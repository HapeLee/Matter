package org.kazusa.matter.core.network

import android.net.Uri
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.kazusa.matter.core.datastore.AuthPreferences
import org.kazusa.matter.core.network.KtorClientFactory.bodyText
import org.kazusa.matter.core.network.KtorClientFactory.decodeBody
import org.kazusa.matter.core.network.KtorClientFactory.jsonBody
import org.kazusa.matter.core.session.SessionExpiredBus
import org.kazusa.matter.data.model.AlbumPhotoPage
import org.kazusa.matter.data.model.ArticleFeedPage
import org.kazusa.matter.data.model.AttitudesDataDto
import org.kazusa.matter.data.model.AttitudesResponse
import org.kazusa.matter.data.model.ChatSendResult
import org.kazusa.matter.data.model.CommentsDataDto
import org.kazusa.matter.data.model.DEFAULT_HOT_SEARCH_CONTAINER_ID
import org.kazusa.matter.data.model.FollowingListTab
import org.kazusa.matter.data.model.HotFeedCategory
import org.kazusa.matter.data.model.HotFeedPage
import org.kazusa.matter.data.model.HotSearchBoard
import org.kazusa.matter.data.model.HotSearchChannel
import org.kazusa.matter.data.model.RepostsDataDto
import org.kazusa.matter.data.model.SearchPageResult
import org.kazusa.matter.data.model.SearchResultItem
import org.kazusa.matter.data.model.SearchResultKind
import org.kazusa.matter.data.model.StatusVisibility
import org.kazusa.matter.data.model.TimelineResponse
import org.kazusa.matter.data.model.UserAlbum
import org.kazusa.matter.data.model.UserPostsPage
import org.kazusa.matter.data.model.UserProfileDetail
import org.kazusa.matter.data.model.UserTimelineResult
import org.kazusa.matter.data.model.VideoFeedPage
import org.kazusa.matter.data.model.WeiboCardContainerDto
import org.kazusa.matter.data.model.WeiboCardDto
import org.kazusa.matter.data.model.WeiboCardResponse
import org.kazusa.matter.data.model.WeiboCommentDto
import org.kazusa.matter.data.model.WeiboConfigResponse
import org.kazusa.matter.data.model.WeiboGroupDto
import org.kazusa.matter.data.model.WeiboSearchCardInfo
import org.kazusa.matter.data.model.WeiboSearchChannelDto
import org.kazusa.matter.data.model.WeiboSearchTabDto
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.model.WeiboUserDto
import org.kazusa.matter.data.model.WeiboUserListPage
import org.kazusa.matter.data.model.classifySearchCard
import org.kazusa.matter.data.model.parseUserProfileDetail
import org.kazusa.matter.data.model.toHotSearchBoard
import org.kazusa.matter.data.model.toSearchCardInfo
import org.kazusa.matter.data.model.toSearchChipGroupOrNull

/**
 * 热门流频道所属的分组类型：`allGroups` 里 `group_type=1` 为「我的频道 / 频道推荐」，
 * `0` 是关注分组（不参与热门流）。
 */
private const val HOT_FEED_GROUP_TYPE = 1

/** 分组流的 `refresh` 固定值：网页版 `mygroups` 页对下拉刷新与翻页都用 4。 */
private const val GROUP_FEED_REFRESH = 4

/**
 * 关注分组流的接口路由。
 *
 * 所有分组统一走网页域 `weibo.com/ajax/feed/…`，参数与网页版 `mygroups?gid=…` 页一致，
 * 不再有 m 站兜底通道（见 [getGroupTimeline]）。
 *
 * 按 gid 前 5 位（微博下发的分组类型，见 `weibo.com/ajax/feed/allGroups`）选路径：
 *
 * - **系统合成 id**（`1` + 类型码 + uid，如 `10001` 全部关注、`10009` 好友圈、`11000` 最新微博）：
 *   其中 `11000/11001/11002`（最新微博 / 原创 / 视频）共用 `friendstimeline`，靠 `fid` 区分。
 * - **真实分组 id**（16 位随机 id：自建分组、特别关注，如 `4249162129677215`）：
 *   同样走 `groupstimeline`，`list_id` 就是 mygroups 页 url 里的 gid。实测「特别关注」
 *   返回 24 条、`max_id` 翻页正常，网页端与分组页 `https://weibo.com/mygroups?gid=` 完全一致。
 */
internal data class GroupFeedRoute(
    /** 网页域路径。 */
    val path: String,
    val count: Int,
    /** 走 `since_id` 增量协议（全部关注 / 最新微博 / 原创 / 视频）。 */
    val incremental: Boolean,
    /** 额外带 `fid = gid`：最新微博 / 原创 / 视频 三个过滤流靠它区分。 */
    val fid: Boolean
) {
    companion object {
        const val PATH_ALL = "/ajax/feed/unreadfriendstimeline"
        const val PATH_FILTERED = "/ajax/feed/friendstimeline"
        const val PATH_GROUP = "/ajax/feed/groupstimeline"
    }
}

internal fun resolveGroupFeedRoute(gid: String): GroupFeedRoute = when (gid.take(5)) {
    "10001" -> GroupFeedRoute(GroupFeedRoute.PATH_ALL, count = 15, incremental = true, fid = false)
    "11000", "11001", "11002" ->
        GroupFeedRoute(GroupFeedRoute.PATH_FILTERED, count = 25, incremental = true, fid = true)
    // 10005 悄悄关注 / 10009 好友圈 / 10010 群微博 / 10013 V+ 分组：网页分组时间线
    "10005", "10009", "10010", "10013" ->
        GroupFeedRoute(GroupFeedRoute.PATH_GROUP, count = 25, incremental = false, fid = false)
    // 真实分组 id（自建分组 / 特别关注）：网页端同样是 groupstimeline，list_id 即 mygroups 的 gid
    else -> GroupFeedRoute(GroupFeedRoute.PATH_GROUP, count = 25, incremental = false, fid = false)
}

class WeiboApiService(
    private val client: HttpClient,
    private val authPreferences: AuthPreferences? = null,
    private val sessionExpiredBus: SessionExpiredBus = SessionExpiredBus(),
    cookieFallbackSource: CookieFallbackSource? = null
) {

    init {
        // CookieAuth 插件的兜底来源：未显式声明凭据的微博内容域请求注入当前会话
        // （或缓存的游客凭据）。不主动请求，避免插件内递归。
        cookieFallbackSource?.bind {
            try {
                authPreferences?.sessionFlow?.firstOrNull()?.cookieHeader?.takeIf { it.isNotBlank() }
                    ?: cachedVisitorCookie.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                ""
            }
        }
    }

    /**
     * 写操作（点赞 / 收藏 / 关注）共用：缓存 `st`、串行提交、识别频控并冷却。
     * 三者共用同一个实例，是因为微博的写操作限流按账号计，分开做会各自把冷却期撑大。
     */
    private val writeSession = WeiboWriteSession(::fetchDesktopWriteCredentials)

    /** 私信图片的 init + 分块上传（协议与失败原因都在那一侧维护）。 */
    private val chatImageUploader = ChatImageUploader(client)

    /**
     * 网页域写凭据（XSRF-TOKEN + 合并 Cookie）缓存。点赞 / 关注走网页端点后成为
     * 最高频的写路径，不能每次都先 GET weibo.com/ 取令牌把请求量翻倍；
     * 与 [writeSession] 的 st 缓存一样按来源 Cookie 失配即重取。
     */
    @Volatile
    private var cachedDesktopCredentials: WeiboWriteCredentials? = null

    private val favoritesApi = FavoritesApi(client, sessionExpiredBus, ::desktopJsonWrite)
    private val replyImageUploadApi = ReplyImageUploadApi(client, writeSession)

    suspend fun uploadReplyImage(cookie: String, bytes: ByteArray): ApiResult<String> =
        replyImageUploadApi.upload(cookie, bytes)

    suspend fun getFavorites(cookie: String, page: Int = 1): ApiResult<UserPostsPage> =
        favoritesApi.getFavorites(cookie, page)

    suspend fun getLikedPosts(cookie: String, page: Int = 1): ApiResult<UserPostsPage> =
        favoritesApi.getLikedPosts(cookie, page)

    suspend fun setFavorite(cookie: String, id: String, favorite: Boolean): ApiResult<Unit> =
        favoritesApi.setFavorite(cookie, id, favorite)

    @Volatile
    private var cachedVisitorCookie: String? = null

    /**
     * 会话失效统一上报：请求确实携带了 Cookie 且微博返回 -100、本地又仍认为处于
     * 登录态（非游客）时，发一次全局失效事件。游客凭据被拒或未携带 Cookie 的 -100
     * 不算会话失效，不触发登出。
     */
    private suspend fun reportSessionExpiredIfLoggedIn(usedCookie: String, code: Int?) {
        if (code != -100 || usedCookie.isBlank()) return
        val prefs = authPreferences ?: return
        val session = try {
            prefs.sessionFlow.firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return
        if (session.isLoggedIn && session.uid.isNotBlank() && session.uid != "guest") {
            sessionExpiredBus.notifySessionExpired()
        }
    }

    /**
     * 获取当前有效 Cookie：
     * 1. 显式传入优先
     * 2. 其次使用登录会话凭据（SUB=...）
     * 3. 兜底获取/复用游客凭据（防止 m.weibo.cn 返回 HTTP 432）
     */
    suspend fun getEffectiveCookie(passedCookie: String = ""): String {
        if (passedCookie.isNotBlank()) return passedCookie
        val sessionCookie = try {
            authPreferences?.sessionFlow?.firstOrNull()?.cookieHeader.orEmpty()
        } catch (_: Exception) {
            ""
        }
        if (sessionCookie.isNotBlank()) {
            return sessionCookie
        }
        if (!cachedVisitorCookie.isNullOrBlank()) {
            return cachedVisitorCookie!!
        }
        val visitor = fetchVisitorCookie()
        if (visitor.isNotBlank()) {
            cachedVisitorCookie = visitor
            return visitor
        }
        return ""
    }

    /**
     * 主页类读接口的统一入口：游客凭据过期（HTTP 432）时刷新游客 Cookie 重试一次。
     *
     * 博主资料 / 相册 / 相册图片 / 视频 / 文章五处原来各自复制同一段脚手架
     * （本地 `request()` + 432 判断 + 换游客凭据重发），这里收敛成一处：
     * 凭据仍然由 [buildEndpoint] 按 [WeiboAuth] 重建（端点定义见 [WeiboEndpoint]），
     * 重试仍 432 就原样返回，错误文案由调用方决定。
     */
    private suspend fun weiboGetWithVisitorRetry(
        buildEndpoint: (WeiboAuth) -> WeiboEndpoint,
        cookie: String,
        block: HttpRequestBuilder.() -> Unit = {}
    ): HttpResponse {
        var effectiveCookie = getEffectiveCookie(cookie)
        var response = client.weiboGet(buildEndpoint(WeiboAuth.Session(effectiveCookie)), block)
        if (response.status.value == 432) {
            // 游客凭据过期：换一张再试一次
            cachedVisitorCookie = null
            val fresh = fetchVisitorCookie()
            if (fresh.isNotBlank()) {
                cachedVisitorCookie = fresh
                effectiveCookie = fresh
                response = client.weiboGet(buildEndpoint(WeiboAuth.Session(effectiveCookie)), block)
            }
        }
        return response
    }

    /**
     * 登录功能专用凭据：显式传入非空则直接用，否则取当前登录会话；游客 / 未登录 /
     * 凭据为空一律返回空串，**绝不回落游客凭据**——游客数据会掩盖真实登录态，
     * 分组与写操作这类登录功能拿游客凭据只会换来难懂的服务端错误。
     * 本地仍标记已登录却拿不到凭据时，先上报会话失效再返回空。
     */
    private suspend fun requireSessionCookie(passedCookie: String = ""): String {
        if (passedCookie.isNotBlank()) return passedCookie
        val session = try {
            authPreferences?.sessionFlow?.firstOrNull()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return ""
        if (!session.isLoggedIn || session.uid == "guest") return ""
        val header = session.cookieHeader
        if (header.isBlank()) {
            sessionExpiredBus.notifySessionExpired()
        }
        return header
    }

    /** 当前登录账号的 uid；游客 / 未登录 / 读取失败一律返回 null。 */
    private suspend fun resolveSessionUid(): String? = try {
        authPreferences?.sessionFlow?.firstOrNull()?.uid?.takeIf { it.isNotBlank() && it != "guest" }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * 向微博 passport 申请游客凭证 (visitor ticket -> SUB)
     */
    suspend fun fetchVisitorCookie(): String {
        return try {
            val response = client.weiboPostForm(
                endpoint = passportEndpoint(
                    path = "/visitor/genvisitor",
                    referer = "https://passport.weibo.com/visitor/visitor?entry=miniblog"
                ),
                form = parameters {
                    append("cb", "gen_visitor")
                    append("fp", "{\"os\":\"1\",\"browser\":\"Chrome120\",\"fonts\":\"undefined\",\"appSeed\":\"\"}")
                }
            )
            val text = response.bodyText()
            val tidRegex = Regex("\"tid\"\\s*:\\s*\"([^\"]+)\"")
            val tid = tidRegex.find(text)?.groupValues?.get(1).orEmpty()
            if (tid.isBlank()) return ""

            val incarnateResp = client.weiboGet(
                endpoint = passportEndpoint("/visitor/visitor")
            ) {
                parameter("a", "incarnate")
                parameter("t", tid)
                parameter("w", "2")
                parameter("c", "095")
                parameter("gc", "")
                parameter("cb", "cross_domain_action")
                parameter("from", "weibo")
            }
            val incarnateText = incarnateResp.bodyText()
            val subRegex = Regex("\"sub\"\\s*:\\s*\"([^\"]+)\"")
            val subpRegex = Regex("\"subp\"\\s*:\\s*\"([^\"]+)\"")
            val sub = subRegex.find(incarnateText)?.groupValues?.get(1).orEmpty()
            val subp = subpRegex.find(incarnateText)?.groupValues?.get(1).orEmpty()
            if (sub.isNotBlank()) {
                val cookie = if (subp.isNotBlank()) "SUB=$sub; SUBP=$subp" else "SUB=$sub"
                android.util.Log.d("WeiboAuth", "fetchVisitorCookie success, length=${cookie.length}")
                cookie
            } else {
                ""
            }
        } catch (e: Exception) {
            android.util.Log.w("WeiboAuth", "fetchVisitorCookie error", e)
            ""
        }
    }

    /**
     * 校验当前会话/Cookie是否有效并获取当前用户信息
     * 请求端点: https://m.weibo.cn/api/config
     */
    suspend fun verifyConfig(cookie: String): ApiResult<WeiboConfigResponse> {
        return try {
            val response = client.weiboGet(mobileEndpoint("/api/config", WeiboAuth.Session(cookie)))
            val rawText = response.bodyText()
            // 响应体含 st / uid / user_token，任何前缀都不允许进日志
            android.util.Log.d("WeiboAuth", "verifyConfig status=${response.status}")
            if (response.status == HttpStatusCode.OK) {
                val body = withContext(Dispatchers.IO) {
                    KtorClientFactory.jsonConfig.decodeFromString<WeiboConfigResponse>(rawText)
                }
                if (body.data?.login == true) {
                    ApiResult.Success(body)
                } else {
                    ApiResult.Error(
                        code = 401,
                        message = "未检测到有效登录状态，请在网页中完成登录并点击「完成登录」"
                    )
                }
            } else {
                ApiResult.Error(
                    code = response.status.value,
                    message = "验证登录失败: HTTP ${response.status}"
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("WeiboAuth", "verifyConfig error", e)
            ApiResult.Error(message = e.localizedMessage ?: "网络请求异常", throwable = e)
        }
    }

    /**
     * 获取首页关注流，即网页端「全部关注」分组。
     *
     * 走网页 `weibo.com`，gid 由 `10001` + 当前账号 uid 组成，与 `mygroups` 第一个分组同源
     * （见 [resolveGroupFeedRoute]），参数与解析也因此与 [getGroupTimeline] 完全一致。
     *
     * @param uid 当前账号 uid；为空时从登录会话读取。
     * @param page 仅保留给调用方的页计数，网页通道用 `max_id` 游标翻页，不参与请求。
     */
    suspend fun getFriendsTimeline(
        cookie: String,
        page: Int = 1,
        maxId: Long? = null,
        uid: String = ""
    ): ApiResult<TimelineResponse> {
        // 关注流是登录功能：没有登录凭据直接提示，不发游客请求
        val sessionCookie = requireSessionCookie(cookie)
        if (sessionCookie.isBlank()) {
            return ApiResult.Error(code = 401, message = "请先登录微博账号")
        }
        val targetUid = uid.takeIf { it.isNotBlank() && it != "guest" } ?: resolveSessionUid()
        if (targetUid.isNullOrBlank()) {
            return ApiResult.Error(message = "获取当前账号信息失败，请重新登录")
        }
        return fetchWebGroupTimeline(sessionCookie, "10001$targetUid", maxId)
    }

    /**
     * 获取指定分组下的微博时间线。
     *
     * 分组 id 来自 `weibo.com/ajax/feed/allGroups`，一律走**同站**的 web 分组接口，
     * 参数与网页版 `mygroups?gid=…` 页一致，路径选择见 [resolveGroupFeedRoute]。
     *
     * 只走网页通道，没有任何 m 站兜底：`ok=1` + 空 `statuses` 就是该分组确实没有微博
     * （`total_number=0`），失败如实返回错误，不再换域名重试。
     *
     * 分页是游标不是页码：`max_id` 传上一页最后一条微博 id（服务端回的 `max_id_str`
     * 就是它），首屏不传该参数（空串会被判 400）。[page] 仅为兼容调用方保留，
     * 不参与请求。
     */
    suspend fun getGroupTimeline(
        cookie: String,
        gid: String,
        page: Int = 1,
        maxId: Long? = null
    ): ApiResult<TimelineResponse> {
        val effectiveCookie = requireSessionCookie(cookie)
        if (effectiveCookie.isBlank()) {
            return ApiResult.Error(code = 401, message = "请先登录微博账号")
        }
        if (gid.isBlank()) {
            return ApiResult.Error(message = "分组参数为空")
        }
        return fetchWebGroupTimeline(effectiveCookie, gid, maxId)
    }

    /** 网页域分组流（与网页版 `mygroups` 页同一套参数）。 */
    private suspend fun fetchWebGroupTimeline(
        effectiveCookie: String,
        gid: String,
        maxId: Long?
    ): ApiResult<TimelineResponse> {
        val route = resolveGroupFeedRoute(gid)
        return try {
            val response = client.weiboGet(
                endpoint = webEndpoint(
                    path = route.path,
                    auth = WeiboAuth.Session(effectiveCookie),
                    referer = "https://weibo.com/mygroups?gid=$gid"
                )
            ) {
                parameter("list_id", gid)
                parameter("refresh", GROUP_FEED_REFRESH)
                parameter("count", route.count)
                // 首屏不传 max_id：网页端首屏同样不带，实测传空串会被判 400 Bad Request，
                // 之前「网页组流取不到内容」就是这个原因
                maxId?.takeIf { it > 0 }?.let { parameter("max_id", it) }
                if (route.incremental) {
                    // 首屏和下拉刷新都要「最新一页」，since_id=0 表示不设下界；
                    // 已读位置与新旧衔接由客户端自己合并（TimelineViewModel 的刷新逻辑）。
                    parameter("since_id", 0)
                } else {
                    parameter("fast_refresh", 1)
                }
                if (route.fid) {
                    parameter("fid", gid)
                }
            }
            if (response.status != HttpStatusCode.OK) {
                return ApiResult.Error(
                    code = response.status.value,
                    message = "加载分组动态失败: HTTP ${response.status}"
                )
            }
            val root = response.jsonBody().jsonObject
            val ok = (root["ok"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            if (ok != 1) {
                reportSessionExpiredIfLoggedIn(effectiveCookie, ok)
                return ApiResult.Error(code = ok, message = readGroupFeedErrorMessage(root, ok))
            }
            // 分组流既可能把微博放在顶层 statuses，也可能放在 data.statuses
            val statusesArray = (root["statuses"] as? JsonArray)
                ?: ((root["data"] as? JsonObject)?.get("statuses") as? JsonArray)
                ?: JsonArray(emptyList())
            val posts = withContext(Dispatchers.IO) {
                statusesArray.mapNotNull { element ->
                    runCatching {
                        KtorClientFactory.jsonConfig.decodeFromJsonElement<WeiboStatusDto>(element)
                    }.getOrNull()
                }
            }
            val totalNumber = (root["total_number"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                ?: ((root["data"] as? JsonObject)?.get("total_number") as? JsonPrimitive)
                    ?.contentOrNull?.toIntOrNull()
            ApiResult.Success(
                TimelineResponse(ok = 1, statuses = posts, totalNumber = totalNumber)
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("TimelineApi", "getGroupTimeline failed: gid=$gid", e)
            ApiResult.Error(message = e.localizedMessage ?: "加载分组微博异常", throwable = e)
        }
    }

    /** 分组流失败原因：优先用服务端文案，避免只剩「ok=0」无从判断。 */
    private fun readGroupFeedErrorMessage(root: JsonObject, ok: Int): String {
        val serverMessage = (root["msg"] as? JsonPrimitive)?.contentOrNull
            ?: (root["message"] as? JsonPrimitive)?.contentOrNull
            ?: (root["error"] as? JsonPrimitive)?.contentOrNull
        if (!serverMessage.isNullOrBlank()) return serverMessage
        return if (ok == -100) SessionExpiredBus.MESSAGE else "获取分组微博失败(ok=$ok)"
    }

    /** `allGroups` 的原始 JSON 快照：属主是发起请求的 Cookie，换号 / 游客态天然不复用。 */
    private data class AllGroupsSnapshot(val owner: String, val root: JsonObject)

    /** 只保护「读缓存 / 挂上在途任务」这一小段，网络请求在锁外跑。 */
    private val allGroupsLock = Mutex()

    @Volatile
    private var allGroupsSnapshot: AllGroupsSnapshot? = null

    /**
     * 在途请求（Cookie + 任务）。同一个 Cookie 的并发调用共享**同一次**网络往返，
     * 但失败不共享：任务一结束就摘掉，下次调用会重新发起。
     */
    @Volatile
    private var allGroupsInFlight: Pair<String, Deferred<ApiResult<JsonObject>>>? = null

    /**
     * 共享在途请求用的作用域。
     *
     * 请求不能挂在某一个调用方的 scope 上：发起方（比如动态页）被取消时，
     * 同一时刻在等这份数据的另一个入口（热门频道）会被连带取消。
     */
    private val sharedFetchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 拉取（或复用）`allGroups` 原始响应。
     *
     * 关注分组（[getFollowGroups]）与热门频道（[getHotFeedCategories]）读的是同一份数据，
     * 一次会话里没必要各拉一次；缓存原始 JSON，两个入口按各自需要解析。
     * 分组增删改成功后主动失效，页面上的「重新加载」用 [forceRefresh] 绕过。
     *
     * 这里刻意**不**把网络请求放进 mutex：之前是 `mutex.withLock { 网络请求 }`，
     * 一旦分组请求超时（最长 20s），另一条完全无关的入口就得排在它后面；
     * 而失败后下一个调用方还会在锁里再发一次，最坏情况要等两倍超时。
     * 改成单飞（single-flight）后：并发调用共享一次往返，失败不会再叠一层等待。
     */
    private suspend fun loadAllGroups(
        cookie: String,
        forceRefresh: Boolean
    ): ApiResult<JsonObject> {
        if (!forceRefresh) {
            allGroupsSnapshot?.takeIf { it.owner == cookie }?.let { return ApiResult.Success(it.root) }
        }
        val flight = allGroupsLock.withLock {
            allGroupsInFlight?.takeIf { it.first == cookie }?.second ?: run {
                val created = sharedFetchScope.async { fetchAllGroups(cookie) }
                created.invokeOnCompletion {
                    val current = allGroupsInFlight
                    if (current != null && current.second === created) allGroupsInFlight = null
                }
                allGroupsInFlight = cookie to created
                created
            }
        }
        return flight.await()
    }

    /** 真正的一次 `allGroups` 往返；不持锁、不读缓存。 */
    private suspend fun fetchAllGroups(cookie: String): ApiResult<JsonObject> {
        val response = try {
            client.weiboGet(
                webEndpoint(
                    path = "/ajax/feed/allGroups",
                    auth = WeiboAuth.Session(cookie),
                    referer = "https://weibo.com"
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("WeiboApi", "allGroups request failed", e)
            return ApiResult.Error(message = e.localizedMessage ?: "获取分组数据异常", throwable = e)
        }
        if (response.status != HttpStatusCode.OK) {
            return ApiResult.Error(
                code = response.status.value,
                message = "获取分组数据失败: HTTP ${response.status}"
            )
        }
        val root = try {
            response.jsonBody().jsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return ApiResult.Error(message = "分组数据格式异常", throwable = e)
        }
        val ok = root["ok"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        if (ok != 1) {
            reportSessionExpiredIfLoggedIn(cookie, ok)
            return ApiResult.Error(
                code = ok,
                message = if (ok == -100) SessionExpiredBus.MESSAGE else "获取分组数据失败(ok=$ok)"
            )
        }
        allGroupsSnapshot = AllGroupsSnapshot(cookie, root)
        return ApiResult.Success(root)
    }

    /** 分组本身发生变化（新建 / 改名 / 删除 / 排序）后，共用快照必须作废。 */
    private fun invalidateAllGroupsCache() {
        allGroupsSnapshot = null
    }

    /**
     * 获取用户关注分组列表。
     *
     * 端点: `https://weibo.com/ajax/feed/allGroups`（与热门频道共用一份缓存，见 [loadAllGroups]）。
     *
     * 分组时间线（[getGroupTimeline]）与分组增删改都用同一套 web 协议，所以这里只走 web。
     * `api.weibo.com/2/friendships/groups.json` 需要 access_token 而网页登录只给 Cookie，
     * 拿不到（实测 403 `auth by Null spi`），混进来只会多打一次必然失败的请求，
     * 还会把真实失败伪装成「只有全部关注」。
     */
    suspend fun getFollowGroups(
        cookie: String,
        forceRefresh: Boolean = false
    ): ApiResult<List<WeiboGroupDto>> {
        val effectiveCookie = requireSessionCookie(cookie)
        if (effectiveCookie.isBlank()) {
            return ApiResult.Error(code = 401, message = "请先登录微博账号")
        }
        val jsonRoot = when (val groupsResult = loadAllGroups(effectiveCookie, forceRefresh)) {
            is ApiResult.Success -> groupsResult.data
            is ApiResult.Error -> return groupsResult
            ApiResult.Loading -> return ApiResult.Loading
        }
        return try {
            val groupsArr = jsonRoot["groups"]?.jsonArray
            val list = mutableListOf<WeiboGroupDto>()
            list.add(WeiboGroupDto(id = 0, gid = "all", name = "全部关注", memberCount = 0))

            groupsArr?.forEach { groupCat ->
                val catObj = groupCat.jsonObject
                val catType = catObj["group_type"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val catTitle = catObj["title"]?.jsonPrimitive?.content.orEmpty()

                // 只取关注相关分组（默认分组、我的分组等 group_type == 0），
                // 推荐频道整组是 group_type == 1。
                if (catType == 0 || catTitle.contains("分组")) {
                    val subGroups = catObj["group"]?.jsonArray
                    subGroups?.forEach { gObj ->
                        val g = gObj.jsonObject
                        val gid = g["gid"]?.jsonPrimitive?.content.orEmpty()
                        val title = g["title"]?.jsonPrimitive?.content
                            ?: g["name"]?.jsonPrimitive?.content
                            ?: g["navigation_title"]?.jsonPrimitive?.content.orEmpty()
                        val cnt = g["count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        // 带 containerid 的是推荐频道（热门 / 同城 / 榜单…），不是关注分组
                        val isChannel = g["containerid"]?.jsonPrimitive?.content
                            ?.isNotBlank() == true

                        // 过滤掉全部关注（已作为第一项）和热门推荐等非关注类条目。
                        // 10001 + uid 就是网页端的「全部关注」，与网页版判断分组类型的
                        // 方式一致（gid 前 5 位），不再用简单的 startsWith 猜。
                        val isFollowGroup = gid.isNotBlank() &&
                                title.isNotBlank() &&
                                !isChannel &&
                                !title.contains("热门") &&
                                !title.contains("推荐") &&
                                title != "全部关注" &&
                                title != "关注" &&
                                gid.take(5) != "10001"

                        if (isFollowGroup && list.none { it.gid == gid }) {
                            list.add(
                                WeiboGroupDto(
                                    id = gid.toLongOrNull(),
                                    idStr = gid,
                                    gid = gid,
                                    name = title,
                                    title = title,
                                    memberCount = cnt
                                )
                            )
                        }
                    }
                }
            }

            if (list.size <= 1) {
                return ApiResult.Error(message = "服务端未下发可用的关注分组")
            }
            android.util.Log.i(
                "WeiboApi",
                "getFollowGroups: parsed ${list.size} groups: ${list.map { "${it.name}(${it.gid})" }}"
            )
            ApiResult.Success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("WeiboApi", "getFollowGroups failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "获取关注分组异常", throwable = e)
        }
    }

    /**
     * 创建关注分组。
     *
     * 端点: POST `https://weibo.com/ajax/feed/createGroup`。
     *
     * 只走 web：分组列表来自 `allGroups`，gid 是 web 侧 id；
     * `api.weibo.com/2/friendships/groups` 系列接口需要 access_token（网页登录的 Cookie
     * 拿不到，实测 403 `auth by Null spi`），混用只会多打一次必然失败的请求。
     *
     * 与点赞 / 关注共用 [WeiboWriteSession]：微博的写限流按账号计，分组写操作同样会触发，
     * 走闸门才能串行提交、在冷却期内直接拒绝而不是继续给服务端记账。
     */
    suspend fun createGroup(
        name: String,
        description: String = "",
        cookie: String = ""
    ): ApiResult<WeiboGroupDto> {
        val effectiveCookie = requireSessionCookie(cookie)
        if (effectiveCookie.isBlank()) {
            return ApiResult.Error(code = 401, message = "未检测到有效登录状态，无法同步至微博")
        }
        return try {
            writeSession.execute(
                cookie = effectiveCookie,
                action = "创建分组",
                // 分组接口只用会话 Cookie 鉴权（WeiboAuth.Session），不需要 XSRF 令牌
                needsXsrfToken = false
            ) { credentials ->
                val response = client.weiboPostForm(
                    endpoint = webEndpoint(
                        "/ajax/feed/createGroup",
                        WeiboAuth.Session(credentials.cookieHeader)
                    ),
                    form = parameters { append("name", name) }
                )
                writeHttpError(response)?.let { return@execute it }
                val jsonRoot = response.jsonBody().jsonObject
                val ok = jsonRoot["ok"]?.jsonPrimitive?.intOrNull ?: 0
                val gid = jsonRoot["gid"]?.jsonPrimitive?.contentOrNull
                    ?: jsonRoot["id"]?.jsonPrimitive?.contentOrNull
                // 只有 ok == 1 且服务端真的给了 gid 才算建成功；
                // 以前拿当前时间戳兜底 gid，失败也会当成功写进本地分组。
                if (ok == 1 && !gid.isNullOrBlank()) {
                    invalidateAllGroupsCache()
                    ApiResult.Success(
                        WeiboGroupDto(
                            id = gid.toLongOrNull(),
                            idStr = gid,
                            gid = gid,
                            name = name,
                            title = name,
                            memberCount = 0
                        )
                    )
                } else {
                    groupWriteError(jsonRoot, "创建分组失败")
                }
            }
        } catch (e: WriteGateException) {
            ApiResult.Error(code = e.code, message = e.message.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ApiResult.Error(message = e.localizedMessage ?: "网络异常，创建分组失败", throwable = e)
        }
    }

    /**
     * 更新关注分组名称。
     *
     * 端点: POST `https://weibo.com/ajax/feed/updateGroupName`（gid 是 web 侧 id，
     * 与 `api.weibo.com` 的 `list_id` 协议不是一套；后者需要 access_token，见 [getFollowGroups]）。
     */
    suspend fun updateGroupName(
        gid: String,
        name: String,
        cookie: String = ""
    ): ApiResult<Unit> {
        val effectiveCookie = requireSessionCookie(cookie)
        if (effectiveCookie.isBlank()) {
            return ApiResult.Error(code = 401, message = "未检测到有效登录状态，无法同步至微博")
        }
        return try {
            writeSession.execute(
                cookie = effectiveCookie,
                action = "重命名分组",
                needsXsrfToken = false
            ) { credentials ->
                val response = client.weiboPostForm(
                    endpoint = webEndpoint(
                        "/ajax/feed/updateGroupName",
                        WeiboAuth.Session(credentials.cookieHeader)
                    ),
                    form = parameters {
                        append("gid", gid)
                        append("name", name)
                    }
                )
                writeHttpError(response)?.let { return@execute it }
                val jsonRoot = response.jsonBody().jsonObject
                // HTTP 200 不代表改成功，只看业务 ok
                val ok = jsonRoot["ok"]?.jsonPrimitive?.intOrNull ?: 0
                if (ok == 1) {
                    invalidateAllGroupsCache()
                    ApiResult.Success(Unit)
                } else {
                    groupWriteError(jsonRoot, "更新分组名称失败")
                }
            }
        } catch (e: WriteGateException) {
            ApiResult.Error(code = e.code, message = e.message.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ApiResult.Error(message = e.localizedMessage ?: "网络异常，更新分组失败", throwable = e)
        }
    }

    /**
     * 删除关注分组。
     *
     * 端点: POST `https://weibo.com/ajax/feed/destroyGroup`（同 [updateGroupName]，只走 web）。
     */
    suspend fun destroyGroup(
        gid: String,
        cookie: String = ""
    ): ApiResult<Unit> {
        val effectiveCookie = requireSessionCookie(cookie)
        if (effectiveCookie.isBlank()) {
            return ApiResult.Error(code = 401, message = "未检测到有效登录状态，无法同步至微博")
        }
        return try {
            writeSession.execute(
                cookie = effectiveCookie,
                action = "删除分组",
                needsXsrfToken = false
            ) { credentials ->
                val response = client.weiboPostForm(
                    endpoint = webEndpoint(
                        "/ajax/feed/destroyGroup",
                        WeiboAuth.Session(credentials.cookieHeader)
                    ),
                    form = parameters { append("gid", gid) }
                )
                writeHttpError(response)?.let { return@execute it }
                val jsonRoot = response.jsonBody().jsonObject
                val ok = jsonRoot["ok"]?.jsonPrimitive?.intOrNull ?: 0
                if (ok == 1) {
                    invalidateAllGroupsCache()
                    ApiResult.Success(Unit)
                } else {
                    groupWriteError(jsonRoot, "删除分组失败")
                }
            }
        } catch (e: WriteGateException) {
            ApiResult.Error(code = e.code, message = e.message.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ApiResult.Error(message = e.localizedMessage ?: "网络异常，删除分组失败", throwable = e)
        }
    }

    /**
     * 更新关注分组排序。
     *
     * 端点: POST `https://weibo.com/ajax/feed/setGroupOrder`（参数是逗号分隔的 web gid）。
     * 本地顺序已落盘，这里失败不影响使用，只把结果如实交回调用方。
     */
    suspend fun setGroupOrder(
        gids: List<String>,
        cookie: String = ""
    ): ApiResult<Unit> {
        val effectiveCookie = requireSessionCookie(cookie)
        if (effectiveCookie.isBlank()) {
            return ApiResult.Success(Unit)
        }
        val gidListStr = gids.filter { it.isNotBlank() && it != "all" }.joinToString(",")
        if (gidListStr.isBlank()) return ApiResult.Success(Unit)

        return try {
            // 排序不是关键写操作：失败只上报调用方，但同样经过闸门，
            // 免得在频控冷却期里继续试探服务端（那会延长账号的冷却）
            writeSession.execute(
                cookie = effectiveCookie,
                action = "分组排序",
                needsXsrfToken = false
            ) { credentials ->
                val response = client.weiboPostForm(
                    endpoint = webEndpoint(
                        "/ajax/feed/setGroupOrder",
                        WeiboAuth.Session(credentials.cookieHeader)
                    ),
                    form = parameters { append("gids", gidListStr) }
                )
                writeHttpError(response)?.let { return@execute it }
                // 排序结果也体现在 allGroups 的顺序里，快照一并作废
                invalidateAllGroupsCache()
                ApiResult.Success(Unit)
            }
        } catch (e: WriteGateException) {
            ApiResult.Error(code = e.code, message = e.message.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("WeiboApi", "setGroupOrder failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "网络异常，同步分组排序失败", throwable = e)
        }
    }

    /**
     * 获取微博实时热搜榜。
     *
     * 端点: `https://m.weibo.cn/api/container/getIndex?containerid=<榜单容器>`。
     *
     * @param containerId **榜单**的 containerid：默认 [DEFAULT_HOT_SEARCH_CONTAINER_ID]（「我的」榜），
     *   切换到别的榜单时传服务端 [HotSearchChannel.containerid]（如 `filter_type=realtimehot` 是热搜榜，`filter_type=fun` 是文娱榜）；
     *   这份列表来自服务端下发的 `cardlist_head_cards[].channel_list[]`，客户端不写死榜名。
     *
     * 返回**结构化榜单**（不是一条扁平列表）：若干榜段（段内含置顶 / 排名 / 推广位 / 查看更多），
     * 外加入口卡与可切换的榜单列表。归一逻辑见 [toHotSearchBoard]。
     */
    suspend fun getHotSearch(
        containerId: String = DEFAULT_HOT_SEARCH_CONTAINER_ID,
        /**
         * 经纬度：`filter_type=region`（同城榜）**必须带**，否则服务端只回一条「位置信息获取失败」；
         * 带上之后榜单名会变成城市名（如「北京」）。其他榜单不需要，传 null 即可。
         */
        latLon: Pair<Double, Double>? = null,
        cookie: String = ""
    ): ApiResult<HotSearchBoard> {
        return try {
            val effectiveCookie = getEffectiveCookie(cookie)
            val response = client.weiboGet(
                mobileEndpoint(
                    path = "/api/container/getIndex",
                    auth = WeiboAuth.Session(effectiveCookie)
                )
            ) {
                parameter("containerid", containerId)
                if (latLon != null) {
                    parameter("lat", latLon.first)
                    parameter("lon", latLon.second)
                }
                // m 站 PWA 专用头：缺了会被当普通网页请求，返回结构不同
                header("MWeibo-Pwa", "1")
            }
            if (response.status == HttpStatusCode.OK) {
                val cardResp = response.decodeBody<WeiboCardResponse>()
                val board = cardResp.data?.toHotSearchBoard() ?: HotSearchBoard()
                ApiResult.Success(board)
            } else {
                ApiResult.Error(code = response.status.value, message = "获取热搜榜失败: HTTP ${response.status}")
            }
        } catch (e: Exception) {
            android.util.Log.e("WeiboApi", "getHotSearch failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "加载热搜榜异常", throwable = e)
        }
    }

    /**
     * 热门流的分类（频道）列表。
     *
     * 端点: `https://weibo.com/ajax/feed/allGroups`（与关注分组共用一份缓存，见 [loadAllGroups]）。
     *
     * 只取 `group_type=1` 的两组：**我的频道**（热门 / 同城 / 实时 / 榜单 / 明星 …）
     * 与**频道推荐**（财经 / 读书 / 摄影 …）。`group_type=0` 是关注分组，不属于热门流。
     *
     * 每项频道自带 `gid`（请求热门流时作为 `group_id`）与 `containerid`
     * （请求热门流时作为 `containerid`），分类名与顺序完全按服务端下发。
     */
    suspend fun getHotFeedCategories(
        cookie: String = "",
        forceRefresh: Boolean = false
    ): ApiResult<List<HotFeedCategory>> {
        val effectiveCookie = getEffectiveCookie(cookie)
        val root = when (val groupsResult = loadAllGroups(effectiveCookie, forceRefresh)) {
            is ApiResult.Success -> groupsResult.data
            is ApiResult.Error -> return groupsResult
            ApiResult.Loading -> return ApiResult.Loading
        }
        return try {
            val categories = mutableListOf<HotFeedCategory>()
            (root["groups"] as? JsonArray).orEmpty().forEach { groupElement ->
                val group = groupElement as? JsonObject ?: return@forEach
                val groupType = (group["group_type"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                // 0 = 关注分组；1 = 热门频道
                if (groupType != HOT_FEED_GROUP_TYPE) return@forEach
                (group["group"] as? JsonArray).orEmpty().forEach { itemElement ->
                    val item = itemElement as? JsonObject ?: return@forEach
                    val gid = (item["gid"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    val containerId = (item["containerid"] as? JsonPrimitive)?.contentOrNull
                        ?.trim()
                        ?.ifBlank { null }
                        ?: gid
                    val title = (item["title"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                    if (gid.isBlank() || containerId.isBlank() || title.isBlank()) return@forEach
                    if (categories.none { it.containerId == containerId }) {
                        categories += HotFeedCategory(
                            id = gid,
                            containerId = containerId,
                            title = title
                        )
                    }
                }
            }
            if (categories.isEmpty()) {
                ApiResult.Error(message = "服务端未下发可用的热门分类")
            } else {
                ApiResult.Success(categories)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("WeiboApi", "getHotFeedCategories failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "获取热门分类异常", throwable = e)
        }
    }

    /**
     * 热门流（「热门」页每个分类下的微博流）。
     *
     * 端点: `https://weibo.com/ajax/feed/hottimeline`
     * 参数: `since_id` / `max_id` 分页，`group_id` + `containerid` 选分类，
     * `extparam=discover|new_feed` 表示发现页信息流。
     *
     * 响应的 `since_id` 是「字符串里再套一层 JSON」，已由 [readHotFeedSinceId] 拆开；
     * 下游按 [HotFeedPage.nextSinceId] + [HotFeedPage.nextMaxId] 原样回传即可翻页。
     */
    suspend fun getHotFeed(
        groupId: String,
        containerId: String,
        sinceId: String = "0",
        maxId: String = "0",
        count: Int = 10,
        cookie: String = ""
    ): ApiResult<HotFeedPage> {
        return try {
            if (groupId.isBlank() || containerId.isBlank()) {
                return ApiResult.Error(message = "热门分类参数为空")
            }
            val effectiveCookie = getEffectiveCookie(cookie)
            val response = client.weiboGet(
                webEndpoint("/ajax/feed/hottimeline", WeiboAuth.Session(effectiveCookie))
            ) {
                parameter("since_id", sinceId.ifBlank { "0" })
                parameter("refresh", "0")
                parameter("group_id", groupId)
                parameter("containerid", containerId)
                parameter("extparam", "discover|new_feed")
                parameter("max_id", maxId.ifBlank { "0" })
                parameter("count", count)
            }
            if (response.status != HttpStatusCode.OK) {
                return ApiResult.Error(
                    code = response.status.value,
                    message = "获取热门流失败: HTTP ${response.status}"
                )
            }
            val root = response.jsonBody().jsonObject
            val ok = (root["ok"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            if (ok != 1) {
                reportSessionExpiredIfLoggedIn(effectiveCookie, ok)
                val msg = (root["msg"] as? JsonPrimitive)?.contentOrNull
                    ?: (root["message"] as? JsonPrimitive)?.contentOrNull
                    ?: if (ok == -100) SessionExpiredBus.MESSAGE else "获取热门流失败(ok=$ok)"
                return ApiResult.Error(code = ok, message = msg)
            }

            val posts = withContext(Dispatchers.IO) {
                (root["statuses"] as? JsonArray).orEmpty().mapNotNull { element ->
                    runCatching {
                        KtorClientFactory.jsonConfig.decodeFromJsonElement<WeiboStatusDto>(element)
                    }.getOrNull()
                }.distinctBy { it.listKey }
            }

            val nextSinceId = readHotFeedSinceId(root["since_id"])
            val nextMaxId = (root["max_id"] as? JsonPrimitive)?.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() }

            ApiResult.Success(
                HotFeedPage(
                    posts = posts,
                    nextSinceId = nextSinceId,
                    nextMaxId = nextMaxId,
                    hasMore = posts.isNotEmpty() &&
                        !nextSinceId.isNullOrBlank() &&
                        nextSinceId != sinceId
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("WeiboApi", "getHotFeed failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "加载热门流异常", throwable = e)
        }
    }

    /**
     * 热门流的 `since_id` 是「字符串里再套一层 JSON」（`"{\"since_id\":\"5355…\"}"`），
     * 偶尔直接给数字/字符串，这里统一拆出真正的游标。
     */
    private fun readHotFeedSinceId(element: JsonElement?): String? {
        val raw = (element as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        if (raw.isBlank()) return null
        if (!raw.startsWith("{")) return raw
        return runCatching {
            (KtorClientFactory.jsonConfig.parseToJsonElement(raw).jsonObject["since_id"] as? JsonPrimitive)
                ?.contentOrNull
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * 关键词搜索微博（只取微博列表，供内部解析 uid / 昵称等场景使用）。
     *
     * 需要分栏配置请用 [searchWeiboPage]。
     */
    suspend fun searchWeibo(
        keyword: String,
        page: Int = 1,
        searchType: Int = 1,
        cookie: String = ""
    ): ApiResult<List<WeiboStatusDto>> {
        return when (
            val res = searchWeiboPage(keyword = keyword, page = page, searchType = searchType, cookie = cookie)
        ) {
            is ApiResult.Success -> ApiResult.Success(res.data.statuses)
            is ApiResult.Error -> res
            is ApiResult.Loading -> ApiResult.Error(message = "搜索动态异常")
        }
    }

    /**
     * 关键词搜索微博（含服务端下发的分栏配置）
     * 端点: https://m.weibo.cn/api/container/getIndex?containerid=...&page_type=searchall&page={page}
     *
     * containerid 有两种来源：
     * - [containerId] 非空：直接用服务端 `data.tabs[i].containerid`（切分栏时用这个）；
     * - [containerId] 为空：用 `100103type={searchType}&q={keyword}&t=1` 作为「综合」首屏请求
     *   （`t=1` 表示实时综合排序）。
     *
     * 响应里的 `data.cardlistInfo.cardlist_head_cards[0].channel_list[]`
     * （每项有 `name` / `containerid`）就是微博的「搜索分类」，
     * 由服务端下发，客户端不硬编码分栏。解析见 [resolveSearchTabs]。
     *
     * @param searchType 首屏 containerid 里的 `100103type=N`；`1` = 「综合」。
     */
    suspend fun searchWeiboPage(
        keyword: String,
        page: Int = 1,
        searchType: Int = 1,
        containerId: String? = null,
        cookie: String = ""
    ): ApiResult<SearchPageResult> {
        return try {
            val effectiveCookie = getEffectiveCookie(cookie)
            val requestContainerId = containerId?.takeIf { it.isNotBlank() }
                ?: defaultSearchContainerId(keyword, searchType)

            val response = client.weiboGet(
                mobileEndpoint(
                    path = "/api/container/getIndex",
                    auth = WeiboAuth.Session(effectiveCookie)
                )
            ) {
                parameter("containerid", requestContainerId)
                parameter("page_type", "searchall")
                parameter("page", page)
                // m 站 PWA 专用头：缺了会被当普通网页请求，返回结构不同
                header("MWeibo-Pwa", "1")
            }
            if (response.status == HttpStatusCode.OK) {
                val cardResp = response.decodeBody<WeiboCardResponse>()
                val container = cardResp.data
                val tabs = resolveSearchTabs(container)
                // 首屏请求打的就是「默认栏」的 containerid（`100103type=1&q=…&t=1`）。
                // 服务端没给 `tab_default` 时，用 containerid 反查该栏下标 ——
                // 不依赖任何分栏名文案（客户端零硬编码），否则会把首屏数据种到别的分栏上。
                val defaultIndex = container?.tabDefault?.takeIf { it in tabs.indices }
                    ?: tabs.indexOfFirst { it.containerid == requestContainerId }.takeIf { it >= 0 }
                    ?: 0
                ApiResult.Success(
                    SearchPageResult(
                        items = buildSearchItems(container?.cards.orEmpty()),
                        tabs = tabs,
                        defaultTabIndex = defaultIndex
                    )
                )
            } else {
                ApiResult.Error(code = response.status.value, message = "搜索失败: HTTP ${response.status}")
            }
        } catch (e: Exception) {
            android.util.Log.e("WeiboApi", "searchWeiboPage failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "搜索动态异常", throwable = e)
        }
    }

    /**
     * 解析搜索页的分栏（分类）列表。
     *
     * 来源优先级（真机实测）：
     * 1. **`data.cardlistInfo.cardlist_head_cards[].channel_list`** —— 搜索分类的真正来源。
     *    真机 `page_type=searchall` 响应里每栏形如
     *    `{"id":"1","name":"综合","containerid":"100103type=1&q=美食&t=1","scheme":"sinaweibo://selectchannel?…"}`。
     * 2. `data.cardlistInfo.sub_header` —— 兼容来源。
     * 3. `data.tabs` —— 通用 container 的分栏配置，搜索页**不**走这条。
     *
     * 三者都拿不到时返回空列表，调用方退化成单个兜底分栏。
     */
    private fun resolveSearchTabs(container: WeiboCardContainerDto?): List<WeiboSearchTabDto> {
        val fromHeadCards = container?.cardlistInfo?.cardlistHeadCards.orEmpty()
            .flatMap { it.channelList }
            .mapNotNull { it.toSearchTab() }
        if (fromHeadCards.isNotEmpty()) return fromHeadCards

        val fromSubHeader = container?.cardlistInfo?.subHeader.orEmpty()
            .mapNotNull { it.toSearchTab() }
        if (fromSubHeader.isNotEmpty()) return fromSubHeader

        return container?.tabs.orEmpty()
    }

    /**
     * 把 `cardlist_head_cards[].channel_list[]` 的一个分栏转成 [WeiboSearchTabDto]。
     *
     * 分栏名取 `name`；containerid 先取自带的 `containerid` 字段，没有才从 `scheme` 里抠
     * `containerid` 参数（注意服务端 scheme 里的 `containerid` 值**未做 URL 编码**，
     * 形如 `sinaweibo://selectchannel?containerid=100103type=1&q=美食&t=1`，
     * 抠参数会被 `&` 截断，因此仅在缺字段时兜底）。
     */
    private fun WeiboSearchChannelDto.toSearchTab(): WeiboSearchTabDto? {
        val label = name?.takeIf { it.isNotBlank() } ?: return null
        val cid = containerid?.takeIf { it.isNotBlank() }
            ?: scheme?.substringAfter("containerid=", "")
                ?.takeIf { it.isNotBlank() }

        return WeiboSearchTabDto(
            title = label,
            containerid = cid,
            scheme = scheme,
            showDot = false
        )
    }

    /**
     * 把 `cardlistInfo.sub_header` 里的一张分栏卡转成 [WeiboSearchTabDto]（兼容来源）。
     *
     * 分栏名取 `title`（依次回落到 `card_type_name`）；
     * containerid 先取卡片自带的 `containerId`，没有就从 `scheme` 里抠 `containerid` 参数。
     */
    private fun WeiboCardDto.toSearchTab(): WeiboSearchTabDto? {
        val label = title?.takeIf { it.isNotBlank() }
            ?: cardTypeName?.takeIf { it.isNotBlank() }
            ?: return null

        val cid = containerId?.takeIf { it.isNotBlank() }
            ?: scheme?.let { runCatching { Uri.parse(it).getQueryParameter("containerid") }.getOrNull() }
                ?.takeIf { it.isNotBlank() }

        return WeiboSearchTabDto(
            title = label,
            containerid = cid,
            type = cardType,
            scheme = scheme,
            showDot = false
        )
    }

    /**
     * 把搜索响应的 `data.cards[]` 展开成结构化的搜索结果项。
     *
     * 响应是一棵卡片树：顶层卡可能自带 `mblog`（微博卡），也可能是用户卡；
     * 每张卡还可以带 `card_group[]`，条目同样是「微博 / 用户 / 话题」三种之一。
     *
     * 分派规则：
     * - 卡片组整组都是纯文本词条 → [SearchResultKind.CHIP_GROUP]（相关搜索 / 热门话题）
     * - 有 `mblog` → [SearchResultKind.POST]（**优先**，避免与卡片自身字段重复出两张）
     * - 有 `user` → [SearchResultKind.USER]
     * - 条目有标题 / 副标题 / 图 / 描述 → [SearchResultKind.TOPIC]
     *
     * 顶层卡只按 `mblog` / `user` 分派：`title` 在顶层常是分组标题而非内容卡，避免误判成话题卡。
     * 词条组同理只用顶层 `title` 当组标题，不进内容卡分派。
     */
    private fun buildSearchItems(cards: List<WeiboCardDto>): List<SearchResultItem> {
        val items = mutableListOf<SearchResultItem>()

        fun addPost(status: WeiboStatusDto) {
            items += SearchResultItem(
                kind = SearchResultKind.POST,
                status = status,
                stableKey = "post_${items.size}_${status.listKey}"
            )
        }

        fun addCard(info: WeiboSearchCardInfo, itemid: String?) {
            val kind = info.classifySearchCard() ?: return
            items += SearchResultItem(
                kind = kind,
                card = info,
                stableKey = "card_${items.size}_${itemid.orEmpty()}"
            )
        }

        cards.forEach { card ->
            val topStatus = card.mblog
            if (topStatus != null) {
                addPost(topStatus)
            } else if (card.user != null) {
                addCard(card.toSearchCardInfo(), card.itemid)
            }

            // 卡片组先整体试判「词条组」：整组都是纯文本词条（相关搜索 / 热门话题）时并成一项，
            // 顶层卡的 `title` 就是组标题（服务端没给则只渲染词条）。
            val chipGroup = card.cardGroup.toSearchChipGroupOrNull(card.title)
            if (chipGroup != null) {
                items += SearchResultItem(
                    kind = SearchResultKind.CHIP_GROUP,
                    chipGroup = chipGroup,
                    stableKey = "chips_${items.size}_${card.itemid.orEmpty()}"
                )
            } else {
                card.cardGroup.forEach { groupItem ->
                    val groupStatus = groupItem.mblog
                    if (groupStatus != null) {
                        addPost(groupStatus)
                    } else {
                        addCard(groupItem.toSearchCardInfo(), groupItem.itemid)
                    }
                }
            }
        }
        return items
    }

    /** 首屏分栏（服务端 `channel_list` 里 id=1 那一栏，即「综合」）的 default containerid。 */
    private fun defaultSearchContainerId(keyword: String, searchType: Int): String =
        "100103type=$searchType&q=$keyword&t=1"

    /**
     * 话题微博（「532 模板」筛选选择）
     *
     * 机制说明：
     * - 话题微博页的两个分栏，标题为「热门 / 实时」，共用同一个 532 模板，仅靠末尾 `&sort=time` 区分：
     *     · 热门 = `100303type=532&q=%s&t=0`
     *     · 实时 = `100303type=532&q=%s&t=0&sort=time`
     * - 用 `String.format(模板, 关键词)` 把关键词填进 `%s`，并
     *   `fid = containerid`；当 pageType == 2（话题页）时追加
     *   `extparam = {"topicid":"{topicId}","pagetype":"index"}`。
     * - 请求统一走 `page_type=searchall`。
     *
     * 端点: https://m.weibo.cn/api/container/getIndex?containerid=100303type=532&q={keyword}&t=0[&sort=time]&page_type=searchall
     *
     * @param sortByTime `true` = 实时（追加 `&sort=time`）；`false` = 热门
     * @param topicId 话题 id，用于 `extparam.topicid`；为空时不下发 extparam
     */
    suspend fun searchTopicStatus(
        keyword: String,
        topicId: String = "",
        page: Int = 1,
        sortByTime: Boolean = false,
        count: Int = 20,
        cookie: String = ""
    ): ApiResult<List<WeiboStatusDto>> {
        val topicKeyword = if (keyword.startsWith("#") && keyword.endsWith("#")) keyword else "#$keyword#"
        return when (val pageResult = searchWeiboPage(keyword = topicKeyword, page = page, cookie = cookie)) {
            is ApiResult.Success -> {
                val posts = pageResult.data.items.mapNotNull { it.status }
                ApiResult.Success(posts)
            }
            is ApiResult.Error -> ApiResult.Error(code = pageResult.code, message = pageResult.message, throwable = pageResult.throwable)
            is ApiResult.Loading -> ApiResult.Loading
        }
    }

    /**
     * 获取单条微博详情。
     *
     * 端点: `https://weibo.com/ajax/statuses/show?id={id}`（网页详情页自己调的那个，
     * 响应顶层就是微博对象，见 [decodeWebStatusDetail]）。
     */
    suspend fun getPostDetail(id: String, cookie: String = ""): ApiResult<WeiboStatusDto> {
        return try {
            val effectiveCookie = getEffectiveCookie(cookie)
            val response = client.weiboGet(
                webEndpoint(
                    path = "/ajax/statuses/show",
                    auth = WeiboAuth.Session(effectiveCookie),
                    referer = "https://weibo.com/detail/$id"
                )
            ) {
                parameter("id", id)
                parameter("locale", "zh-CN")
                parameter("isGetLongText", "true")
            }
            if (response.status == HttpStatusCode.OK) {
                withContext(Dispatchers.IO) { decodeWebStatusDetail(response.bodyAsText()) }
            } else {
                ApiResult.Error(code = response.status.value, message = "HTTP ${response.status}")
            }
        } catch (e: Exception) {
            android.util.Log.e("WeiboApi", "getPostDetail failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "获取微博详情异常", throwable = e)
        }
    }

    /**
     * 获取微博评论。
     *
     * 端点: `https://weibo.com/ajax/statuses/buildComments`（网页详情页的评论列表），
     * `flow=1` 热门 / `flow=0` 时间序；翻页用服务端下发的 `max_id` 评论游标，
     * [page] 仅为兼容调用方保留，不参与请求。
     */
    suspend fun getComments(
        id: String,
        sortByHot: Boolean = true,
        maxId: Long? = null,
        page: Int = 1,
        cookie: String = ""
    ): ApiResult<CommentsDataDto> {
        return try {
            val effectiveCookie = getEffectiveCookie(cookie)
            val response = client.weiboGet(
                webEndpoint(
                    path = "/ajax/statuses/buildComments",
                    auth = WeiboAuth.Session(effectiveCookie),
                    referer = "https://weibo.com/detail/$id"
                )
            ) {
                parameter("is_reload", 1)
                parameter("id", id)
                parameter("is_show_bulletin", 2)
                parameter("is_mix", 0)
                parameter("fetch_level", 0)
                parameter("flow", if (sortByHot) 1 else 0)
                parameter("count", 20)
                maxId?.takeIf { it > 0 }?.let { parameter("max_id", it) }
            }
            if (response.status == HttpStatusCode.OK) {
                // 网页一级评论与楼中楼同构（顶层扁平 data 数组），复用同一个解析
                withContext(Dispatchers.IO) { decodeCommentReplies(response.bodyAsText()) }
            } else {
                ApiResult.Error(code = response.status.value, message = "HTTP ${response.status}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("WeiboApi", "getComments failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "获取评论异常", throwable = e)
        }
    }

    /**
     * 获取评论的楼中楼回复，按服务端下发的评论游标分页。
     *
     * 端点与一级评论相同（`/ajax/statuses/buildComments`），区别是 `id` 传**父评论 id**，
     * 且必须带 `fetch_level=1&is_mix=1`（实测缺这两个参数返回的仍是一级评论）。
     * [maxIdType] 是 m 站专有参数，网页端不需要，仅为兼容调用方保留。
     */
    suspend fun getCommentReplies(
        postId: String,
        commentId: Long,
        maxId: Long = 0,
        maxIdType: Int = 0,
        cookie: String = ""
    ): ApiResult<CommentsDataDto> {
        return try {
            val effectiveCookie = getEffectiveCookie(cookie)
            val response = client.weiboGet(
                webEndpoint(
                    path = "/ajax/statuses/buildComments",
                    auth = WeiboAuth.Session(effectiveCookie),
                    referer = "https://weibo.com/detail/$postId?cid=$commentId"
                )
            ) {
                parameter("is_reload", 1)
                parameter("id", commentId)
                parameter("is_show_bulletin", 2)
                parameter("is_mix", 1)
                parameter("fetch_level", 1)
                parameter("count", 20)
                parameter("max_id", maxId)
            }
            if (response.status == HttpStatusCode.OK) {
                withContext(Dispatchers.IO) { decodeCommentReplies(response.bodyAsText()) }
            } else {
                ApiResult.Error(code = response.status.value, message = "HTTP ${response.status}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("WeiboApi", "getCommentReplies failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "获取回复异常", throwable = e)
        }
    }

    /**
     * 获取微博转发列表。
     *
     * 端点: `https://weibo.com/ajax/statuses/repostTimeline?id={id}&page={page}`，
     * 响应为扁平 `data` 数组，见 [decodeWebRepostPage]。
     */
    suspend fun getReposts(
        id: String,
        page: Int = 1,
        cookie: String = ""
    ): ApiResult<RepostsDataDto> {
        return try {
            val effectiveCookie = getEffectiveCookie(cookie)
            val response = client.weiboGet(
                webEndpoint(
                    path = "/ajax/statuses/repostTimeline",
                    auth = WeiboAuth.Session(effectiveCookie),
                    referer = "https://weibo.com/detail/$id"
                )
            ) {
                parameter("id", id)
                parameter("page", page)
            }
            if (response.status == HttpStatusCode.OK) {
                withContext(Dispatchers.IO) { decodeWebRepostPage(response.bodyAsText()) }
            } else {
                ApiResult.Error(code = response.status.value, message = "HTTP ${response.status}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("WeiboApi", "getReposts failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "获取转发列表异常", throwable = e)
        }
    }

    /**
     * 获取微博赞列表
     * 端点: https://m.weibo.cn/api/attitudes/show?id={id}&page={page}
     */
    suspend fun getAttitudes(
        id: String,
        page: Int = 1,
        cookie: String = ""
    ): ApiResult<AttitudesDataDto> {
        return try {
            val effectiveCookie = getEffectiveCookie(cookie)
            val response = client.weiboGet(
                mobileEndpoint(
                    path = "/api/attitudes/show",
                    auth = WeiboAuth.Session(effectiveCookie),
                    referer = "https://m.weibo.cn/detail/$id"
                )
            ) {
                parameter("id", id)
                parameter("page", page)
                // m 站 PWA 专用头：缺了会被当普通网页请求，返回结构不同
                header("MWeibo-Pwa", "1")
            }
            if (response.status == HttpStatusCode.OK) {
                val resp = response.decodeBody<AttitudesResponse>()
                when {
                    // 没有点赞时服务端返回 ok=0 + "这里还没有内容"，按空列表处理
                    isWeiboEmptyListResponse(resp.ok, resp.msg, resp.data?.data?.size) ->
                        ApiResult.Success(AttitudesDataDto(data = emptyList()))
                    resp.data != null -> ApiResult.Success(resp.data)
                    else -> ApiResult.Error(message = "点赞列表加载失败")
                }
            } else {
                ApiResult.Error(code = response.status.value, message = "HTTP ${response.status}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("WeiboApi", "getAttitudes failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "获取点赞列表异常", throwable = e)
        }
    }

    /**
     * 对微博或评论点赞 / 取消点赞。端点（weibo.com 网页端表单协议）：
     * - 微博: POST https://weibo.com/ajax/statuses/setLike | cancelLike，参数 `id`
     * - 评论: POST https://weibo.com/ajax/statuses/updateLike | destroyLike，
     *   参数 `object_id` + `object_type=comment`
     *
     * m 站 `attitudes/create|destroy` 已被微博风控收紧（登录态连写直接回「操作频繁」），
     * 2026-10 起改走 weibo.com SPA 自己在用的通道；仍共用 [WeiboWriteSession] 的
     * 串行提交与频控冷却。
     */
    suspend fun setAttitude(
        id: String,
        isLike: Boolean,
        cookie: String = "",
        /** 评论点赞时传所属微博 id：非 null 即按评论端点（updateLike/destroyLike）提交。 */
        refererPostId: String? = null
    ): ApiResult<Boolean> {
        return try {
            val effectiveCookie = requireSessionCookie(cookie)
            if (effectiveCookie.isBlank()) {
                return ApiResult.Error(code = 401, message = "未登录或登录已失效，请先登录")
            }
            val form = if (refererPostId == null) {
                parameters { append("id", id) }
            } else {
                parameters {
                    append("object_id", id)
                    append("object_type", "comment")
                }
            }
            writeSession.execute(
                cookie = effectiveCookie,
                action = if (isLike) "点赞" else "取消点赞"
            ) { baseCredentials ->
                desktopFormWrite(
                    path = "/ajax/statuses/" +
                        when {
                            refererPostId == null && isLike -> "setLike"
                            refererPostId == null -> "cancelLike"
                            isLike -> "updateLike"
                            else -> "destroyLike"
                        },
                    form = form,
                    referer = "https://weibo.com/",
                    baseCredentials = baseCredentials,
                    failureMessage = if (isLike) "点赞失败" else "取消点赞失败",
                    success = isLike
                )
            }
        } catch (e: WriteGateException) {
            ApiResult.Error(code = e.code, message = e.message.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("WeiboApi", "setAttitude failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "点赞操作异常", throwable = e)
        }
    }

    /**
     * 回复（评论）微博。
     *
     * 端点: `POST https://weibo.com/ajax/comments/create`，带 `cid` 时为 `.../reply`。
     * 请求体是**表单**（JSON 会被拒），`Referer` 指详情页，凭据用网页域 XSRF。
     * 字段：`mid`/`id`（详情链接里的微博 id，两个都带以兼容不同分支）、`comment`、
     * 楼中楼用 `cid`，附图用 `pic_id`，其余开关固定为 0。
     *
     * 与点赞 / 关注共用同一个 [WeiboWriteSession]：网页 XSRF 凭据、串行提交与频控冷却一致。
     * 成功时尽量带回服务端新建的评论（`data` 为对象时可直接插入列表）；
     * 部分响应只给 `ok=1`，此时返回 `null`，由调用方静默重拉评论。
     */
    suspend fun createComment(
        id: String,
        text: String,
        cookie: String = "",
        commentId: Long? = null,
        pictureId: String? = null
    ): ApiResult<WeiboCommentDto?> {
        if (id.isBlank() || (commentId != null && commentId <= 0)) {
            return ApiResult.Error(message = "回复目标无效")
        }
        val content = text.trim()
        if (content.isEmpty() && pictureId.isNullOrBlank()) return ApiResult.Error(message = "回复内容不能为空")
        return try {
            val effectiveCookie = requireSessionCookie(cookie)
            if (effectiveCookie.isBlank()) {
                return ApiResult.Error(code = 401, message = "未登录或登录已失效，请先登录")
            }
            writeSession.execute(cookie = effectiveCookie, action = "回复") { credentials ->
                // 纯文本与图片评论统一走网页端：同一个端点、同一套 XSRF 凭据，不再分两条通道
                val response = client.weiboPostForm(
                    endpoint = webEndpoint(
                        path = "/ajax/comments/${if (commentId == null) "create" else "reply"}",
                        auth = WeiboAuth.Write(credentials),
                        referer = "https://weibo.com/detail/$id"
                    ),
                    form = parameters {
                        append("mid", id)
                        append("id", id)
                        commentId?.let { append("cid", it.toString()) }
                        append("comment", content.ifBlank { "图片评论" })
                        pictureId?.let { append("pic_id", it) }
                        append("is_repost", "0")
                        append("comment_ori", "0")
                        append("is_comment", "0")
                    }
                )
                readCreatedComment(response, failureMessage = "回复失败")
            }
        } catch (e: WriteGateException) {
            ApiResult.Error(code = e.code, message = e.message.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("WeiboApi", "createComment failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "回复异常", throwable = e)
        }
    }

    /**
     * 转发微博。
     *
     * 端点: `POST https://weibo.com/ajax/statuses/normal_repost`（2026-10 网页端抓包核实：
     * 路径是 `normal_repost`，不是 `repost`），**表单**，`Referer` 用网页首页，凭据用网页域 XSRF。
     *
     * 字段（括号内为网页端抓包核实过的值）：
     * `id`（被转发微博 id）、`comment`（转发语，空串＝直接转发，网页端确实这么发）、
     * `is_repost=0`、`comment_ori=0`、`share_id`（空串）；
     * `is_comment`（`1`＝转发的同时评论给原作者，即「同时评论此微博」）、
     * `visible`（分享范围，见 [StatusVisibility]）、
     * `pic_id`（带图时与 [publishStatus] 同为 JSON 数组字符串，并额外带 `need_transcode=1`；
     * 不带图时发空串）。
     *
     * `is_comment` / `visible` / `pic_id` 是 2026-10 之后按网页端表单补齐的，
     * 尚未逐项实机抓包核对，失败时把服务端原话留在 logcat 便于对照。
     *
     * 网页端还会带 `fp`（浏览器指纹），App 侧无法生成，评论等写接口不带也正常，先不伪造。
     * 成功响应是 `{"ok":1,"data":{"statuses":{...}}}`，可直接走 [readWriteResponse]。
     */
    suspend fun repost(
        id: String,
        text: String,
        pictures: List<StatusPicture> = emptyList(),
        alsoComment: Boolean = false,
        visibility: StatusVisibility = StatusVisibility.PUBLIC,
        cookie: String = ""
    ): ApiResult<Unit> {
        if (id.isBlank()) return ApiResult.Error(message = "转发目标无效")
        return try {
            val effectiveCookie = requireSessionCookie(cookie)
            if (effectiveCookie.isBlank()) {
                return ApiResult.Error(code = 401, message = "未登录或登录已失效，请先登录")
            }
            writeSession.execute(cookie = effectiveCookie, action = "转发") { credentials ->
                val response = client.weiboPostForm(
                    endpoint = webEndpoint(
                        path = "/ajax/statuses/normal_repost",
                        auth = WeiboAuth.Write(credentials),
                        referer = "https://weibo.com/"
                    ),
                    form = parameters {
                        append("id", id)
                        append("comment", text.trim())
                        append("pic_id", if (pictures.isEmpty()) "" else pictureJsonOf(pictures))
                        if (pictures.isNotEmpty()) append("need_transcode", "1")
                        append("is_repost", "0")
                        append("comment_ori", "0")
                        append("is_comment", if (alsoComment) "1" else "0")
                        append("visible", visibility.value)
                        append("share_id", "")
                    }
                )
                readWriteResponse(response, failureMessage = "转发失败", success = Unit).also { result ->
                    // 端点字段尚未实机核实：失败时把服务端原话留在 logcat，方便对照网页端请求排查
                    if (result is ApiResult.Error) {
                        android.util.Log.w(
                            "WeiboApi",
                            "repost id=$id failed: code=${result.code} msg=${result.message}"
                        )
                    }
                }
            }
        } catch (e: WriteGateException) {
            ApiResult.Error(code = e.code, message = e.message.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("WeiboApi", "repost failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "转发异常", throwable = e)
        }
    }

    /**
     * 发微博。
     *
     * 端点: `POST https://weibo.com/ajax/statuses/update`（2026-10 网页端抓包核实），
     * **表单**，`Referer` 用网页首页，凭据用网页域 XSRF。
     *
     * 字段（同一次抓包核实）：
     * - `content`、`visible=0`、`share_id`/`vote`/`media` 空串；
     * - 带图时 `pic_id` 是 **JSON 数组字符串**，形如
     *   `[{"type":"image/jpeg","pid":"008ve..."},{"type":"image/webp","pid":"..."}]`，
     *   同时带 `need_transcode=1`；不带图时网页端压根不发这两个字段。
     *
     * 成功响应**没有 `ok` 字段**，只有 `{"data":{...新微博...}}`，
     * 因此不能用 [readWriteResponse]（它按 `ok==1` 判定），见 [readPublishResponse]。
     * 网页端另带 `fp`（浏览器指纹），App 无法生成，先不伪造。
     */
    suspend fun publishStatus(
        text: String,
        pictures: List<StatusPicture> = emptyList(),
        cookie: String = ""
    ): ApiResult<Unit> {
        val content = text.trim()
        if (content.isEmpty() && pictures.isEmpty()) {
            return ApiResult.Error(message = "微博内容不能为空")
        }
        return try {
            val effectiveCookie = requireSessionCookie(cookie)
            if (effectiveCookie.isBlank()) {
                return ApiResult.Error(code = 401, message = "未登录或登录已失效，请先登录")
            }
            val pictureJson = pictureJsonOf(pictures)
            writeSession.execute(cookie = effectiveCookie, action = "发布") { credentials ->
                val response = client.weiboPostForm(
                    endpoint = webEndpoint(
                        path = "/ajax/statuses/update",
                        auth = WeiboAuth.Write(credentials),
                        referer = "https://weibo.com/"
                    ),
                    form = parameters {
                        append("content", content)
                        append("visible", "0")
                        append("share_id", "")
                        append("vote", "")
                        append("media", "")
                        if (pictures.isNotEmpty()) {
                            append("pic_id", pictureJson)
                            append("need_transcode", "1")
                        }
                    }
                )
                readPublishResponse(response).also { result ->
                    // 失败原话进 logcat，便于和网页端抓到的请求对齐
                    if (result is ApiResult.Error) {
                        android.util.Log.w(
                            "WeiboApi",
                            "publishStatus pictures=${pictures.size} failed: " +
                                "code=${result.code} msg=${result.message}"
                        )
                    }
                }
            }
        } catch (e: WriteGateException) {
            ApiResult.Error(code = e.code, message = e.message.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("WeiboApi", "publishStatus failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "发布异常", throwable = e)
        }
    }

    /**
     * 网页写接口的 `pic_id` 编码：JSON 数组字符串，逐项带 MIME（见 [StatusPicture]）。
     *
     * 发微博与转发带图共用同一份编码；两者的 `pic_id` 字段在网页端是同一个格式。
     */
    private fun pictureJsonOf(pictures: List<StatusPicture>): String = buildJsonArray {
        pictures.forEach { picture ->
            add(
                buildJsonObject {
                    put("type", picture.mimeType)
                    put("pid", picture.pid)
                }
            )
        }
    }.toString()

    /**
     * 网页域（weibo.com/ajax）写操作的凭据：点赞、关注/取关、删除等网页端点用网页域
     * 自己的 XSRF Cookie，不混用移动端 st。按来源 Cookie 缓存复用（见
     * [cachedDesktopCredentials]）。
     */
    private suspend fun desktopWriteCredentials(base: WeiboWriteCredentials): WeiboWriteCredentials {
        cachedDesktopCredentials?.let { cached ->
            if (cached.sourceCookie == base.sourceCookie) return cached
        }
        val cookies = linkedMapOf<String, String>()
        base.cookieHeader.split(';').forEach { part ->
            val name = part.substringBefore('=').trim()
            if (name.isNotEmpty() && '=' in part) cookies[name] = part.substringAfter('=').trim()
        }
        if (cookies["XSRF-TOKEN"].isNullOrBlank()) {
            // 网页域首页只用来换 XSRF Cookie，不带业务参数
            val response = client.weiboGet(webEndpoint("/", WeiboAuth.Session(base.cookieHeader)))
            if (response.status == HttpStatusCode.TooManyRequests) {
                writeSession.reportRateLimited()
                throw WriteGateException(429, WeiboWriteErrors.RATE_LIMITED_MESSAGE)
            }
            response.headers.getAll("Set-Cookie").orEmpty().forEach { header ->
                val pair = header.substringBefore(';')
                if ('=' in pair) cookies[pair.substringBefore('=').trim()] = pair.substringAfter('=').trim()
            }
        }
        val csrf = cookies["XSRF-TOKEN"]?.takeIf { it.isNotBlank() }
            ?: throw WriteGateException(401, "无法获取微博网页发布凭据，请重新登录后重试")
        return base.copy(cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
            token = csrf.decodeURLQueryComponent())
            .also { cachedDesktopCredentials = it }
    }

    /**
     * 把网页域响应的 `Set-Cookie` 合并回缓存凭据。微博几乎每个响应都可能轮换
     * `XSRF-TOKEN`，要像浏览器 cookie jar 一样跟着更新，缓存令牌才不会悄悄过期。
     */
    private fun syncDesktopCredentials(credentials: WeiboWriteCredentials, response: HttpResponse) {
        if (cachedDesktopCredentials !== credentials) return
        val setCookies = response.headers.getAll("Set-Cookie").orEmpty()
        if (setCookies.isEmpty()) return
        val cookies = parseCookieHeader(credentials.cookieHeader)
        setCookies.forEach { header ->
            val pair = header.substringBefore(';')
            if ('=' in pair) cookies[pair.substringBefore('=').trim()] = pair.substringAfter('=').trim()
        }
        val rotated = cookies["XSRF-TOKEN"]?.takeIf { it.isNotBlank() }
        cachedDesktopCredentials = credentials.copy(
            cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
            token = rotated?.decodeURLQueryComponent() ?: credentials.token
        )
    }

    /**
     * 网页域表单写请求（点赞 / 关注 / 取关）。契约（2026-10 从 weibo.com SPA bundle
     * 核实）：`application/x-www-form-urlencoded`，JSON 体会被服务端拒绝。
     */
    private suspend fun <T : Any> desktopFormWrite(
        path: String,
        form: Parameters,
        referer: String,
        baseCredentials: WeiboWriteCredentials,
        failureMessage: String,
        success: T
    ): ApiResult<T> {
        val credentials = desktopWriteCredentials(baseCredentials)
        val response = client.weiboPostForm(
            endpoint = webEndpoint(path, WeiboAuth.Write(credentials), referer = referer),
            form = form
        )
        syncDesktopCredentials(credentials, response)
        return readWriteResponse(response, failureMessage = failureMessage, success = success)
    }

    /**
     * 网页域 JSON 写操作（收藏 / 取消收藏）：`application/json` 体 + 网页域 XSRF 令牌。
     *
     * 与 [desktopFormWrite] 的区别只在请求体格式：网页端收藏走 JSON（表单会被拒），
     * 点赞 / 关注走表单。频控、凭据轮换与失败处理共用同一套逻辑。
     */
    private suspend fun desktopJsonWrite(
        cookie: String,
        action: String,
        path: String,
        body: JsonObject,
        failureMessage: String
    ): ApiResult<Unit> {
        return try {
            writeSession.execute(cookie = cookie, action = action) { baseCredentials ->
                val credentials = desktopWriteCredentials(baseCredentials)
                val response = client.weiboPostJson(
                    endpoint = webEndpoint(path, WeiboAuth.Write(credentials)),
                    json = body.toString()
                )
                syncDesktopCredentials(credentials, response)
                readWriteResponse(response, failureMessage = failureMessage, success = Unit)
            }
        } catch (e: WriteGateException) {
            ApiResult.Error(code = e.code, message = e.message.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("WeiboApi", "$action failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "$failureMessage，请稍后重试", throwable = e)
        }
    }

    /**
     * 解析写操作（点赞 / 关注）的响应。
     *
     * - HTTP 429 与「操作频繁」类文案统一转成 429 并进入本地冷却：这类失败如果按普通错误
     *   透传，用户会不断重试，服务端的冷却期只会被越拖越长；
     * - 其它 `ok != 1` 多为 `st` 失效，丢弃缓存凭据让下次重新获取，但**不自动重发**，
     *   避免把一次写入变成两次。
     */
    private suspend fun <T : Any> readWriteResponse(
        response: HttpResponse,
        failureMessage: String,
        success: T
    ): ApiResult<T> {
        writeHttpError(response)?.let { return it }
        val respJson = response.jsonBody().jsonObject
        if (writeOkFlag(respJson) == 1) return ApiResult.Success(success)
        return writeJsonError(respJson, failureMessage)
    }

    /**
     * 发表接口的响应：成功时**没有 `ok` 字段**，只有 `data`（新微博对象）。
     *
     * 按 `ok==1` 判定会把每一次成功都当失败，这里改成「有 data 或有 ok=1」即为成功；
     * 失败仍然交给 [writeJsonError]（频控收敛、凭据失效处理与其它写接口一致）。
     */
    private suspend fun readPublishResponse(response: HttpResponse): ApiResult<Unit> {
        writeHttpError(response)?.let { return it }
        val respJson = response.jsonBody().jsonObject
        if (respJson["data"] is JsonObject || writeOkFlag(respJson) == 1) {
            return ApiResult.Success(Unit)
        }
        return writeJsonError(respJson, "发布失败")
    }

    /** 与 [readWriteResponse] 同一套失败处理，成功时带回 `data` 里新建的评论（可能缺省）。 */
    private suspend fun readCreatedComment(
        response: HttpResponse,
        failureMessage: String
    ): ApiResult<WeiboCommentDto?> {
        writeHttpError(response)?.let { return it }
        val respJson = response.jsonBody().jsonObject
        if (writeOkFlag(respJson) != 1) return writeJsonError(respJson, failureMessage)
        val created = (respJson["data"] as? JsonObject)?.let { data ->
            // 新建评论的字段与服务端列表不完全一致，解析失败只当作「没带回评论」，
            // 不能把一次已经成功的写入报成失败；没有 id 的对象直接丢弃，
            // 否则会在列表里插进一条点不开、也不属于任何人的空评论
            runCatching {
                KtorClientFactory.jsonConfig
                    .decodeFromJsonElement<WeiboCommentDto>(data)
            }.getOrNull()?.takeIf { it.id != null }
        }
        return ApiResult.Success(created)
    }

    /** 写响应的 HTTP 层失败：429 进入本地冷却，非 200 原样透传。 */
    private fun writeHttpError(response: HttpResponse): ApiResult.Error? {
        if (response.status == HttpStatusCode.TooManyRequests) {
            writeSession.reportRateLimited()
            return ApiResult.Error(
                code = WeiboWriteErrors.CODE_RATE_LIMITED,
                message = WeiboWriteErrors.RATE_LIMITED_MESSAGE
            )
        }
        if (response.status != HttpStatusCode.OK) {
            return ApiResult.Error(code = response.status.value, message = "HTTP ${response.status}")
        }
        return null
    }

    /** 写响应的业务失败（`ok != 1`）：频控进入冷却，其它视为 `st` 失效并丢弃缓存凭据。 */
    private fun writeJsonError(respJson: JsonObject, failureMessage: String): ApiResult.Error {
        val msg = respJson["msg"]?.jsonPrimitive?.contentOrNull
            ?: respJson["message"]?.jsonPrimitive?.contentOrNull
            ?: failureMessage
        if (WeiboWriteErrors.isRateLimited(msg)) {
            writeSession.reportRateLimited()
            return ApiResult.Error(
                code = WeiboWriteErrors.CODE_RATE_LIMITED,
                message = WeiboWriteErrors.RATE_LIMITED_MESSAGE
            )
        }
        writeSession.invalidate()
        cachedDesktopCredentials = null
        return ApiResult.Error(message = msg)
    }

    /**
     * 关注分组（建 / 改名 / 删）的业务失败：`ok != 1` 时频控同样收敛成 429 并进入本地冷却。
     *
     * 与 [writeJsonError] 的差别：分组接口用会话 Cookie 鉴权（没有 XSRF 令牌可作废），
     * 失败不牵动 `st` 与桌面凭据缓存，免得下一次写操作白打一次取令牌的 GET。
     */
    private fun groupWriteError(jsonRoot: JsonObject, failureMessage: String): ApiResult.Error {
        val msg = jsonRoot["msg"]?.jsonPrimitive?.contentOrNull
            ?: jsonRoot["message"]?.jsonPrimitive?.contentOrNull
            ?: jsonRoot["error"]?.jsonPrimitive?.contentOrNull
        if (WeiboWriteErrors.isRateLimited(msg)) {
            writeSession.reportRateLimited()
            return ApiResult.Error(
                code = WeiboWriteErrors.CODE_RATE_LIMITED,
                message = WeiboWriteErrors.RATE_LIMITED_MESSAGE
            )
        }
        return ApiResult.Error(message = msg ?: failureMessage)
    }

    /** `ok` 在不同端是数字或字符串，统一取整数。 */
    private fun writeOkFlag(respJson: JsonObject): Int =
        respJson["ok"]?.jsonPrimitive?.intOrNull
            ?: (if (respJson["ok"]?.jsonPrimitive?.content == "1") 1 else 0)

    /** 解析 `Cookie` 头为有序键值表，供写操作合并 `Set-Cookie` 使用。 */
    private fun parseCookieHeader(header: String): LinkedHashMap<String, String> {
        val cookies = linkedMapOf<String, String>()
        header.split(';').forEach { part ->
            val name = part.substringBefore('=').trim()
            if (name.isNotEmpty() && '=' in part) cookies[name] = part.substringAfter('=').trim()
        }
        return cookies
    }

    /**
     * 写操作凭据（网页域 XSRF）。
     *
     * 所有写路径都已收敛到 `weibo.com`，不再需要 `/api/config` 下发的 `st`：这里只把调用方
     * 的 Cookie 包成凭据，真正的 XSRF 令牌由 [desktopWriteCredentials] 取（首次会打一次
     * `weibo.com/`，之后按来源 Cookie 缓存复用）。
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun fetchDesktopWriteCredentials(
        cookie: String,
        action: String
    ): WeiboWriteCredentials = desktopWriteCredentials(
        WeiboWriteCredentials(sourceCookie = cookie, cookieHeader = cookie, token = "")
    )


    /** 关注用户。端点: POST https://weibo.com/ajax/friendships/create，表单 `friend_uid` */
    suspend fun followUser(uid: String, cookie: String = ""): ApiResult<Unit> =
        updateFollowState(uid, follow = true, cookie = cookie)

    /**
     * 取消关注。端点: POST https://weibo.com/ajax/friendships/destory，表单 `uid`
     *
     * `destory` 是微博上游自己的拼写（2026-10 从 weibo.com SPA bundle 核实，正确拼写的
     * `destroy` 路由不存在）；m 站 `POST /api/friendships/destroy` 也已下线
     * （实测返回 `errno 404` +「出错了: 链接…无效」）。
     */
    suspend fun unfollowUser(uid: String, cookie: String = ""): ApiResult<Unit> =
        updateFollowState(uid, follow = false, cookie = cookie)

    /**
     * 关注 / 取关共用实现：都走网页域表单协议（[desktopFormWrite]），与点赞、删微博
     * 同一套网页 XSRF 凭据。仍共用 [WeiboWriteSession]：微博的写限流按账号计，
     * 凭据缓存、串行提交和频控冷却对全部写操作一致。
     */
    private suspend fun updateFollowState(
        uid: String,
        follow: Boolean,
        cookie: String
    ): ApiResult<Unit> {
        return try {
            val targetUid = uid.trim()
            if (targetUid.isBlank() || targetUid == "guest") {
                return ApiResult.Error(code = 401, message = "请先登录后再操作")
            }
            val effectiveCookie = requireSessionCookie(cookie)
            if (effectiveCookie.isBlank()) {
                return ApiResult.Error(code = 401, message = "未登录或登录已失效，请先登录")
            }
            writeSession.execute(
                cookie = effectiveCookie,
                action = if (follow) "关注" else "取消关注"
            ) { baseCredentials ->
                desktopFormWrite(
                    path = if (follow) "/ajax/friendships/create"
                    else "/ajax/friendships/destory",
                    form = parameters {
                        append(if (follow) "friend_uid" else "uid", targetUid)
                    },
                    referer = "https://weibo.com/u/$targetUid",
                    baseCredentials = baseCredentials,
                    failureMessage = if (follow) "关注失败" else "取消关注失败",
                    success = Unit
                )
            }
        } catch (e: WriteGateException) {
            ApiResult.Error(code = e.code, message = e.message.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("WeiboApi", "updateFollowState failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "关注操作异常", throwable = e)
        }
    }

    /**
     * 删除自己发表的评论（楼中楼回复同样是评论，按 cid 删除）。
     *
     * 端点: POST https://weibo.com/ajax/statuses/destroyComment，表单 `cid`。
     *
     * 两个必需条件（缺一个就 400，实测）：请求体必须是**表单**而不是 JSON；
     * `Referer` 必须是该微博的**详情页**，不能是首页。凭据用网页域 XSRF。
     * 服务端对评论删除另有更紧的配额，触发后同样走 [WeiboWriteSession] 的本地冷却。
     */
    suspend fun destroyComment(postId: String, cid: Long, cookie: String = ""): ApiResult<Unit> {
        if (cid <= 0 || postId.isBlank()) return ApiResult.Error(message = "删除目标无效")
        return destroyWrite(postId, cid.toString(), cookie)
    }

    /**
     * 删除自己发表的微博（自己发的转发同理，删除即撤回该转发）。
     *
     * 端点: POST https://weibo.com/ajax/statuses/destroy，JSON 体 `{"id": ...}`。
     * m.weibo.cn 移动端没有可查证的删微博端点，这里直接走网页渠道，
     * 凭据用网页域自己的 XSRF 令牌（[desktopWriteCredentials]，与附图回复同一套）。
     */
    suspend fun destroyPost(id: String, cookie: String = ""): ApiResult<Unit> {
        if (id.isBlank()) return ApiResult.Error(message = "删除目标无效")
        return destroyWrite(id, cid = null, cookie = cookie)
    }

    private suspend fun destroyWrite(id: String, cid: String?, cookie: String): ApiResult<Unit> {
        return try {
            val effectiveCookie = requireSessionCookie(cookie)
            if (effectiveCookie.isBlank()) {
                return ApiResult.Error(code = 401, message = "未登录或登录已失效，请先登录")
            }
            writeSession.execute(
                cookie = effectiveCookie,
                action = if (cid == null) "删除微博" else "删除评论"
            ) { credentials ->
                if (cid == null) {
                    destroyViaDesktop(id, base = credentials)
                } else {
                    // 网页删评论：只接受**表单** `cid`（JSON 体会被判 400），
                    // 且 Referer 必须是详情页，否则同样 400
                    val response = client.weiboPostForm(
                        endpoint = webEndpoint(
                            path = "/ajax/statuses/destroyComment",
                            auth = WeiboAuth.Write(credentials),
                            referer = "https://weibo.com/detail/$id"
                        ),
                        form = parameters { append("cid", cid) }
                    )
                    readWriteResponse(response, failureMessage = "删除失败", success = Unit)
                }
            }
        } catch (e: WriteGateException) {
            ApiResult.Error(code = e.code, message = e.message.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("WeiboApi", "destroyWrite failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "删除操作异常", throwable = e)
        }
    }

    /**
     * 发送文本私信。
     *
     * 端点（2026-10 从 api.weibo.com/chat 私信 SPA（pcweibochat）bundle 核实）：
     * `POST https://api.weibo.com/webim/2/direct_messages/new.json`，表单编码，纯 Cookie 鉴权
     * （该 SPA 自身 `withCredentials:true` 且 `xsrfCookieName:null`，不依赖 st / XSRF）。
     * 参数：`uid`、`text`、`media_type=0`、`extensions`（JSON 字符串：webchat 标识 + 客户端 id）、
     * `return_detail=1`（响应带回消息明细，用于替换本地乐观插入的临时 id）。
     *
     * 与其他写操作共用 [WeiboWriteSession] 的串行提交与频控冷却；webim 不消费 `st`，
     * 闸门凭据仅用于统一节流。参数契约未经登录态实测，真机验证出入时按 DEBUG 日志修正。
     */
    suspend fun sendChatMessage(peerUid: String, text: String, cookie: String = ""): ApiResult<ChatSendResult> =
        sendChat(peerUid, image = null, text = text, cookie = cookie)

    /**
     * 发送图片私信：上传取 fid 后按 `media_type=1` 发送，正文沿用网页端固定文案「分享图片」。
     *
     * 上传两步：`fileplatform/init.json`（表单 extprops/length/name/type 换 fileToken）→
     * `webim/uploadx.json`（multipart 分块，最后一块回 `fid`）。
     * 协议细节见 [uploadChatImage]；图片展示地址见 `ChatMessage.imageUrl`。
     */
    suspend fun sendChatImage(peerUid: String, bytes: ByteArray, cookie: String = ""): ApiResult<ChatSendResult> =
        sendChat(peerUid, image = bytes, text = "", cookie = cookie)

    private suspend fun sendChat(
        peerUid: String,
        image: ByteArray?,
        text: String,
        cookie: String
    ): ApiResult<ChatSendResult> {
        return try {
            val targetUid = peerUid.trim()
            val content = text.trim()
            if (targetUid.isBlank() || targetUid == "guest") {
                return ApiResult.Error(code = 401, message = "请先登录后再操作")
            }
            if (content.isEmpty() && image == null) return ApiResult.Error(message = "私信内容不能为空")
            val effectiveCookie = requireSessionCookie(cookie)
            if (effectiveCookie.isBlank()) {
                return ApiResult.Error(code = 401, message = "未登录或登录已失效，请先登录")
            }
            writeSession.execute(cookie = effectiveCookie, action = "发送私信") { credentials ->
                if (image != null) {
                    // 已经在这个写闸门里，上传不能再取写锁（互斥锁不可重入）
                    when (val upload = chatImageUploader.upload(credentials, targetUid, image)) {
                        // 上传失败带着服务端原因：参数/风控/分块问题各不相同，不能一律「请稍后重试」
                        is ChatImageUpload.Failed -> ApiResult.Error(message = upload.reason)
                        is ChatImageUpload.Uploaded ->
                            submitWebimMessage(targetUid, fid = upload.fid, text = "分享图片", cookie = effectiveCookie)
                    }
                } else {
                    submitWebimMessage(targetUid, fid = null, text = content, cookie = effectiveCookie)
                }
            }
        } catch (e: WriteGateException) {
            ApiResult.Error(code = e.code, message = e.message.orEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("WeiboApi", "sendChat failed", e)
            ApiResult.Error(message = sendExceptionMessage(e), throwable = e)
        }
    }

    /**
     * 发送抛异常时的可读文案：网络层异常的 `localizedMessage` 是英文类名/地址，
     * 直接抛给用户等于没给信息；这里按异常类型给一句能指导操作的话，细节仍留在 throwable 里。
     */
    private fun sendExceptionMessage(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "网络不可用，请检查网络后重试"
        is java.net.SocketTimeoutException -> "网络超时，请检查网络后重试"
        is javax.net.ssl.SSLException -> "安全连接失败，请检查网络或稍后重试"
        is java.io.IOException -> "网络异常（${e.javaClass.simpleName}），请稍后重试"
        else -> e.localizedMessage?.takeIf { it.isNotBlank() } ?: "私信发送异常"
    }

    /** `direct_messages/new.json` 提交与响应解析。 */
    private suspend fun submitWebimMessage(
        targetUid: String,
        fid: String?,
        text: String,
        cookie: String
    ): ApiResult<ChatSendResult> {
        val extensions = JsonObject(
            mapOf("webchat" to JsonPrimitive(1), "clientid" to JsonPrimitive(makeChatClientId()))
        ).toString()
        val response = client.weiboPostForm(
            endpoint = openApiEndpoint(
                path = "/webim/2/direct_messages/new.json",
                auth = WeiboAuth.Session(cookie),
                referer = "https://api.weibo.com/chat/"
            ),
            form = parameters {
                append("uid", targetUid)
                append("text", text)
                if (fid != null) append("fids", fid)
                append("media_type", if (fid == null) "0" else "1")
                append("extensions", extensions)
                append("return_detail", "1")
            }
        ) {
            // webim 的 SPA 与网页主站不同源，Origin 要跟 api.weibo.com
            header("Origin", "https://api.weibo.com")
        }
        // 带 fids 时要求服务端确实把图片挂上了：只回一条纯文本消息说明附件被丢掉，
        // 这时必须报失败并回滚，不能让「分享图片」变成一条没有图的私信
        return readWebimSendResponse(response, expectsImage = fid != null)
    }

    /**
     * webim 写响应：`errno==0` / `ok==1` / `return_detail=1` 时直接把新建消息当响应体，三种都算成功。
     *
     * 关键点是「只有出现明确的失败信号才算失败」。不能像其他写接口那样复用 [writeOkFlag]：
     * 它在 `ok` 缺省时返回 0，而 `return_detail=1` 的成功响应**没有** `ok` 字段，
     * 于是「已经发出去」的私信会被报成失败 —— 本地回滚气泡并提示重试，随后轮询又把服务端那条拉回来。
     * 失败形态为 `errno!=0` / `ok!=1` + 错误文案；频控文案进入与其他写操作一致的本地冷却。
     */
    private suspend fun readWebimSendResponse(
        response: HttpResponse,
        expectsImage: Boolean = false
    ): ApiResult<ChatSendResult> {
        writeHttpError(response)?.let { return it }
        val respJson = response.jsonBody().jsonObject
        val errno = respJson["errno"]?.jsonPrimitive?.intOrNull
        val ok = respJson["ok"]?.jsonPrimitive?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }
        val errorText = respJson.webimString("errmsg", "error", "error_description", "msg", "message")

        // 明细可能在 data 里、data.status 里，也可能整个响应体就是这条新消息
        val detail = (respJson["data"] as? JsonObject)
            ?.let { (it["status"] as? JsonObject) ?: it }
            ?: respJson
        val id = detail.webimString("id", "idstr", "mid", "dmid")

        // 状态字段优先；两者都缺省时看响应体本身：带回了新消息 id 就是 `return_detail` 形态的成功，
        // 只有错误文案、没有任何消息身份才是失败
        val explicitFailure = (errno != null && errno != 0) || (ok != null && ok != 1)
        val ambiguousFailure = errno == null && ok == null && id == null && errorText != null

        if (!explicitFailure && !ambiguousFailure) {
            if (expectsImage && !detail.hasChatImageAttachment()) {
                // 服务端回了成功但没有附件：消息其实已经发出去了，只是退化成一条纯文本，
                // 这种「静默丢图」必须报出来，否则用户以为图发出去了
                runCatching {
                    android.util.Log.w("WeiboApi", "send lost attachment: ${detail.toString().take(400)}")
                }
                return ApiResult.Error(message = "图片发送失败：服务器未接住附件，请稍后重试")
            }
            return ApiResult.Success(
                ChatSendResult(
                    id = id,
                    timestampMillis = parseChatTimestampMillis(
                        detail.webimString("created_at", "time", "timestamp")
                    )
                )
            )
        }

        val msg = errorText ?: "发送失败，请稍后重试"
        if (WeiboWriteErrors.isRateLimited(msg)) {
            writeSession.reportRateLimited()
            cachedDesktopCredentials = null
            return ApiResult.Error(
                code = WeiboWriteErrors.CODE_RATE_LIMITED,
                message = WeiboWriteErrors.RATE_LIMITED_MESSAGE
            )
        }
        return ApiResult.Error(message = msg)
    }

    /** extensions 里的客户端标识：网页端用浏览器指纹，这里每次发送生成一次性随机 id 即可。 */
    private fun makeChatClientId(): String =
        java.util.UUID.randomUUID().toString().replace("-", "").take(16)

    private fun JsonObject.webimString(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    /** 图片私信成功时应有的痕迹：`media_type=1` 或带回了 `attachment`。 */
    private fun JsonObject.hasChatImageAttachment(): Boolean =
        this["attachment"] is JsonObject || webimString("media_type")?.toIntOrNull() == 1

    /** 网页渠道删除微博：JSON 体 + 网页域 XSRF 令牌；凭据取不到时抛 [WriteGateException]。 */
    private suspend fun destroyViaDesktop(
        id: String,
        base: WeiboWriteCredentials
    ): ApiResult<Unit> {
        val credentials = desktopWriteCredentials(base)
        val response = client.weiboPostJson(
            endpoint = webEndpoint("/ajax/statuses/destroy", WeiboAuth.Write(credentials)),
            json = JsonObject(mapOf("id" to JsonPrimitive(id))).toString()
        )
        return readWriteResponse(response, failureMessage = "删除失败", success = Unit)
    }

    private suspend fun resolveMentionUserId(name: String, cookie: String): ApiResult<String> =
        when (val result = searchWeiboPage(name, searchType = 3, cookie = cookie)) {
            is ApiResult.Success -> {
                val user = result.data.items.asSequence().mapNotNull { it.card?.user }
                    .firstOrNull { it.screenName == name }
                val id = user?.id?.toString() ?: user?.idStr
                if (!id.isNullOrBlank() && id.all { it.isDigit() }) ApiResult.Success(id)
                else ApiResult.Error(message = "未找到博主 @$name，请检查昵称是否已更改")
            }
            is ApiResult.Error -> result
            ApiResult.Loading -> ApiResult.Loading
        }

    /**
     * 获取博主资料卡片
     * 端点: https://weibo.com/ajax/profile/info?uid={uid}（游客态也能读，字段比 m 站 userInfo 更全）
     *
     * 该接口不可用时只回基础字段（id / 昵称），不再回落到 m 站 `container/getIndex`——
     * 那条通道的 `userInfo` 字段更少，且 m 站对未登录请求返回 432。
     */
    suspend fun getUserProfile(uid: String, cookie: String = ""): ApiResult<WeiboUserDto> {
        return try {
            val cleanUid = uid.removePrefix("name:").removePrefix("@").trim()
            if (cleanUid.isBlank()) return ApiResult.Error(message = "博主名称不能为空")
            val isNumeric = !uid.startsWith("name:") && cleanUid.all { it.isDigit() }
            val effectiveCookie = getEffectiveCookie(cookie)
            val numericUid = if (isNumeric) cleanUid else when (val resolved = resolveMentionUserId(cleanUid, effectiveCookie)) {
                is ApiResult.Success -> resolved.data
                is ApiResult.Error -> return resolved
                ApiResult.Loading -> return ApiResult.Error(message = "博主资料暂不可用，请重试")
            }

            val targetUid = numericUid

            // 网页端资料接口：游客态也能读，字段比移动端 userInfo 更全
            if (targetUid.all { it.isDigit() }) {
                try {
                    val pcResponse = client.weiboGet(
                        webEndpoint(
                            path = "/ajax/profile/info",
                            auth = WeiboAuth.Session(effectiveCookie),
                            referer = "https://weibo.com/u/$targetUid"
                        )
                    ) {
                        parameter("uid", targetUid)
                        header("client-version", "v2.44.75")
                        header("server-version", "v2024.05.24.1")
                    }
                    if (pcResponse.status == HttpStatusCode.OK) {
                        val rootJson = pcResponse.jsonBody().jsonObject
                        val ok = rootJson["ok"]?.jsonPrimitive?.content?.toIntOrNull()
                        if (ok == 1) {
                            val userObj = rootJson["data"]?.jsonObject?.get("user")
                            if (userObj != null) {
                                val userDto = KtorClientFactory.jsonConfig.decodeFromJsonElement<WeiboUserDto>(userObj)
                                return ApiResult.Success(userDto)
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    android.util.Log.w("WeiboApi", "weibo.com PC getUserProfile failed for $targetUid", e)
                }
            }

            // 兜底：只回基础博主信息（没有头像 / 简介）
            ApiResult.Success(
                WeiboUserDto(
                    id = targetUid.toLongOrNull(),
                    idStr = targetUid,
                    screenName = if (targetUid.all { it.isDigit() }) "博主 $targetUid" else targetUid
                )
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            android.util.Log.e("WeiboApi", "getUserProfile failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "加载博主资料异常", throwable = e)
        }
    }

    /**
     * 获取博主详细资料（PC 端个人主页「资料」模块）
     *
     * 端点:
     * - https://weibo.com/ajax/profile/detail?uid={uid}（字段最全）
     * - 兜底: https://weibo.com/ajax/profile/info?uid={uid} 的 data.user
     *
     * `profile/info` 的 `data.user` 只有昵称/头像/粉丝数等基础字段；
     * 生日、星座、IP 属地、注册时间、教育/职业、阳光信用、动态标签只在这里下发。
     * 该接口依赖登录态，失败时由调用方静默降级，不影响主页主体内容。
     */
    suspend fun getUserProfileDetail(uid: String, cookie: String = ""): ApiResult<UserProfileDetail> {
        return try {
            if (!uid.all { it.isDigit() }) {
                return ApiResult.Error(message = "仅支持数字 UID 获取详细资料")
            }

            suspend fun fetchData(path: String): JsonObject? {
                val response = weiboGetWithVisitorRetry(
                    buildEndpoint = { auth -> webEndpoint(path, auth, referer = "https://weibo.com/u/$uid") },
                    cookie = cookie
                ) {
                    parameter("uid", uid)
                    header("client-version", "v2.44.75")
                    header("server-version", "v2024.05.24.1")
                }
                if (response.status != HttpStatusCode.OK) return null
                val root = response.jsonBody().jsonObject
                val ok = (root["ok"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
                if (ok != 1) return null
                return root["data"] as? JsonObject
            }

            // 1. 详细资料（生日、教育、职业、阳光信用、动态标签等）
            val detailData = fetchData("/ajax/profile/detail")
            if (detailData != null) {
                val detail = parseUserProfileDetail(detailData)
                if (detail.hasProfileInfo) return ApiResult.Success(detail)
            }

            // 2. 兜底 profile/info 的 user 节点，至少补齐所在地/性别
            val infoUser = fetchData("/ajax/profile/info")?.get("user") as? JsonObject
            if (infoUser != null) {
                val detail = parseUserProfileDetail(infoUser)
                if (detail.hasProfileInfo) return ApiResult.Success(detail)
            }

            ApiResult.Error(message = "该博主暂无更多公开资料")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("WeiboApi", "getUserProfileDetail failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "加载博主详细资料异常", throwable = e)
        }
    }

    /**
     * 博主主页「相册」tab：相册列表。
     *
     * 端点: https://weibo.com/ajax/profile/getImageWall?uid={uid}&sinceid=0&has_album=true
     * 返回 `data.album_list[]`，每项含 `containerid` / `pic_title` / `pic` / `pic_count`。
     */
    suspend fun getUserAlbums(uid: String, cookie: String = ""): ApiResult<List<UserAlbum>> {
        return try {
            if (!uid.all { it.isDigit() }) return ApiResult.Error(message = "仅支持数字 UID 获取相册")
            suspend fun request(): HttpResponse =
                weiboGetWithVisitorRetry(
                    buildEndpoint = { auth ->
                        webEndpoint(
                            path = "/ajax/profile/getImageWall",
                            auth = auth,
                            referer = "https://weibo.com/u/$uid?tabtype=album"
                        )
                    },
                    cookie = cookie
                ) {
                    parameter("uid", uid)
                    parameter("sinceid", "0")
                    parameter("has_album", "true")
                }

            val response = request()
            if (response.status != HttpStatusCode.OK) {
                return ApiResult.Error(code = response.status.value, message = "获取相册失败 (HTTP ${response.status.value})")
            }

            val root = response.jsonBody().jsonObject
            val ok = (root["ok"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            if (ok != 1) {
                val msg = (root["msg"] as? JsonPrimitive)?.contentOrNull
                    ?: (root["message"] as? JsonPrimitive)?.contentOrNull
                    ?: "获取相册失败"
                return ApiResult.Error(message = msg)
            }

            val data = root["data"] as? JsonObject
                ?: return ApiResult.Error(message = "相册数据为空")
            val albumList = data["album_list"] as? JsonArray ?: JsonArray(emptyList())
            val albums = albumList.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                val containerId = (obj["containerid"] as? JsonPrimitive)?.contentOrNull
                    ?.trim()
                    ?.ifBlank { null }
                    ?: return@mapNotNull null
                UserAlbum(
                    containerId = containerId,
                    title = (obj["pic_title"] as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null }
                        ?: (obj["name"] as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null }
                        ?: "未命名相册",
                    coverUrl = (obj["pic"] as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null }
                        ?: (obj["pic_url"] as? JsonPrimitive)?.contentOrNull?.trim()?.ifBlank { null },
                    photoCount = (obj["pic_count"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
                )
            }
            ApiResult.Success(albums)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("WeiboApi", "getUserAlbums failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "加载相册异常", throwable = e)
        }
    }

    /**
     * 相册内图片列表（游标分页）。
     *
     * 端点: https://weibo.com/ajax/profile/getAlbumDetail?containerid={cid}&since_id={cursor}
     * 返回 `data.list[]`（图片记录）与 `data.since_id`（下一页游标，0 表示结束）。
     */
    suspend fun getAlbumPhotos(
        containerId: String,
        sinceId: String = "0",
        cookie: String = ""
    ): ApiResult<AlbumPhotoPage> {
        return try {
            if (containerId.isBlank()) return ApiResult.Error(message = "相册 containerid 为空")

            val response = weiboGetWithVisitorRetry(
                buildEndpoint = { auth -> webEndpoint("/ajax/profile/getAlbumDetail", auth) },
                cookie = cookie
            ) {
                parameter("containerid", containerId)
                parameter("since_id", sinceId)
            }
            if (response.status != HttpStatusCode.OK) {
                return ApiResult.Error(code = response.status.value, message = "获取相册图片失败 (HTTP ${response.status.value})")
            }

            val root = response.jsonBody().jsonObject
            val ok = (root["ok"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            if (ok != 1) {
                val msg = (root["msg"] as? JsonPrimitive)?.contentOrNull
                    ?: (root["message"] as? JsonPrimitive)?.contentOrNull
                    ?: "获取相册图片失败"
                return ApiResult.Error(message = msg)
            }

            val data = root["data"] as? JsonObject
                ?: return ApiResult.Error(message = "相册图片数据为空")
            val list = data["list"] as? JsonArray ?: JsonArray(emptyList())
            val urls = LinkedHashSet<String>()
            list.forEach { element ->
                val obj = element as? JsonObject ?: return@forEach
                val pids = mutableListOf<String>()
                // 新版：pics[].pid
                (obj["pics"] as? JsonArray)?.forEach { pic ->
                    val picObj = pic as? JsonObject ?: return@forEach
                    (picObj["pid"] as? JsonPrimitive)?.contentOrNull?.let(pids::add)
                    (picObj["url"] as? JsonPrimitive)?.contentOrNull?.let(pids::add)
                }
                // 旧版：pic_ids[]
                (obj["pic_ids"] as? JsonArray)?.forEach { pic ->
                    (pic as? JsonPrimitive)?.contentOrNull?.let(pids::add)
                }
                (obj["pid"] as? JsonPrimitive)?.contentOrNull?.let(pids::add)
                (obj["pic"] as? JsonPrimitive)?.contentOrNull?.let(pids::add)
                pids.mapNotNull(::albumPhotoUrl).forEach(urls::add)
            }

            val nextCursor = (data["since_id"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val hasMore = urls.isNotEmpty() &&
                nextCursor.isNotBlank() &&
                nextCursor != sinceId &&
                nextCursor != "0" &&
                nextCursor != "-1"
            ApiResult.Success(
                AlbumPhotoPage(
                    photos = urls.toList(),
                    nextCursor = nextCursor.ifBlank { "0" },
                    hasMore = hasMore
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("WeiboApi", "getAlbumPhotos failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "加载相册图片异常", throwable = e)
        }
    }

    /**
     * 博主主页「视频」tab。
     *
     * 端点: https://weibo.com/ajax/profile/getWaterFallContent?uid={uid}&cursor={cursor}
     * 返回 `data.list[]`（微博 mblog，含 page_info.media_info）与 `data.next_cursor`。
     */
    suspend fun getUserVideos(
        uid: String,
        cursor: String = "0",
        cookie: String = ""
    ): ApiResult<VideoFeedPage> {
        return try {
            if (!uid.all { it.isDigit() }) return ApiResult.Error(message = "仅支持数字 UID 获取视频")

            suspend fun request(): HttpResponse =
                weiboGetWithVisitorRetry(
                    buildEndpoint = { auth ->
                        webEndpoint(
                            path = "/ajax/profile/getWaterFallContent",
                            auth = auth,
                            referer = "https://weibo.com/u/$uid?tabtype=newVideo"
                        )
                    },
                    cookie = cookie
                ) {
                    parameter("uid", uid)
                    parameter("cursor", cursor)
                }

            val response = request()
            if (response.status != HttpStatusCode.OK) {
                return ApiResult.Error(code = response.status.value, message = "获取视频失败 (HTTP ${response.status.value})")
            }

            val root = response.jsonBody().jsonObject
            val ok = (root["ok"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            if (ok != 1) {
                val msg = (root["msg"] as? JsonPrimitive)?.contentOrNull
                    ?: (root["message"] as? JsonPrimitive)?.contentOrNull
                    ?: "获取视频失败"
                return ApiResult.Error(message = msg)
            }

            val data = root["data"] as? JsonObject
                ?: return ApiResult.Error(message = "视频数据为空")
            val videos = withContext(Dispatchers.IO) {
                (data["list"] as? JsonArray).orEmpty().mapNotNull { element ->
                    runCatching {
                        KtorClientFactory.jsonConfig
                            .decodeFromJsonElement<WeiboStatusDto>(element)
                    }.getOrNull()
                }.distinctBy { it.listKey }
            }

            val nextCursor = (data["next_cursor"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val hasMore = videos.isNotEmpty() &&
                nextCursor.isNotBlank() &&
                nextCursor != cursor &&
                (nextCursor.toLongOrNull() ?: -1L) >= 0L
            ApiResult.Success(
                VideoFeedPage(
                    videos = videos,
                    nextCursor = nextCursor.ifBlank { "-1" },
                    hasMore = hasMore
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("WeiboApi", "getUserVideos failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "加载视频异常", throwable = e)
        }
    }

    /**
     * 博主主页「文章」tab。
     *
     * 端点: https://m.weibo.cn/api/container/getIndex?containerid=230413{uid}_-_WEIBO_SECOND_PROFILE_WEIBO_ARTICAL&page={page}
     * 注意 `ARTICAL` 是微博接口自身的拼写，不能改成 `ARTICLE`。
     */
    suspend fun getUserArticles(
        uid: String,
        page: Int = 1,
        cookie: String = ""
    ): ApiResult<ArticleFeedPage> {
        return try {
            if (!uid.all { it.isDigit() }) return ApiResult.Error(message = "仅支持数字 UID 获取文章")

            val response = weiboGetWithVisitorRetry(
                buildEndpoint = { auth -> mobileEndpoint("/api/container/getIndex", auth, referer = "https://m.weibo.cn/u/$uid") },
                cookie = cookie
            ) {
                parameter("type", "uid")
                parameter("value", uid)
                parameter("containerid", "230413${uid}_-_WEIBO_SECOND_PROFILE_WEIBO_ARTICAL")
                parameter("page", page)
                // m 站 PWA 专用头：缺了会被当普通网页请求，返回结构不同
                header("MWeibo-Pwa", "1")
            }
            if (response.status != HttpStatusCode.OK) {
                return ApiResult.Error(code = response.status.value, message = "获取文章失败 (HTTP ${response.status.value})")
            }

            val cardResp = response.decodeBody<WeiboCardResponse>()
            if (cardResp.ok != 1) {
                return ApiResult.Error(code = cardResp.ok, message = "获取文章失败 (ok: ${cardResp.ok})")
            }

            val cards = cardResp.data?.cards.orEmpty()
            val articles = buildList {
                cards.forEach { card ->
                    card.mblog?.let(::add)
                    card.cardGroup.forEach { item -> item.mblog?.let(::add) }
                }
            }.distinctBy { it.listKey }
            val total = cardResp.data?.cardlistInfo?.total
            val hasMore = articles.isNotEmpty() &&
                (total == null || page * ARTICLE_PAGE_SIZE < total)
            ApiResult.Success(
                ArticleFeedPage(
                    articles = articles,
                    page = page,
                    hasMore = hasMore,
                    total = total
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("WeiboApi", "getUserArticles failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "加载文章异常", throwable = e)
        }
    }

    /**
     * 获取博主发布的微博时间线。
     *
     * 端点: `https://weibo.com/ajax/statuses/mymblog?uid={uid}&page={page}&feature=0`
     * （网页个人主页自己调的那个），响应为 `data.list`，见 [decodeWebUserStatusPage]。
     */
    suspend fun getUserTimeline(
        uid: String,
        page: Int = 1,
        cookie: String = ""
    ): ApiResult<UserTimelineResult> {
        return try {
            val cleanUid = uid.removePrefix("name:").removePrefix("@").trim()
            if (cleanUid.isBlank()) return ApiResult.Error(message = "博主名称不能为空")
            val isNumericUid = !uid.startsWith("name:") && cleanUid.all { it.isDigit() }
            if (!isNumericUid) {
                return when (val resolved = resolveMentionUserId(cleanUid, getEffectiveCookie(cookie))) {
                    is ApiResult.Success -> getUserTimeline(resolved.data, page, cookie)
                    is ApiResult.Error -> resolved
                    ApiResult.Loading -> ApiResult.Error(message = "博主资料暂不可用，请重试")
                }
            }

            val effectiveCookie = getEffectiveCookie(cookie)
            val response = client.weiboGet(
                webEndpoint(
                    path = "/ajax/statuses/mymblog",
                    auth = WeiboAuth.Session(effectiveCookie),
                    referer = "https://weibo.com/u/$cleanUid"
                )
            ) {
                parameter("uid", cleanUid)
                parameter("page", page)
                parameter("feature", 0)
            }
            if (response.status == HttpStatusCode.OK) {
                withContext(Dispatchers.IO) { decodeWebUserStatusPage(response.bodyAsText()) }
            } else {
                ApiResult.Error(code = response.status.value, message = "获取博主动态失败: HTTP ${response.status}")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            android.util.Log.e("WeiboApi", "getUserTimeline failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "加载博主动态异常", throwable = e)
        }
    }

    /**
     * 获取关注列表。
     *
     * 容器规则（与微博客户端一致，见反编译产物 `oO0OOO.java` / `xu6.java`）：
     * - 自己：`231093_-_selffollowed`
     * - 他人：`231051_-_followers_-_{uid}`
     */
    suspend fun getFollowingUsers(
        uid: String,
        page: Int = 1,
        cookie: String = "",
        tab: FollowingListTab = FollowingListTab.ALL
    ): ApiResult<WeiboUserListPage> = getUserListContainer(uid, page, cookie, fans = false, followingTab = tab)

    /**
     * 获取粉丝列表。
     *
     * 容器规则（与微博客户端一致，见反编译产物 `oO0OOO.java` / `dje.java`）：
     * - 自己：`231016_-_selffans`
     * - 他人：`231051_-_fans_-_{uid}`
     */
    suspend fun getFollowerUsers(
        uid: String,
        page: Int = 1,
        cookie: String = ""
    ): ApiResult<WeiboUserListPage> = getUserListContainer(uid, page, cookie, fans = true)

    /**
     * 关注 / 粉丝列表共用实现。
     *
     * 端点: https://m.weibo.cn/api/container/getIndex?containerid={containerId}&page={page}
     * 用户条目挂在 `data.cards[].card_group[].user`（部分卡片顶层直接带 `user`）。
     */
    private suspend fun getUserListContainer(
        uid: String,
        page: Int,
        cookie: String,
        fans: Boolean,
        followingTab: FollowingListTab = FollowingListTab.ALL
    ): ApiResult<WeiboUserListPage> {
        return try {
            val selfUid = try {
                authPreferences?.sessionFlow?.firstOrNull()?.uid.orEmpty()
            } catch (_: Exception) {
                ""
            }
            val targetUid = uid.ifBlank { selfUid }
            if (targetUid.isBlank() || targetUid == "guest") {
                return ApiResult.Error(code = 401, message = "登录后才能查看关注和粉丝列表")
            }
            val isSelf = targetUid == selfUid
            val containerId = when {
                fans && isSelf -> "231016_-_selffans"
                fans -> "231051_-_fans_-_$targetUid"
                // 「最新发博」「经常访问」是本人专属容器，他人主页只能看普通关注列表
                isSelf -> followingTab.containerId
                else -> "231051_-_followers_-_$targetUid"
            }

            var effectiveCookie = getEffectiveCookie(cookie)
            // 失败时要把实际使用的 Cookie 交给会话失效判定，所以这里保留手写的 432 重试
            suspend fun request(): HttpResponse = client.weiboGet(
                mobileEndpoint(
                    path = "/api/container/getIndex",
                    auth = WeiboAuth.Session(effectiveCookie),
                    referer = "https://m.weibo.cn/u/$targetUid"
                )
            ) {
                parameter("containerid", containerId)
                parameter("page", page)
                // m 站 PWA 专用头：缺了会被当普通网页请求，返回结构不同
                header("MWeibo-Pwa", "1")
            }
            var response = request()
            if (response.status.value == 432) {
                cachedVisitorCookie = null
                val fresh = fetchVisitorCookie()
                if (fresh.isNotBlank()) {
                    cachedVisitorCookie = fresh
                    effectiveCookie = fresh
                    response = request()
                }
            }
            if (response.status != HttpStatusCode.OK) {
                return ApiResult.Error(
                    code = response.status.value,
                    message = "获取列表失败: HTTP ${response.status}"
                )
            }

            val cardResp = response.decodeBody<WeiboCardResponse>()
            if (cardResp.ok != 1) {
                reportSessionExpiredIfLoggedIn(effectiveCookie, cardResp.ok)
                val message = if (cardResp.ok == -100) {
                    SessionExpiredBus.MESSAGE
                } else {
                    "微博返回异常状态 (ok: ${cardResp.ok})"
                }
                return ApiResult.Error(code = cardResp.ok, message = message)
            }

            val items = mutableListOf<WeiboSearchCardInfo>()
            cardResp.data?.cards?.forEach { card ->
                // 「最新发博」等容器可能用微博卡承载用户：user 挂在 mblog 上
                card.user?.let { user ->
                    items.add(card.toSearchCardInfo().copy(user = user))
                }
                card.mblog?.user?.let { user ->
                    items.add(card.toSearchCardInfo().copy(user = user))
                }
                card.cardGroup.forEach { item ->
                    item.user?.let { user ->
                        items.add(item.toSearchCardInfo().copy(user = user))
                    }
                    item.mblog?.user?.let { user ->
                        items.add(item.toSearchCardInfo().copy(user = user))
                    }
                }
            }
            val distinctItems = items
                .filter { it.user?.screenName?.isNotBlank() == true || it.title?.isNotBlank() == true }
                .distinctBy { item ->
                    item.user?.id?.toString()
                        ?: item.user?.idStr
                        ?: item.itemid?.takeIf { it.isNotBlank() }
                        ?: item.title
                        ?: item.scheme
                        ?: item.hashCode().toString()
                }

            ApiResult.Success(
                WeiboUserListPage(
                    items = distinctItems,
                    total = cardResp.data?.cardlistInfo?.total
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("WeiboApi", "getUserListContainer failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "加载用户列表异常", throwable = e)
        }
    }
}

/** 文章列表接口每页条数（用于结合 total 判断是否还有下一页） */
private const val ARTICLE_PAGE_SIZE = 10

private val ALBUM_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp")

/**
 * 把相册接口返回的 pid / 完整 URL 统一转成可加载的大图地址。
 * `pid` 新格式自带 `.jpg` 后缀，旧格式没有，需要分别处理，避免拼出 `.jpg.jpg`。
 */
private fun albumPhotoUrl(raw: String): String? {
    val value = raw.trim()
    if (value.isBlank()) return null
    if (value.startsWith("http://", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)) {
        return value
    }
    val extension = value.substringAfterLast('.', "").lowercase()
    val fileName = if (extension in ALBUM_IMAGE_EXTENSIONS) value else "$value.jpg"
    return "https://wx1.sinaimg.cn/large/$fileName"
}
