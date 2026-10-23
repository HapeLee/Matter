package org.kazusa.matter.core.network

import org.kazusa.matter.core.datastore.AuthPreferences
import org.kazusa.matter.data.model.GroupListResponse
import org.kazusa.matter.data.model.PasswordLoginResponse
import org.kazusa.matter.data.model.SearchResultItem
import org.kazusa.matter.data.model.SearchResultKind
import android.net.Uri
import org.kazusa.matter.data.model.WeiboCardContainerDto
import org.kazusa.matter.data.model.WeiboCardDto
import org.kazusa.matter.data.model.DEFAULT_HOT_SEARCH_CONTAINER_ID
import org.kazusa.matter.data.model.HotFeedCategory
import org.kazusa.matter.data.model.HotFeedPage
import org.kazusa.matter.data.model.HotSearchBoard
import org.kazusa.matter.data.model.HotSearchChannel
import org.kazusa.matter.data.model.WeiboCardResponse
import org.kazusa.matter.data.model.WeiboSearchChannelDto
import org.kazusa.matter.data.model.WeiboSearchTabDto
import org.kazusa.matter.data.model.toHotSearchBoard
import org.kazusa.matter.data.model.WeiboSearchCardInfo
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.model.AlbumPhotoPage
import org.kazusa.matter.data.model.ArticleFeedPage
import org.kazusa.matter.data.model.UserAlbum
import org.kazusa.matter.data.model.UserPostsPage
import org.kazusa.matter.data.model.UserProfileDetail
import org.kazusa.matter.data.model.VideoFeedPage
import org.kazusa.matter.data.model.parseUserProfileDetail
import org.kazusa.matter.data.model.classifySearchCard
import org.kazusa.matter.data.model.toSearchCardInfo
import org.kazusa.matter.data.model.toSearchChipGroupOrNull
import org.kazusa.matter.data.model.SmsCodeResponse
import org.kazusa.matter.data.model.SmsLoginResponse
import org.kazusa.matter.data.model.TimelineResponse
import org.kazusa.matter.data.model.UserTimelineResult
import org.kazusa.matter.data.model.FollowingListTab
import org.kazusa.matter.data.model.WeiboUserListPage
import org.kazusa.matter.data.model.WeiboConfigResponse
import org.kazusa.matter.data.model.WeiboGroupDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.decodeURLQueryComponent
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 热门流频道所属的分组类型：`allGroups` 里 `group_type=1` 为「我的频道 / 频道推荐」，
 * `0` 是关注分组（不参与热门流）。
 */
private const val HOT_FEED_GROUP_TYPE = 1

class WeiboApiService(
    private val client: HttpClient,
    private val authPreferences: AuthPreferences? = null
) {

    /**
     * 写操作（点赞 / 收藏 / 关注）共用：缓存 `st`、串行提交、识别频控并冷却。
     * 三者共用同一个实例，是因为微博的写操作限流按账号计，分开做会各自把冷却期撑大。
     */
    private val writeSession = WeiboWriteSession(::fetchWriteCredentials)

    private val favoritesApi = FavoritesApi(client, writeSession)
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
     * 向微博 passport 申请游客凭证 (visitor ticket -> SUB)
     */
    suspend fun fetchVisitorCookie(): String {
        return try {
            val response = client.submitForm(
                url = "https://passport.weibo.com/visitor/genvisitor",
                formParameters = parameters {
                    append("cb", "gen_visitor")
                    append("fp", "{\"os\":\"1\",\"browser\":\"Chrome120\",\"fonts\":\"undefined\",\"appSeed\":\"\"}")
                }
            ) {
                header("Referer", "https://passport.weibo.com/visitor/visitor?entry=miniblog")
            }
            val text = response.bodyAsText()
            val tidRegex = Regex("\"tid\"\\s*:\\s*\"([^\"]+)\"")
            val tid = tidRegex.find(text)?.groupValues?.get(1).orEmpty()
            if (tid.isBlank()) return ""

            val incarnateResp = client.get("https://passport.weibo.com/visitor/visitor") {
                parameter("a", "incarnate")
                parameter("t", tid)
                parameter("w", "2")
                parameter("c", "095")
                parameter("gc", "")
                parameter("cb", "cross_domain_action")
                parameter("from", "weibo")
            }
            val incarnateText = incarnateResp.bodyAsText()
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
            val response = client.get("https://m.weibo.cn/api/config") {
                if (cookie.isNotBlank()) {
                    header("Cookie", cookie)
                }
            }
            val rawText = response.bodyAsText()
            // 响应体含 st / uid / user_token，任何前缀都不允许进日志
            android.util.Log.d("WeiboAuth", "verifyConfig status=${response.status}")
            if (response.status == HttpStatusCode.OK) {
                val body = KtorClientFactory.jsonConfig.decodeFromString<WeiboConfigResponse>(rawText)
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
     * 发送手机短信验证码
     * 端点: https://login.sina.com.cn/sso/v2/sms/send 与 passport 统一
     */
    suspend fun sendSmsCode(phone: String, countryCode: String = "86"): ApiResult<String> {
        return try {
            val response = client.submitForm(
                url = "https://login.sina.com.cn/sso/v2/sms/send",
                formParameters = parameters {
                    append("entry", "wapsso")
                    append("mobile", phone)
                }
            ) {
                header("Referer", "https://login.sina.com.cn/sso/signin")
                header("Origin", "https://login.sina.com.cn")
            }
            if (response.status == HttpStatusCode.OK) {
                val rawText = response.bodyAsText()
                if (rawText.contains("need mfa")) {
                    ApiResult.Error(
                        code = 403,
                        message = "微博触发了安全人机验证(MFA)。请切换到「网页授权」标签页完成滑块/图形验证并一键登录。"
                    )
                } else {
                    val body = response.body<SmsCodeResponse>()
                    if (body.retcode == 20000000L || body.retcode == 0L) {
                        ApiResult.Success(body.msg ?: "验证码已发送")
                    } else {
                        ApiResult.Error(
                            code = body.retcode.toInt(),
                            message = body.msg ?: "发送验证码失败"
                        )
                    }
                }
            } else if (response.status.value in 300..399) {
                ApiResult.Error(
                    code = response.status.value,
                    message = "新浪安全验证重定向(HTTP ${response.status.value})，请使用「网页授权」完成人机验证登录"
                )
            } else {
                ApiResult.Error(code = response.status.value, message = "HTTP ${response.status}")
            }
        } catch (e: Exception) {
            ApiResult.Error(message = e.localizedMessage ?: "发送验证码异常", throwable = e)
        }
    }

    /**
     * 校验短信验证码并执行登录
     * 端点: https://login.sina.com.cn/sso/v2/login
     */
    suspend fun loginWithSms(
        phone: String,
        smsCode: String,
        countryCode: String = "86"
    ): ApiResult<Pair<String, String>> {
        return try {
            val response = client.submitForm(
                url = "https://login.sina.com.cn/sso/v2/login",
                formParameters = parameters {
                    append("entry", "wapsso")
                    append("username", phone)
                    append("scode", smsCode)
                    append("gateway", "1")
                }
            ) {
                header("Referer", "https://login.sina.com.cn/sso/signin")
                header("Origin", "https://login.sina.com.cn")
            }

            // 提取响应中的 Set-Cookie (包含 SUB=...; SUBP=...)
            val setCookieHeaders = response.headers.getAll("Set-Cookie").orEmpty()
            val cookieMap = mutableMapOf<String, String>()
            setCookieHeaders.forEach { header ->
                val cookiePair = header.substringBefore(";").trim()
                val eqIdx = cookiePair.indexOf("=")
                if (eqIdx > 0) {
                    val key = cookiePair.substring(0, eqIdx).trim()
                    val value = cookiePair.substring(eqIdx + 1).trim()
                    cookieMap[key] = value
                }
            }

            val sub = cookieMap["SUB"].orEmpty()
            val subp = cookieMap["SUBP"].orEmpty()
            val combinedCookie = if (sub.isNotBlank()) {
                "SUB=$sub; ${if (subp.isNotBlank()) "SUBP=$subp" else ""}".trim()
            } else {
                setCookieHeaders.joinToString("; ") { it.substringBefore(";") }
            }

            val rawText = response.bodyAsText()
            if (rawText.contains("need mfa")) {
                ApiResult.Error(
                    code = 403,
                    message = "检测到安全验证(MFA)，请切换到「网页授权」完成验证登录"
                )
            } else {
                val body = response.body<SmsLoginResponse>()
                if (body.retcode == 20000000L || body.retcode == 0L || combinedCookie.isNotBlank()) {
                    val uid = body.data?.uid.orEmpty()
                    ApiResult.Success(Pair(uid, combinedCookie))
                } else {
                    ApiResult.Error(
                        code = body.retcode.toInt(),
                        message = body.msg ?: "短信验证码错误或已失效"
                    )
                }
            }
        } catch (e: Exception) {
            ApiResult.Error(message = e.localizedMessage ?: "短信登录异常", throwable = e)
        }
    }

    /**
     * 账号密码登录
     * 端点: https://login.sina.com.cn/sso/v2/login
     */
    suspend fun loginWithPassword(
        username: String,
        password: String
    ): ApiResult<Pair<String, String>> {
        return try {
            val response = client.submitForm(
                url = "https://login.sina.com.cn/sso/v2/login",
                formParameters = parameters {
                    append("entry", "wapsso")
                    append("username", username)
                    append("password", password)
                    append("gateway", "1")
                }
            ) {
                header("Referer", "https://login.sina.com.cn/sso/signin")
                header("Origin", "https://login.sina.com.cn")
            }

            val setCookieHeaders = response.headers.getAll("Set-Cookie").orEmpty()
            val cookieMap = mutableMapOf<String, String>()
            setCookieHeaders.forEach { header ->
                val cookiePair = header.substringBefore(";").trim()
                val eqIdx = cookiePair.indexOf("=")
                if (eqIdx > 0) {
                    val key = cookiePair.substring(0, eqIdx).trim()
                    val value = cookiePair.substring(eqIdx + 1).trim()
                    cookieMap[key] = value
                }
            }

            val sub = cookieMap["SUB"].orEmpty()
            val subp = cookieMap["SUBP"].orEmpty()
            val combinedCookie = if (sub.isNotBlank()) {
                "SUB=$sub; ${if (subp.isNotBlank()) "SUBP=$subp" else ""}".trim()
            } else {
                setCookieHeaders.joinToString("; ") { it.substringBefore(";") }
            }

            val rawText = response.bodyAsText()
            if (rawText.contains("need mfa")) {
                ApiResult.Error(
                    code = 403,
                    message = "账号需要进行人机安全验证(MFA)。请切换到「网页授权」标签页完成验证并登录。"
                )
            } else {
                val body = response.body<PasswordLoginResponse>()
                if (body.retcode == 20000000L || body.retcode == 0L || combinedCookie.isNotBlank()) {
                    val uid = body.data?.uid.orEmpty()
                    ApiResult.Success(Pair(uid, combinedCookie))
                } else {
                    ApiResult.Error(
                        code = body.retcode.toInt(),
                        message = body.msg.takeIf { !it.isNullOrBlank() } ?: "登录失败，请检查账号密码"
                    )
                }
            }
        } catch (e: Exception) {
            ApiResult.Error(message = e.localizedMessage ?: "密码登录异常", throwable = e)
        }
    }

    /**
     * 获取关注好友时间线
     * 端点: https://m.weibo.cn/feed/friends
     */
    suspend fun getFriendsTimeline(
        cookie: String,
        page: Int = 1,
        maxId: Long? = null
    ): ApiResult<TimelineResponse> {
        return try {
            val response = client.get("https://m.weibo.cn/feed/friends") {
                if (cookie.isNotBlank()) {
                    header("Cookie", cookie)
                }
                header("Referer", "https://m.weibo.cn/")
                header("MWeibo-Pwa", "1")
                parameter("page", page)
                if (maxId != null && maxId > 0) {
                    parameter("max_id", maxId)
                }
            }
            val rawText = response.bodyAsText()
            android.util.Log.d("TimelineApi", "friends response status=${response.status}, len=${rawText.length}")
            if (response.status == HttpStatusCode.OK) {
                val body = KtorClientFactory.jsonConfig.decodeFromString<TimelineResponse>(rawText)
                if (body.ok == 1) {
                    ApiResult.Success(body)
                } else {
                    ApiResult.Error(
                        code = body.ok,
                        message = if (body.ok == -100) "登录凭证已失效(-100)，请点击下方退出并重新登录" else "获取微博动态失败(ok=${body.ok})"
                    )
                }
            } else {
                ApiResult.Error(
                    code = response.status.value,
                    message = "加载时间线失败: HTTP ${response.status}"
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("TimelineApi", "getFriendsTimeline failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "加载时间线异常", throwable = e)
        }
    }

    /**
     * 获取指定分组下的微博时间线
     * 端点: https://m.weibo.cn/feed/group?gid=xxx&page=1
     */
    suspend fun getGroupTimeline(
        cookie: String,
        gid: String,
        page: Int = 1,
        maxId: Long? = null
    ): ApiResult<TimelineResponse> {
        val effectiveCookie = getEffectiveCookie(cookie)
        return try {
            val response = client.get("https://m.weibo.cn/feed/group") {
                if (effectiveCookie.isNotBlank()) {
                    header("Cookie", effectiveCookie)
                }
                header("Referer", "https://m.weibo.cn/")
                header("MWeibo-Pwa", "1")
                header("X-Requested-With", "XMLHttpRequest")
                parameter("gid", gid)
                parameter("page", page)
                if (maxId != null && maxId > 0) {
                    parameter("max_id", maxId)
                }
            }
            val rawText = response.bodyAsText()
            android.util.Log.d("TimelineApi", "group response status=${response.status}, len=${rawText.length}")
            if (response.status == HttpStatusCode.OK) {
                val body = KtorClientFactory.jsonConfig.decodeFromString<TimelineResponse>(rawText)
                if (body.ok == 1) {
                    ApiResult.Success(body)
                } else {
                    ApiResult.Error(
                        code = body.ok,
                        message = if (body.ok == -100) "登录凭证已失效(-100)，请点击下方退出并重新登录" else "获取分组微博失败(ok=${body.ok})"
                    )
                }
            } else {
                ApiResult.Error(
                    code = response.status.value,
                    message = "加载分组动态失败: HTTP ${response.status}"
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("TimelineApi", "getGroupTimeline failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "加载分组微博异常", throwable = e)
        }
    }

    /**
     * 获取用户关注分组列表
     * 端点: https://api.weibo.com/2/friendships/groups.json 或 m端分组导航
     */
    /**
     * 获取用户关注分组列表
     * 优先使用 weibo.com/ajax/feed/allGroups 获取具有真实数值 gid（包括特别关注、互相关注以及自定义分组）的完整列表
     */
    suspend fun getFollowGroups(cookie: String): ApiResult<List<WeiboGroupDto>> {
        val effectiveCookie = getEffectiveCookie(cookie)
        // 1. 尝试从 weibo.com/ajax/feed/allGroups 获取最新完整真实分组
        try {
            val response = client.get("https://weibo.com/ajax/feed/allGroups") {
                if (effectiveCookie.isNotBlank()) {
                    header("Cookie", effectiveCookie)
                }
                header("Referer", "https://weibo.com")
            }
            if (response.status == HttpStatusCode.OK) {
                val rawText = response.bodyAsText()
                val jsonRoot = KtorClientFactory.jsonConfig.parseToJsonElement(rawText).jsonObject
                val okVal = jsonRoot["ok"]?.jsonPrimitive?.content?.toIntOrNull()
                if (okVal == 1) {
                    val groupsArr = jsonRoot["groups"]?.jsonArray
                    val list = mutableListOf<WeiboGroupDto>()
                    list.add(WeiboGroupDto(id = 0, gid = "all", name = "全部关注", memberCount = 0))

                    groupsArr?.forEach { groupCat ->
                        val catObj = groupCat.jsonObject
                        val catType = catObj["group_type"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        val catTitle = catObj["title"]?.jsonPrimitive?.content.orEmpty()

                        // 仅提取关注相关分组（默认分组、我的分组等 group_type == 0），过滤公共推荐频道（group_type == 1）
                        if (catType == 0 || catTitle.contains("分组")) {
                            val subGroups = catObj["group"]?.jsonArray
                            subGroups?.forEach { gObj ->
                                val g = gObj.jsonObject
                                val gid = g["gid"]?.jsonPrimitive?.content.orEmpty()
                                val title = g["title"]?.jsonPrimitive?.content
                                    ?: g["name"]?.jsonPrimitive?.content
                                    ?: g["navigation_title"]?.jsonPrimitive?.content.orEmpty()
                                val cnt = g["count"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

                                // 过滤掉全部关注（已作为第一项）和热门推荐等非关注类条目
                                val isFollowGroup = gid.isNotBlank() &&
                                        title.isNotBlank() &&
                                        !title.contains("热门") &&
                                        !title.contains("推荐") &&
                                        title != "全部关注" &&
                                        title != "关注" &&
                                        !gid.startsWith("10001")

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

                    if (list.size > 1) {
                        android.util.Log.i("WeiboApi", "getFollowGroups: successfully parsed ${list.size} groups: ${list.map { "${it.name}(${it.gid})" }}")
                        return ApiResult.Success(list)
                    }
                }
            } else {
                android.util.Log.w("WeiboApi", "ajax/feed/allGroups returned status: ${response.status}")
            }
        } catch (e: Exception) {
            android.util.Log.w("WeiboApi", "getFollowGroups from ajax/feed/allGroups failed, fallback", e)
        }

        // 2. 备用方式：api.weibo.com 开放接口
        return try {
            val response = client.get("https://api.weibo.com/2/friendships/groups.json") {
                if (effectiveCookie.isNotBlank()) {
                    header("Cookie", effectiveCookie)
                }
            }
            if (response.status == HttpStatusCode.OK) {
                val body = response.body<GroupListResponse>()
                val groups = body.lists.ifEmpty { body.data.orEmpty() }
                if (groups.isNotEmpty()) {
                    val filtered = groups.filter { it.name != "全部关注" && it.title != "全部关注" && it.gid != "all" }
                    val fullList = listOf(WeiboGroupDto(id = 0, gid = "all", name = "全部关注", memberCount = 0)) + filtered
                    ApiResult.Success(fullList)
                } else {
                    ApiResult.Success(getDefaultSystemGroups())
                }
            } else {
                ApiResult.Success(getDefaultSystemGroups())
            }
        } catch (e: Exception) {
            ApiResult.Success(getDefaultSystemGroups())
        }
    }

    private fun getDefaultSystemGroups(): List<WeiboGroupDto> {
        return listOf(
            WeiboGroupDto(id = 0, gid = "all", name = "全部关注", memberCount = 0)
        )
    }

    /**
     * 创建关注分组
     * 端点: POST https://api.weibo.com/2/friendships/groups/create.json
     * 备用端点: POST https://weibo.com/ajax/feed/createGroup
     */
    suspend fun createGroup(
        name: String,
        description: String = "",
        cookie: String = ""
    ): ApiResult<WeiboGroupDto> {
        val effectiveCookie = getEffectiveCookie(cookie)
        if (effectiveCookie.isBlank()) {
            return ApiResult.Error(code = 401, message = "未检测到有效登录状态，无法同步至微博")
        }
        // 1. 尝试使用 openapi
        try {
            val response = client.submitForm(
                url = "https://api.weibo.com/2/friendships/groups/create.json",
                formParameters = parameters {
                    append("name", name)
                    if (description.isNotBlank()) {
                        append("description", description)
                    }
                }
            ) {
                header("Cookie", effectiveCookie)
            }
            if (response.status == HttpStatusCode.OK) {
                val rawText = response.bodyAsText()
                val jsonRoot = KtorClientFactory.jsonConfig.parseToJsonElement(rawText).jsonObject
                val idStr = jsonRoot["idstr"]?.jsonPrimitive?.contentOrNull
                    ?: jsonRoot["id"]?.jsonPrimitive?.contentOrNull
                val groupName = jsonRoot["name"]?.jsonPrimitive?.contentOrNull ?: name
                if (!idStr.isNullOrBlank()) {
                    return ApiResult.Success(
                        WeiboGroupDto(
                            id = idStr.toLongOrNull(),
                            idStr = idStr,
                            gid = idStr,
                            name = groupName,
                            title = groupName,
                            memberCount = 0
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("WeiboApi", "createGroup via api.weibo.com failed, trying web fallback", e)
        }

        // 2. 备用方式: weibo.com/ajax/feed/createGroup
        return try {
            val response = client.submitForm(
                url = "https://weibo.com/ajax/feed/createGroup",
                formParameters = parameters {
                    append("name", name)
                }
            ) {
                header("Cookie", effectiveCookie)
                header("Referer", "https://weibo.com/")
                header("X-Requested-With", "XMLHttpRequest")
            }
            val rawText = response.bodyAsText()
            val jsonRoot = KtorClientFactory.jsonConfig.parseToJsonElement(rawText).jsonObject
            val ok = jsonRoot["ok"]?.jsonPrimitive?.intOrNull ?: 0
            val gid = jsonRoot["gid"]?.jsonPrimitive?.contentOrNull
                ?: jsonRoot["id"]?.jsonPrimitive?.contentOrNull
                ?: System.currentTimeMillis().toString()
            if (ok == 1 || gid.isNotBlank()) {
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
                val errorMsg = jsonRoot["msg"]?.jsonPrimitive?.contentOrNull
                    ?: jsonRoot["error"]?.jsonPrimitive?.contentOrNull ?: "创建分组失败"
                ApiResult.Error(message = errorMsg)
            }
        } catch (e: Exception) {
            ApiResult.Error(message = e.localizedMessage ?: "网络异常，创建分组失败", throwable = e)
        }
    }

    /**
     * 更新关注分组名称
     * 端点: POST https://api.weibo.com/2/friendships/groups/update.json
     * 备用端点: POST https://weibo.com/ajax/feed/updateGroupName
     */
    suspend fun updateGroupName(
        gid: String,
        name: String,
        cookie: String = ""
    ): ApiResult<Unit> {
        val effectiveCookie = getEffectiveCookie(cookie)
        if (effectiveCookie.isBlank()) {
            return ApiResult.Error(code = 401, message = "未检测到有效登录状态，无法同步至微博")
        }
        // 1. 尝试 openapi
        try {
            val response = client.submitForm(
                url = "https://api.weibo.com/2/friendships/groups/update.json",
                formParameters = parameters {
                    append("list_id", gid)
                    append("name", name)
                }
            ) {
                header("Cookie", effectiveCookie)
            }
            if (response.status == HttpStatusCode.OK) {
                return ApiResult.Success(Unit)
            }
        } catch (e: Exception) {
            android.util.Log.w("WeiboApi", "updateGroupName via api.weibo.com failed, trying fallback", e)
        }

        // 2. 备用方式
        return try {
            val response = client.submitForm(
                url = "https://weibo.com/ajax/feed/updateGroupName",
                formParameters = parameters {
                    append("gid", gid)
                    append("name", name)
                }
            ) {
                header("Cookie", effectiveCookie)
                header("Referer", "https://weibo.com/")
                header("X-Requested-With", "XMLHttpRequest")
            }
            val rawText = response.bodyAsText()
            val jsonRoot = KtorClientFactory.jsonConfig.parseToJsonElement(rawText).jsonObject
            val ok = jsonRoot["ok"]?.jsonPrimitive?.intOrNull ?: 0
            if (ok == 1 || response.status == HttpStatusCode.OK) {
                ApiResult.Success(Unit)
            } else {
                val errorMsg = jsonRoot["msg"]?.jsonPrimitive?.contentOrNull ?: "更新分组名称失败"
                ApiResult.Error(message = errorMsg)
            }
        } catch (e: Exception) {
            ApiResult.Error(message = e.localizedMessage ?: "网络异常，更新分组失败", throwable = e)
        }
    }

    /**
     * 删除关注分组
     * 端点: POST https://api.weibo.com/2/friendships/groups/destroy.json
     * 备用端点: POST https://weibo.com/ajax/feed/destroyGroup
     */
    suspend fun destroyGroup(
        gid: String,
        cookie: String = ""
    ): ApiResult<Unit> {
        val effectiveCookie = getEffectiveCookie(cookie)
        if (effectiveCookie.isBlank()) {
            return ApiResult.Error(code = 401, message = "未检测到有效登录状态，无法同步至微博")
        }
        // 1. 尝试 openapi
        try {
            val response = client.submitForm(
                url = "https://api.weibo.com/2/friendships/groups/destroy.json",
                formParameters = parameters {
                    append("list_id", gid)
                }
            ) {
                header("Cookie", effectiveCookie)
            }
            if (response.status == HttpStatusCode.OK) {
                return ApiResult.Success(Unit)
            }
        } catch (e: Exception) {
            android.util.Log.w("WeiboApi", "destroyGroup via api.weibo.com failed, trying fallback", e)
        }

        // 2. 备用方式
        return try {
            val response = client.submitForm(
                url = "https://weibo.com/ajax/feed/destroyGroup",
                formParameters = parameters {
                    append("gid", gid)
                }
            ) {
                header("Cookie", effectiveCookie)
                header("Referer", "https://weibo.com/")
                header("X-Requested-With", "XMLHttpRequest")
            }
            val rawText = response.bodyAsText()
            val jsonRoot = KtorClientFactory.jsonConfig.parseToJsonElement(rawText).jsonObject
            val ok = jsonRoot["ok"]?.jsonPrimitive?.intOrNull ?: 0
            if (ok == 1 || response.status == HttpStatusCode.OK) {
                ApiResult.Success(Unit)
            } else {
                val errorMsg = jsonRoot["msg"]?.jsonPrimitive?.contentOrNull ?: "删除分组失败"
                ApiResult.Error(message = errorMsg)
            }
        } catch (e: Exception) {
            ApiResult.Error(message = e.localizedMessage ?: "网络异常，删除分组失败", throwable = e)
        }
    }

    /**
     * 更新关注分组排序
     * 端点: POST https://api.weibo.com/2/friendships/groups/order.json
     */
    suspend fun setGroupOrder(
        gids: List<String>,
        cookie: String = ""
    ): ApiResult<Unit> {
        val effectiveCookie = getEffectiveCookie(cookie)
        if (effectiveCookie.isBlank()) {
            return ApiResult.Success(Unit)
        }
        val gidListStr = gids.filter { it.isNotBlank() && it != "all" }.joinToString(",")
        if (gidListStr.isBlank()) return ApiResult.Success(Unit)

        return try {
            val response = client.submitForm(
                url = "https://api.weibo.com/2/friendships/groups/order.json",
                formParameters = parameters {
                    append("list_ids", gidListStr)
                }
            ) {
                header("Cookie", effectiveCookie)
            }
            if (response.status == HttpStatusCode.OK) {
                ApiResult.Success(Unit)
            } else {
                // 尝试 web 接口
                client.submitForm(
                    url = "https://weibo.com/ajax/feed/setGroupOrder",
                    formParameters = parameters {
                        append("gids", gidListStr)
                    }
                ) {
                    header("Cookie", effectiveCookie)
                    header("Referer", "https://weibo.com/")
                    header("X-Requested-With", "XMLHttpRequest")
                }
                ApiResult.Success(Unit)
            }
        } catch (e: Exception) {
            ApiResult.Success(Unit)
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
            val response = client.get("https://m.weibo.cn/api/container/getIndex") {
                parameter("containerid", containerId)
                if (latLon != null) {
                    parameter("lat", latLon.first)
                    parameter("lon", latLon.second)
                }
                header("Referer", "https://m.weibo.cn/")
                header("MWeibo-Pwa", "1")
                header("X-Requested-With", "XMLHttpRequest")
                if (effectiveCookie.isNotBlank()) header("Cookie", effectiveCookie)
            }
            if (response.status == HttpStatusCode.OK) {
                val rawText = response.bodyAsText()
                val cardResp = KtorClientFactory.jsonConfig
                    .decodeFromString<WeiboCardResponse>(rawText)
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
     * 端点: `https://weibo.com/ajax/feed/allGroups`
     *
     * 只取 `group_type=1` 的两组：**我的频道**（热门 / 同城 / 实时 / 榜单 / 明星 …）
     * 与**频道推荐**（财经 / 读书 / 摄影 …）。`group_type=0` 是关注分组，不属于热门流。
     *
     * 每项频道自带 `gid`（请求热门流时作为 `group_id`）与 `containerid`
     * （请求热门流时作为 `containerid`），分类名与顺序完全按服务端下发。
     */
    suspend fun getHotFeedCategories(cookie: String = ""): ApiResult<List<HotFeedCategory>> {
        return try {
            val effectiveCookie = getEffectiveCookie(cookie)
            val response = client.get("https://weibo.com/ajax/feed/allGroups") {
                header("Referer", "https://weibo.com/")
                header("X-Requested-With", "XMLHttpRequest")
                if (effectiveCookie.isNotBlank()) header("Cookie", effectiveCookie)
            }
            if (response.status != HttpStatusCode.OK) {
                return ApiResult.Error(
                    code = response.status.value,
                    message = "获取热门分类失败: HTTP ${response.status}"
                )
            }
            val root = KtorClientFactory.jsonConfig
                .parseToJsonElement(response.bodyAsText())
                .jsonObject
            val ok = (root["ok"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            if (ok != 1) {
                return ApiResult.Error(
                    code = ok,
                    message = if (ok == -100) "登录状态已失效，请重新登录后查看热门" else "获取热门分类失败(ok=$ok)"
                )
            }

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
            val response = client.get("https://weibo.com/ajax/feed/hottimeline") {
                parameter("since_id", sinceId.ifBlank { "0" })
                parameter("refresh", "0")
                parameter("group_id", groupId)
                parameter("containerid", containerId)
                parameter("extparam", "discover|new_feed")
                parameter("max_id", maxId.ifBlank { "0" })
                parameter("count", count)
                header("Referer", "https://weibo.com/")
                header("X-Requested-With", "XMLHttpRequest")
                if (effectiveCookie.isNotBlank()) header("Cookie", effectiveCookie)
            }
            if (response.status != HttpStatusCode.OK) {
                return ApiResult.Error(
                    code = response.status.value,
                    message = "获取热门流失败: HTTP ${response.status}"
                )
            }
            val root = KtorClientFactory.jsonConfig
                .parseToJsonElement(response.bodyAsText())
                .jsonObject
            val ok = (root["ok"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            if (ok != 1) {
                val msg = (root["msg"] as? JsonPrimitive)?.contentOrNull
                    ?: (root["message"] as? JsonPrimitive)?.contentOrNull
                    ?: if (ok == -100) "登录状态已失效，请重新登录后查看热门" else "获取热门流失败(ok=$ok)"
                return ApiResult.Error(code = ok, message = msg)
            }

            val posts = (root["statuses"] as? JsonArray).orEmpty().mapNotNull { element ->
                runCatching {
                    KtorClientFactory.jsonConfig.decodeFromJsonElement<WeiboStatusDto>(element)
                }.getOrNull()
            }.distinctBy { it.listKey }

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
    private fun readHotFeedSinceId(element: kotlinx.serialization.json.JsonElement?): String? {
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
    ): ApiResult<List<org.kazusa.matter.data.model.WeiboStatusDto>> {
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
    ): ApiResult<org.kazusa.matter.data.model.SearchPageResult> {
        return try {
            val effectiveCookie = getEffectiveCookie(cookie)
            val requestContainerId = containerId?.takeIf { it.isNotBlank() }
                ?: defaultSearchContainerId(keyword, searchType)

            val response = client.get("https://m.weibo.cn/api/container/getIndex") {
                parameter("containerid", requestContainerId)
                parameter("page_type", "searchall")
                parameter("page", page)
                header("Referer", "https://m.weibo.cn/")
                header("MWeibo-Pwa", "1")
                header("X-Requested-With", "XMLHttpRequest")
                if (effectiveCookie.isNotBlank()) header("Cookie", effectiveCookie)
            }
            if (response.status == HttpStatusCode.OK) {
                val rawText = response.bodyAsText()
                val cardResp = KtorClientFactory.jsonConfig
                    .decodeFromString<org.kazusa.matter.data.model.WeiboCardResponse>(rawText)
                val container = cardResp.data
                val tabs = resolveSearchTabs(container)
                // 首屏请求打的就是「默认栏」的 containerid（`100103type=1&q=…&t=1`）。
                // 服务端没给 `tab_default` 时，用 containerid 反查该栏下标 ——
                // 不依赖任何分栏名文案（客户端零硬编码），否则会把首屏数据种到别的分栏上。
                val defaultIndex = container?.tabDefault?.takeIf { it in tabs.indices }
                    ?: tabs.indexOfFirst { it.containerid == requestContainerId }.takeIf { it >= 0 }
                    ?: 0
                ApiResult.Success(
                    org.kazusa.matter.data.model.SearchPageResult(
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
    ): ApiResult<List<org.kazusa.matter.data.model.WeiboStatusDto>> {
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
     * 获取单条微博详情
     * 端点: https://m.weibo.cn/statuses/show?id={id}
     */
    suspend fun getPostDetail(id: String, cookie: String = ""): ApiResult<org.kazusa.matter.data.model.WeiboStatusDto> {
        return try {
            val effectiveCookie = getEffectiveCookie(cookie)
            val response = client.get("https://m.weibo.cn/statuses/show") {
                parameter("id", id)
                header("Referer", "https://m.weibo.cn/detail/$id")
                header("MWeibo-Pwa", "1")
                header("X-Requested-With", "XMLHttpRequest")
                if (effectiveCookie.isNotBlank()) header("Cookie", effectiveCookie)
            }
            if (response.status == HttpStatusCode.OK) {
                val rawText = response.bodyAsText()
                val resp = KtorClientFactory.jsonConfig.decodeFromString<org.kazusa.matter.data.model.WeiboDetailResponse>(rawText)
                resp.data?.let {
                    ApiResult.Success(it)
                } ?: ApiResult.Error(message = "未获取到微博详情")
            } else {
                ApiResult.Error(code = response.status.value, message = "HTTP ${response.status}")
            }
        } catch (e: Exception) {
            android.util.Log.e("WeiboApi", "getPostDetail failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "获取微博详情异常", throwable = e)
        }
    }

    /**
     * 获取微博评论（支持按热度或按时间获取）
     * 热门评论端点: https://m.weibo.cn/comments/hotflow?id={id}&mid={id}&max_id_type=0
     * 时间流评论端点: https://m.weibo.cn/api/comments/show?id={id}&page={page}
     */
    suspend fun getComments(
        id: String,
        sortByHot: Boolean = true,
        maxId: Long? = null,
        page: Int = 1,
        cookie: String = ""
    ): ApiResult<org.kazusa.matter.data.model.CommentsDataDto> {
        return try {
            val effectiveCookie = getEffectiveCookie(cookie)
            if (sortByHot) {
                val response = client.get("https://m.weibo.cn/comments/hotflow") {
                    parameter("id", id)
                    parameter("mid", id)
                    parameter("max_id_type", "0")
                    if (maxId != null && maxId > 0) {
                        parameter("max_id", maxId)
                    }
                    header("Referer", "https://m.weibo.cn/detail/$id")
                    header("MWeibo-Pwa", "1")
                    header("X-Requested-With", "XMLHttpRequest")
                    if (effectiveCookie.isNotBlank()) header("Cookie", effectiveCookie)
                }
                if (response.status == HttpStatusCode.OK) {
                    val rawText = response.bodyAsText()
                    val resp = KtorClientFactory.jsonConfig.decodeFromString<org.kazusa.matter.data.model.CommentsResponse>(rawText)
                    when {
                        // 没有评论时服务端返回 ok=0 + "这里还没有内容"，按空列表处理
                        isWeiboEmptyListResponse(resp.ok, resp.msg, resp.data?.data?.size) ->
                            ApiResult.Success(org.kazusa.matter.data.model.CommentsDataDto(data = emptyList()))
                        resp.data != null -> ApiResult.Success(resp.data)
                        else -> ApiResult.Error(message = "评论加载失败，请稍后重试")
                    }
                } else {
                    ApiResult.Error(code = response.status.value, message = "HTTP ${response.status}")
                }
            } else {
                // 按最新时间排序
                val response = client.get("https://m.weibo.cn/api/comments/show") {
                    parameter("id", id)
                    parameter("page", page)
                    header("Referer", "https://m.weibo.cn/detail/$id")
                    header("MWeibo-Pwa", "1")
                    header("X-Requested-With", "XMLHttpRequest")
                    if (effectiveCookie.isNotBlank()) header("Cookie", effectiveCookie)
                }
                if (response.status == HttpStatusCode.OK) {
                    val rawText = response.bodyAsText()
                    val resp = KtorClientFactory.jsonConfig.decodeFromString<org.kazusa.matter.data.model.CommentsResponse>(rawText)
                    when {
                        // 没有评论时服务端返回 ok=0 + "这里还没有内容"，按空列表处理
                        isWeiboEmptyListResponse(resp.ok, resp.msg, resp.data?.data?.size) ->
                            ApiResult.Success(org.kazusa.matter.data.model.CommentsDataDto(data = emptyList()))
                        resp.data != null -> ApiResult.Success(resp.data)
                        else -> ApiResult.Error(message = "评论加载失败，请稍后重试")
                    }
                } else {
                    ApiResult.Error(code = response.status.value, message = "HTTP ${response.status}")
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("WeiboApi", "getComments failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "获取评论异常", throwable = e)
        }
    }

    /** 获取评论的楼中楼回复，按服务端游标分页。 */
    suspend fun getCommentReplies(
        postId: String,
        commentId: Long,
        maxId: Long = 0,
        maxIdType: Int = 0,
        cookie: String = ""
    ): ApiResult<org.kazusa.matter.data.model.CommentsDataDto> {
        return try {
            val effectiveCookie = getEffectiveCookie(cookie)
            val response = client.get("https://m.weibo.cn/comments/hotFlowChild") {
                parameter("cid", commentId)
                parameter("max_id", maxId)
                parameter("max_id_type", maxIdType)
                header("Referer", "https://m.weibo.cn/detail/$postId?cid=$commentId")
                header("MWeibo-Pwa", "1")
                header("X-Requested-With", "XMLHttpRequest")
                if (effectiveCookie.isNotBlank()) header("Cookie", effectiveCookie)
            }
            if (response.status == HttpStatusCode.OK) {
                decodeCommentReplies(response.bodyAsText())
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
     * 获取微博转发列表
     * 端点: https://m.weibo.cn/api/statuses/repostTimeline?id={id}&page={page}
     */
    suspend fun getReposts(
        id: String,
        page: Int = 1,
        cookie: String = ""
    ): ApiResult<org.kazusa.matter.data.model.RepostsDataDto> {
        return try {
            val effectiveCookie = getEffectiveCookie(cookie)
            val response = client.get("https://m.weibo.cn/api/statuses/repostTimeline") {
                parameter("id", id)
                parameter("page", page)
                header("Referer", "https://m.weibo.cn/detail/$id")
                header("MWeibo-Pwa", "1")
                header("X-Requested-With", "XMLHttpRequest")
                if (effectiveCookie.isNotBlank()) header("Cookie", effectiveCookie)
            }
            if (response.status == HttpStatusCode.OK) {
                val rawText = response.bodyAsText()
                val resp = KtorClientFactory.jsonConfig.decodeFromString<org.kazusa.matter.data.model.RepostsResponse>(rawText)
                when {
                    // 没有转发时服务端返回 ok=0 + "这里还没有内容"，按空列表处理
                    isWeiboEmptyListResponse(resp.ok, resp.msg, resp.data?.data?.size) ->
                        ApiResult.Success(org.kazusa.matter.data.model.RepostsDataDto(data = emptyList()))
                    resp.data != null -> ApiResult.Success(resp.data)
                    else -> ApiResult.Error(message = "转发列表加载失败")
                }
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
    ): ApiResult<org.kazusa.matter.data.model.AttitudesDataDto> {
        return try {
            val effectiveCookie = getEffectiveCookie(cookie)
            val response = client.get("https://m.weibo.cn/api/attitudes/show") {
                parameter("id", id)
                parameter("page", page)
                header("Referer", "https://m.weibo.cn/detail/$id")
                header("MWeibo-Pwa", "1")
                header("X-Requested-With", "XMLHttpRequest")
                if (effectiveCookie.isNotBlank()) header("Cookie", effectiveCookie)
            }
            if (response.status == HttpStatusCode.OK) {
                val rawText = response.bodyAsText()
                val resp = KtorClientFactory.jsonConfig.decodeFromString<org.kazusa.matter.data.model.AttitudesResponse>(rawText)
                when {
                    // 没有点赞时服务端返回 ok=0 + "这里还没有内容"，按空列表处理
                    isWeiboEmptyListResponse(resp.ok, resp.msg, resp.data?.data?.size) ->
                        ApiResult.Success(org.kazusa.matter.data.model.AttitudesDataDto(data = emptyList()))
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
     * 对微博点赞或取消点赞
     * 端点:
     * - 点赞: POST https://m.weibo.cn/api/attitudes/create
     * - 取消点赞: POST https://m.weibo.cn/api/attitudes/destroy
     */
    suspend fun setAttitude(
        id: String,
        isLike: Boolean,
        cookie: String = "",
        /** 评论点赞时传所属微博 id，用于拼出合法的 Referer；微博点赞留空。 */
        refererPostId: String? = null
    ): ApiResult<Boolean> {
        return try {
            val effectiveCookie = getEffectiveCookie(cookie)
            if (effectiveCookie.isBlank()) {
                return ApiResult.Error(code = 401, message = "未登录或登录已失效，请先登录")
            }
            val path = if (isLike) "create" else "destroy"
            // 用评论 id 拼出来的 detail 链接并不存在，微博会据此判为异常请求，
            // 所以评论点赞必须带上所属微博 id
            val refererId = refererPostId?.takeIf { it.isNotBlank() } ?: id
            writeSession.execute(
                cookie = effectiveCookie,
                action = if (isLike) "点赞" else "取消点赞"
            ) { credentials ->
                val response = client.submitForm(
                    url = "https://m.weibo.cn/api/attitudes/$path",
                    formParameters = parameters {
                        append("id", id)
                        append("attitude", "heart")
                        append("st", credentials.token)
                    }
                ) {
                    header("Cookie", credentials.cookieHeader)
                    header("X-XSRF-TOKEN", credentials.token)
                    header("Origin", "https://m.weibo.cn")
                    header("Referer", "https://m.weibo.cn/detail/$refererId")
                    header("MWeibo-Pwa", "1")
                    header("X-Requested-With", "XMLHttpRequest")
                }
                readWriteResponse(
                    response = response,
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
     * 端点: POST https://m.weibo.cn/api/comments/create 或 comments/reply（附带 cid）。
     *
     * 移动端 PWA 提交的是 `mid` + `content` + `st`（mid 即详情链接里的微博 id）；
     * 这里同时带上 `id`，兼容仍按 `id` 取目标微博的端。
     *
     * 与点赞 / 关注共用同一个 [WeiboWriteSession]：`st` 令牌、串行提交与频控冷却一致。
     * 成功时尽量带回服务端新建的评论（`data` 为对象时可直接插入列表）；
     * 部分响应只给 `ok=1`，此时返回 `null`，由调用方静默重拉评论。
     */
    suspend fun createComment(
        id: String,
        text: String,
        cookie: String = "",
        commentId: Long? = null,
        pictureId: String? = null
    ): ApiResult<org.kazusa.matter.data.model.WeiboCommentDto?> {
        if (id.isBlank() || (commentId != null && commentId <= 0)) {
            return ApiResult.Error(message = "回复目标无效")
        }
        val content = text.trim()
        if (content.isEmpty() && pictureId.isNullOrBlank()) return ApiResult.Error(message = "回复内容不能为空")
        return try {
            val effectiveCookie = getEffectiveCookie(cookie)
            if (effectiveCookie.isBlank()) {
                return ApiResult.Error(code = 401, message = "未登录或登录已失效，请先登录")
            }
            writeSession.execute(cookie = effectiveCookie, action = "回复") { mobileCredentials ->
                val credentials = if (pictureId == null) mobileCredentials else desktopReplyCredentials(mobileCredentials)
                val response = client.submitForm(
                    url = if (pictureId == null) "https://m.weibo.cn/api/comments/${if (commentId == null) "create" else "reply"}"
                        else "https://weibo.com/ajax/comments/${if (commentId == null) "create" else "reply"}",
                    formParameters = parameters {
                        append("mid", id)
                        append("id", id)
                        commentId?.let { append("cid", it.toString()) }
                        if (pictureId == null) append("content", content) else {
                            append("comment", content.ifBlank { "图片评论" })
                            append("pic_id", pictureId)
                            append("is_repost", "0")
                            append("comment_ori", "0")
                            append("is_comment", "0")
                        }
                        append("st", credentials.token)
                    }
                ) {
                    header("Cookie", credentials.cookieHeader)
                    header("X-XSRF-TOKEN", credentials.token)
                    header("Origin", if (pictureId == null) "https://m.weibo.cn" else "https://weibo.com")
                    header("Referer", if (pictureId == null) "https://m.weibo.cn/detail/$id" else "https://weibo.com/")
                    header("MWeibo-Pwa", "1")
                    header("X-Requested-With", "XMLHttpRequest")
                }
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

    /** 附图走网页评论协议，使用网页域自己的 XSRF Cookie，不混用移动端 st。 */
    private suspend fun desktopReplyCredentials(base: WeiboWriteCredentials): WeiboWriteCredentials {
        val cookies = linkedMapOf<String, String>()
        base.cookieHeader.split(';').forEach { part ->
            val name = part.substringBefore('=').trim()
            if (name.isNotEmpty() && '=' in part) cookies[name] = part.substringAfter('=').trim()
        }
        if (cookies["XSRF-TOKEN"].isNullOrBlank()) {
            val response = client.get("https://weibo.com/") {
                header("Cookie", base.cookieHeader)
                header("Referer", "https://weibo.com/")
            }
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
            ?: throw WriteGateException(401, "无法获取微博网页发布凭据，请重新登录后重试；所选图片已保留")
        return base.copy(cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
            token = csrf.decodeURLQueryComponent())
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
        val respJson = KtorClientFactory.jsonConfig
            .parseToJsonElement(response.bodyAsText())
            .jsonObject
        if (writeOkFlag(respJson) == 1) return ApiResult.Success(success)
        return writeJsonError(respJson, failureMessage)
    }

    /** 与 [readWriteResponse] 同一套失败处理，成功时带回 `data` 里新建的评论（可能缺省）。 */
    private suspend fun readCreatedComment(
        response: HttpResponse,
        failureMessage: String
    ): ApiResult<org.kazusa.matter.data.model.WeiboCommentDto?> {
        writeHttpError(response)?.let { return it }
        val respJson = KtorClientFactory.jsonConfig
            .parseToJsonElement(response.bodyAsText())
            .jsonObject
        if (writeOkFlag(respJson) != 1) return writeJsonError(respJson, failureMessage)
        val created = (respJson["data"] as? JsonObject)?.let { data ->
            // 新建评论的字段与服务端列表不完全一致，解析失败只当作「没带回评论」，
            // 不能把一次已经成功的写入报成失败；没有 id 的对象直接丢弃，
            // 否则会在列表里插进一条点不开、也不属于任何人的空评论
            runCatching {
                KtorClientFactory.jsonConfig
                    .decodeFromJsonElement<org.kazusa.matter.data.model.WeiboCommentDto>(data)
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
        return ApiResult.Error(message = msg)
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
     * 取写操作凭据（`st` + 配套 Cookie）。
     *
     * `st` 与下发它的 Cookie 必须成对使用，因此由 [WeiboWriteSession] 一起缓存：
     * 原先每次点赞都要先打一次 `/api/config`，写路径请求量翻倍，是最容易触发
     * 「操作频繁」的地方。
     */
    private suspend fun fetchWriteCredentials(
        cookie: String,
        action: String
    ): WeiboWriteCredentials {
        val cookies = parseCookieHeader(cookie)
        val bootstrap = client.get("https://m.weibo.cn/api/config") {
            header("Cookie", cookie)
            header("Referer", "https://m.weibo.cn/")
        }
        if (bootstrap.status == HttpStatusCode.TooManyRequests) {
            throw WriteGateException(
                WeiboWriteErrors.CODE_RATE_LIMITED,
                WeiboWriteErrors.RATE_LIMITED_MESSAGE
            )
        }
        if (bootstrap.status != HttpStatusCode.OK) {
            throw WriteGateException(
                bootstrap.status.value,
                "获取${action}凭据失败：HTTP ${bootstrap.status.value}"
            )
        }
        val configElement = KtorClientFactory.jsonConfig
            .parseToJsonElement(bootstrap.bodyAsText())
            .jsonObject
        val configData = configElement["data"]?.jsonObject
        if (configData?.get("login")?.jsonPrimitive?.booleanOrNull != true) {
            // /api/config 自身被限流时同样返回 ok=0 + 频控文案，
            // 不能误报成「登录已失效」，否则用户会去重新登录而不是等待
            if (WeiboWriteErrors.isRateLimited(configElement["msg"]?.jsonPrimitive?.contentOrNull)) {
                throw WriteGateException(
                    WeiboWriteErrors.CODE_RATE_LIMITED,
                    WeiboWriteErrors.RATE_LIMITED_MESSAGE
                )
            }
            throw WriteGateException(401, "登录已失效，请重新登录后$action")
        }
        bootstrap.headers.getAll("Set-Cookie").orEmpty().forEach { value ->
            val pair = value.substringBefore(';')
            if ('=' in pair) cookies[pair.substringBefore('=').trim()] = pair.substringAfter('=').trim()
        }
        val token = configData["st"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw WriteGateException(401, "无法获取${action}凭据，请重新登录")
        return WeiboWriteCredentials(
            sourceCookie = cookie,
            cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" },
            token = token
        )
    }


    /** 关注用户。端点: POST https://m.weibo.cn/api/friendships/create */
    suspend fun followUser(uid: String, cookie: String = ""): ApiResult<Unit> =
        updateFollowState(uid, follow = true, cookie = cookie)

    /** 取消关注。端点: POST https://m.weibo.cn/api/friendships/destroy */
    suspend fun unfollowUser(uid: String, cookie: String = ""): ApiResult<Unit> =
        updateFollowState(uid, follow = false, cookie = cookie)

    /**
     * 关注 / 取关共用实现。
     *
     * 移动端写操作需要 `st` 令牌（由 `/api/config` 下发）并同步 Set-Cookie；
     * 与 [setAttitude] 共用同一个 [WeiboWriteSession]，因此凭据缓存、串行提交和
     * 频控冷却对三者一致。
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
            val effectiveCookie = getEffectiveCookie(cookie)
            if (effectiveCookie.isBlank()) {
                return ApiResult.Error(code = 401, message = "未登录或登录已失效，请先登录")
            }
            val path = if (follow) "create" else "destroy"
            writeSession.execute(
                cookie = effectiveCookie,
                action = if (follow) "关注" else "取消关注"
            ) { credentials ->
                val response = client.submitForm(
                    url = "https://m.weibo.cn/api/friendships/$path",
                    formParameters = parameters {
                        append("uid", targetUid)
                        append("st", credentials.token)
                    }
                ) {
                    header("Cookie", credentials.cookieHeader)
                    header("X-XSRF-TOKEN", credentials.token)
                    header("Origin", "https://m.weibo.cn")
                    header("Referer", "https://m.weibo.cn/u/$targetUid")
                    header("X-Requested-With", "XMLHttpRequest")
                }
                readWriteResponse(
                    response = response,
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
     * 端点: https://m.weibo.cn/api/container/getIndex?containerid=100505{uid}
     * 兜底: https://weibo.com/ajax/profile/info?uid={uid}
     */
    suspend fun getUserProfile(uid: String, cookie: String = ""): ApiResult<org.kazusa.matter.data.model.WeiboUserDto> {
        return try {
            val cleanUid = uid.removePrefix("name:").removePrefix("@").trim()
            if (cleanUid.isBlank()) return ApiResult.Error(message = "博主名称不能为空")
            val isNumeric = !uid.startsWith("name:") && cleanUid.all { it.isDigit() }
            var effectiveCookie = getEffectiveCookie(cookie)
            val numericUid = if (isNumeric) cleanUid else when (val resolved = resolveMentionUserId(cleanUid, effectiveCookie)) {
                is ApiResult.Success -> resolved.data
                is ApiResult.Error -> return resolved
                ApiResult.Loading -> return ApiResult.Error(message = "博主资料暂不可用，请重试")
            }

            val targetUid = numericUid

            // 1. 尝试 m.weibo.cn 移动端接口
            if (targetUid.all { it.isDigit() }) {
                try {
                    val containerId = "100505$targetUid"
                    var response = client.get("https://m.weibo.cn/api/container/getIndex") {
                        parameter("containerid", containerId)
                        header("Referer", "https://m.weibo.cn/u/$targetUid")
                        header("MWeibo-Pwa", "1")
                        header("X-Requested-With", "XMLHttpRequest")
                        if (effectiveCookie.isNotBlank()) header("Cookie", effectiveCookie)
                    }
                    if (response.status.value == 432) {
                        cachedVisitorCookie = null
                        val fresh = fetchVisitorCookie()
                        if (fresh.isNotBlank()) {
                            cachedVisitorCookie = fresh
                            effectiveCookie = fresh
                            response = client.get("https://m.weibo.cn/api/container/getIndex") {
                                parameter("containerid", containerId)
                                header("Referer", "https://m.weibo.cn/u/$targetUid")
                                header("MWeibo-Pwa", "1")
                                header("X-Requested-With", "XMLHttpRequest")
                                header("Cookie", effectiveCookie)
                            }
                        }
                    }
                    if (response.status == HttpStatusCode.OK) {
                        val rawText = response.bodyAsText()
                        val cardResp = KtorClientFactory.jsonConfig.decodeFromString<org.kazusa.matter.data.model.WeiboCardResponse>(rawText)
                        if (cardResp.ok == 1) {
                            var user: org.kazusa.matter.data.model.WeiboUserDto? = cardResp.data?.userInfo
                            if (user == null) {
                                cardResp.data?.cards?.forEach { card ->
                                    if (user == null) {
                                        card.mblog?.user?.let { user = it }
                                        card.cardGroup.forEach { item ->
                                            item.mblog?.user?.let { user = it }
                                        }
                                    }
                                }
                            }
                            if (user != null) {
                                return ApiResult.Success(user)
                            }
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    android.util.Log.w("WeiboApi", "m.weibo.cn getUserProfile failed for $targetUid, trying PC fallback", e)
                }
            }

            // 2. 备用接口：weibo.com PC 端 ajax 接口（对于无需登录即可查看资料的账号极度稳定）
            if (targetUid.all { it.isDigit() }) {
                try {
                    val pcResponse = client.get("https://weibo.com/ajax/profile/info") {
                        parameter("uid", targetUid)
                        header("Referer", "https://weibo.com/u/$targetUid")
                        header("Accept", "application/json, text/plain, */*")
                        header("X-Requested-With", "XMLHttpRequest")
                        header("client-version", "v2.44.75")
                        header("server-version", "v2024.05.24.1")
                        if (effectiveCookie.isNotBlank()) header("Cookie", effectiveCookie)
                    }
                    if (pcResponse.status == HttpStatusCode.OK) {
                        val pcText = pcResponse.bodyAsText()
                        val rootJson = KtorClientFactory.jsonConfig.parseToJsonElement(pcText).jsonObject
                        val ok = rootJson["ok"]?.jsonPrimitive?.content?.toIntOrNull()
                        if (ok == 1) {
                            val userObj = rootJson["data"]?.jsonObject?.get("user")
                            if (userObj != null) {
                                val userDto = KtorClientFactory.jsonConfig.decodeFromJsonElement<org.kazusa.matter.data.model.WeiboUserDto>(userObj)
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

            // 3. 兜底返回基础博主信息
            ApiResult.Success(
                org.kazusa.matter.data.model.WeiboUserDto(
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
     * 移动端 `100505{uid}` 的 userInfo 只有昵称/头像/粉丝数等基础字段；
     * 生日、星座、IP 属地、注册时间、教育/职业、阳光信用、动态标签只在这里下发。
     * 该接口依赖登录态，失败时由调用方静默降级，不影响主页主体内容。
     */
    suspend fun getUserProfileDetail(uid: String, cookie: String = ""): ApiResult<UserProfileDetail> {
        return try {
            if (!uid.all { it.isDigit() }) {
                return ApiResult.Error(message = "仅支持数字 UID 获取详细资料")
            }
            var effectiveCookie = getEffectiveCookie(cookie)

            suspend fun request(url: String): io.ktor.client.statement.HttpResponse =
                client.get(url) {
                    parameter("uid", uid)
                    header("Referer", "https://weibo.com/u/$uid")
                    header("Accept", "application/json, text/plain, */*")
                    header("X-Requested-With", "XMLHttpRequest")
                    header("client-version", "v2.44.75")
                    header("server-version", "v2024.05.24.1")
                    if (effectiveCookie.isNotBlank()) header("Cookie", effectiveCookie)
                }

            suspend fun fetchData(url: String): JsonObject? {
                var response = request(url)
                if (response.status.value == 432) {
                    // 游客凭据过期：换一张再试一次
                    cachedVisitorCookie = null
                    val fresh = fetchVisitorCookie()
                    if (fresh.isNotBlank()) {
                        cachedVisitorCookie = fresh
                        effectiveCookie = fresh
                        response = request(url)
                    }
                }
                if (response.status != HttpStatusCode.OK) return null
                val root = KtorClientFactory.jsonConfig
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject
                val ok = (root["ok"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
                if (ok != 1) return null
                return root["data"] as? JsonObject
            }

            // 1. 详细资料（生日、教育、职业、阳光信用、动态标签等）
            val detailData = fetchData("https://weibo.com/ajax/profile/detail")
            if (detailData != null) {
                val detail = parseUserProfileDetail(detailData)
                if (detail.hasProfileInfo) return ApiResult.Success(detail)
            }

            // 2. 兜底 profile/info 的 user 节点，至少补齐所在地/性别
            val infoUser = fetchData("https://weibo.com/ajax/profile/info")?.get("user") as? JsonObject
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
            var effectiveCookie = getEffectiveCookie(cookie)

            suspend fun request(): io.ktor.client.statement.HttpResponse =
                client.get("https://weibo.com/ajax/profile/getImageWall") {
                    parameter("uid", uid)
                    parameter("sinceid", "0")
                    parameter("has_album", "true")
                    header("Referer", "https://weibo.com/u/$uid?tabtype=album")
                    header("Accept", "application/json, text/plain, */*")
                    header("X-Requested-With", "XMLHttpRequest")
                    if (effectiveCookie.isNotBlank()) header("Cookie", effectiveCookie)
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
                return ApiResult.Error(code = response.status.value, message = "获取相册失败 (HTTP ${response.status.value})")
            }

            val root = KtorClientFactory.jsonConfig
                .parseToJsonElement(response.bodyAsText())
                .jsonObject
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
            var effectiveCookie = getEffectiveCookie(cookie)

            suspend fun request(): io.ktor.client.statement.HttpResponse =
                client.get("https://weibo.com/ajax/profile/getAlbumDetail") {
                    parameter("containerid", containerId)
                    parameter("since_id", sinceId)
                    header("Referer", "https://weibo.com/")
                    header("Accept", "application/json, text/plain, */*")
                    header("X-Requested-With", "XMLHttpRequest")
                    if (effectiveCookie.isNotBlank()) header("Cookie", effectiveCookie)
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
                return ApiResult.Error(code = response.status.value, message = "获取相册图片失败 (HTTP ${response.status.value})")
            }

            val root = KtorClientFactory.jsonConfig
                .parseToJsonElement(response.bodyAsText())
                .jsonObject
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
            var effectiveCookie = getEffectiveCookie(cookie)

            suspend fun request(): io.ktor.client.statement.HttpResponse =
                client.get("https://weibo.com/ajax/profile/getWaterFallContent") {
                    parameter("uid", uid)
                    parameter("cursor", cursor)
                    header("Referer", "https://weibo.com/u/$uid?tabtype=newVideo")
                    header("Accept", "application/json, text/plain, */*")
                    header("X-Requested-With", "XMLHttpRequest")
                    if (effectiveCookie.isNotBlank()) header("Cookie", effectiveCookie)
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
                return ApiResult.Error(code = response.status.value, message = "获取视频失败 (HTTP ${response.status.value})")
            }

            val root = KtorClientFactory.jsonConfig
                .parseToJsonElement(response.bodyAsText())
                .jsonObject
            val ok = (root["ok"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
            if (ok != 1) {
                val msg = (root["msg"] as? JsonPrimitive)?.contentOrNull
                    ?: (root["message"] as? JsonPrimitive)?.contentOrNull
                    ?: "获取视频失败"
                return ApiResult.Error(message = msg)
            }

            val data = root["data"] as? JsonObject
                ?: return ApiResult.Error(message = "视频数据为空")
            val videos = (data["list"] as? JsonArray).orEmpty().mapNotNull { element ->
                runCatching {
                    KtorClientFactory.jsonConfig
                        .decodeFromJsonElement<WeiboStatusDto>(element)
                }.getOrNull()
            }.distinctBy { it.listKey }

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
            var effectiveCookie = getEffectiveCookie(cookie)

            suspend fun request(): io.ktor.client.statement.HttpResponse =
                client.get("https://m.weibo.cn/api/container/getIndex") {
                    parameter("type", "uid")
                    parameter("value", uid)
                    parameter("containerid", "230413${uid}_-_WEIBO_SECOND_PROFILE_WEIBO_ARTICAL")
                    parameter("page", page)
                    header("Referer", "https://m.weibo.cn/u/$uid")
                    header("MWeibo-Pwa", "1")
                    header("X-Requested-With", "XMLHttpRequest")
                    if (effectiveCookie.isNotBlank()) header("Cookie", effectiveCookie)
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
                return ApiResult.Error(code = response.status.value, message = "获取文章失败 (HTTP ${response.status.value})")
            }

            val cardResp = KtorClientFactory.jsonConfig
                .decodeFromString<WeiboCardResponse>(response.bodyAsText())
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
     * 获取博主发布的微博时间线
     * 端点: https://m.weibo.cn/api/container/getIndex?type=uid&value={uid}&containerid=107603{uid}&page={page}
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

            var effectiveCookie = getEffectiveCookie(cookie)
            var response = client.get("https://m.weibo.cn/api/container/getIndex") {
                parameter("type", "uid")
                parameter("value", uid)
                parameter("containerid", "107603$uid")
                parameter("page", page)
                header("Referer", "https://m.weibo.cn/u/$uid")
                header("MWeibo-Pwa", "1")
                header("X-Requested-With", "XMLHttpRequest")
                if (effectiveCookie.isNotBlank()) header("Cookie", effectiveCookie)
            }
            if (response.status.value == 432) {
                cachedVisitorCookie = null
                val fresh = fetchVisitorCookie()
                if (fresh.isNotBlank()) {
                    cachedVisitorCookie = fresh
                    effectiveCookie = fresh
                    response = client.get("https://m.weibo.cn/api/container/getIndex") {
                        parameter("type", "uid")
                        parameter("value", uid)
                        parameter("containerid", "107603$uid")
                        parameter("page", page)
                        header("Referer", "https://m.weibo.cn/u/$uid")
                        header("MWeibo-Pwa", "1")
                        header("X-Requested-With", "XMLHttpRequest")
                        header("Cookie", effectiveCookie)
                    }
                }
            }
            if (response.status == HttpStatusCode.OK) {
                val rawText = response.bodyAsText()
                val cardResp = KtorClientFactory.jsonConfig.decodeFromString<org.kazusa.matter.data.model.WeiboCardResponse>(rawText)
                if (cardResp.ok != 1) {
                    val errorMsg = if (cardResp.ok == -100) {
                        "该博主内容需要登录微博账号后方可查看 (ok: -100)"
                    } else {
                        "微博返回异常状态 (ok: ${cardResp.ok})"
                    }
                    return ApiResult.Error(code = cardResp.ok, message = errorMsg)
                }
                val list = mutableListOf<org.kazusa.matter.data.model.WeiboStatusDto>()
                cardResp.data?.cards?.forEach { card ->
                    card.mblog?.let { list.add(it) }
                    card.cardGroup.forEach { item ->
                        item.mblog?.let { list.add(it) }
                    }
                }
                val extractedUser = cardResp.data?.userInfo ?: list.firstOrNull()?.user
                val totalCount = cardResp.data?.cardlistInfo?.total
                ApiResult.Success(
                    UserTimelineResult(
                        user = extractedUser,
                        statuses = list,
                        total = totalCount,
                        hasMore = list.isNotEmpty()
                    )
                )
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
            var response = client.get("https://m.weibo.cn/api/container/getIndex") {
                parameter("containerid", containerId)
                parameter("page", page)
                header("Referer", "https://m.weibo.cn/u/$targetUid")
                header("MWeibo-Pwa", "1")
                header("X-Requested-With", "XMLHttpRequest")
                if (effectiveCookie.isNotBlank()) header("Cookie", effectiveCookie)
            }
            if (response.status.value == 432) {
                cachedVisitorCookie = null
                val fresh = fetchVisitorCookie()
                if (fresh.isNotBlank()) {
                    cachedVisitorCookie = fresh
                    effectiveCookie = fresh
                    response = client.get("https://m.weibo.cn/api/container/getIndex") {
                        parameter("containerid", containerId)
                        parameter("page", page)
                        header("Referer", "https://m.weibo.cn/u/$targetUid")
                        header("MWeibo-Pwa", "1")
                        header("X-Requested-With", "XMLHttpRequest")
                        header("Cookie", effectiveCookie)
                    }
                }
            }
            if (response.status != HttpStatusCode.OK) {
                return ApiResult.Error(
                    code = response.status.value,
                    message = "获取列表失败: HTTP ${response.status}"
                )
            }

            val cardResp = KtorClientFactory.jsonConfig
                .decodeFromString<WeiboCardResponse>(response.bodyAsText())
            if (cardResp.ok != 1) {
                val message = if (cardResp.ok == -100) {
                    "该列表需要登录微博账号后才能查看"
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
