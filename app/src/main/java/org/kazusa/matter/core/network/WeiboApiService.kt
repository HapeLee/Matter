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
import org.kazusa.matter.data.model.HotSearchBoard
import org.kazusa.matter.data.model.HotSearchChannel
import org.kazusa.matter.data.model.WeiboCardResponse
import org.kazusa.matter.data.model.WeiboSearchChannelDto
import org.kazusa.matter.data.model.WeiboSearchTabDto
import org.kazusa.matter.data.model.toHotSearchBoard
import org.kazusa.matter.data.model.WeiboSearchCardInfo
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.model.UserPostsPage
import org.kazusa.matter.data.model.classifySearchCard
import org.kazusa.matter.data.model.toSearchCardInfo
import org.kazusa.matter.data.model.toSearchChipGroupOrNull
import org.kazusa.matter.data.model.SmsCodeResponse
import org.kazusa.matter.data.model.SmsLoginResponse
import org.kazusa.matter.data.model.TimelineResponse
import org.kazusa.matter.data.model.UserTimelineResult
import org.kazusa.matter.data.model.WeiboConfigResponse
import org.kazusa.matter.data.model.WeiboGroupDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive


class WeiboApiService(
    private val client: HttpClient,
    private val authPreferences: AuthPreferences? = null
) {

    private val favoritesApi = FavoritesApi(client)

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
                android.util.Log.d("WeiboAuth", "fetchVisitorCookie success: ${cookie.take(25)}...")
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
            android.util.Log.d("WeiboAuth", "verifyConfig status=${response.status}, preview=${rawText.take(200)}")
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
            android.util.Log.d("TimelineApi", "friends response status=${response.status}, len=${rawText.length}, preview=${rawText.take(200)}")
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
            android.util.Log.d("TimelineApi", "group response status=${response.status}, len=${rawText.length}, preview=${rawText.take(200)}")
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
                    if (resp.ok == 0 || resp.data == null) {
                        ApiResult.Error(message = "评论加载失败，请稍后重试")
                    } else {
                        ApiResult.Success(resp.data)
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
                    if (resp.ok == 0 || resp.data == null) {
                        ApiResult.Error(message = "评论加载失败，请稍后重试")
                    } else {
                        ApiResult.Success(resp.data)
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
                if (resp.ok == 0 || resp.data == null) {
                    ApiResult.Error(message = "转发列表加载失败")
                } else {
                    ApiResult.Success(resp.data)
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
                if (resp.ok == 0 || resp.data == null) {
                    ApiResult.Error(message = "点赞列表加载失败")
                } else {
                    ApiResult.Success(resp.data)
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
        cookie: String = ""
    ): ApiResult<Boolean> {
        return try {
            val effectiveCookie = getEffectiveCookie(cookie)
            if (effectiveCookie.isBlank()) {
                return ApiResult.Error(code = 401, message = "未登录或登录已失效，请先登录")
            }
            val cookies = linkedMapOf<String, String>()
            effectiveCookie.split(';').forEach { part ->
                val name = part.substringBefore('=').trim()
                if (name.isNotEmpty() && '=' in part) cookies[name] = part.substringAfter('=').trim()
            }
            val bootstrap = client.get("https://m.weibo.cn/api/config") {
                header("Cookie", effectiveCookie)
                header("Referer", "https://m.weibo.cn/")
            }
            val rawConfig = bootstrap.bodyAsText()
            val configElement = KtorClientFactory.jsonConfig.parseToJsonElement(rawConfig).jsonObject
            val configData = configElement["data"]?.jsonObject
            if (configData?.get("login")?.jsonPrimitive?.booleanOrNull != true) {
                return ApiResult.Error(code = 401, message = "登录已失效，请重新登录后点赞")
            }
            bootstrap.headers.getAll("Set-Cookie").orEmpty().forEach { value ->
                val pair = value.substringBefore(';')
                if ('=' in pair) cookies[pair.substringBefore('=').trim()] = pair.substringAfter('=').trim()
            }
            val token = configData["st"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return ApiResult.Error(code = 401, message = "无法获取点赞凭据，请重新登录")

            val path = if (isLike) "create" else "destroy"
            val response = client.submitForm(
                url = "https://m.weibo.cn/api/attitudes/$path",
                formParameters = parameters {
                    append("id", id)
                    append("attitude", "heart")
                    append("st", token)
                }
            ) {
                header("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
                header("X-XSRF-TOKEN", token)
                header("Origin", "https://m.weibo.cn")
                header("Referer", "https://m.weibo.cn/detail/$id")
                header("X-Requested-With", "XMLHttpRequest")
            }
            if (response.status == HttpStatusCode.OK) {
                val respText = response.bodyAsText()
                val respJson = KtorClientFactory.jsonConfig.parseToJsonElement(respText).jsonObject
                val ok = respJson["ok"]?.jsonPrimitive?.intOrNull
                    ?: (if (respJson["ok"]?.jsonPrimitive?.content == "1") 1 else 0)
                if (ok == 1) {
                    ApiResult.Success(isLike)
                } else {
                    val msg = respJson["msg"]?.jsonPrimitive?.contentOrNull
                        ?: respJson["message"]?.jsonPrimitive?.contentOrNull
                        ?: (if (isLike) "点赞失败" else "取消点赞失败")
                    ApiResult.Error(message = msg)
                }
            } else {
                ApiResult.Error(code = response.status.value, message = "HTTP ${response.status}")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("WeiboApi", "setAttitude failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "点赞操作异常", throwable = e)
        }
    }


    /**
     * 获取博主资料卡片
     * 端点: https://m.weibo.cn/api/container/getIndex?containerid=100505{uid}
     * 兜底: https://weibo.com/ajax/profile/info?uid={uid}
     */
    suspend fun getUserProfile(uid: String, cookie: String = ""): ApiResult<org.kazusa.matter.data.model.WeiboUserDto> {
        return try {
            val isNumeric = uid.all { it.isDigit() }
            val cleanUid = if (isNumeric) uid else uid.removePrefix("name:")
            var effectiveCookie = getEffectiveCookie(cookie)

            // 如果不是数字 UID（如来自 @mention 的昵称），先通过搜索获取该用户的真实数字 UID
            var numericUid = if (isNumeric) uid else null
            if (numericUid == null) {
                val searchRes = searchWeibo(cleanUid, page = 1, cookie = cookie)
                if (searchRes is ApiResult.Success) {
                    val matching = searchRes.data.firstOrNull { it.user?.screenName == cleanUid }?.user
                        ?: searchRes.data.firstOrNull()?.user
                    if (matching?.id != null) {
                        numericUid = matching.id.toString()
                    } else if (matching != null) {
                        return ApiResult.Success(matching)
                    }
                }
            }

            val targetUid = numericUid ?: cleanUid

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
        } catch (e: Exception) {
            android.util.Log.e("WeiboApi", "getUserProfile failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "加载博主资料异常", throwable = e)
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
            val isNumericUid = uid.all { it.isDigit() }
            if (!isNumericUid) {
                // 如果传入的是博主昵称而非数字 uid，优先通过搜索该昵称获取博主发布的内容与博主信息
                val cleanName = uid.removePrefix("name:")
                val searchRes = searchWeibo(cleanName, page = page, cookie = cookie)
                if (searchRes is ApiResult.Success) {
                    val matchingUser = searchRes.data.firstOrNull { it.user?.screenName == cleanName }?.user
                        ?: searchRes.data.firstOrNull()?.user
                    if (matchingUser?.id != null && page == 1) {
                        val realTimeline = getUserTimeline(matchingUser.id.toString(), page = 1, cookie = cookie)
                        if (realTimeline is ApiResult.Success && realTimeline.data.statuses.isNotEmpty()) {
                            return realTimeline
                        }
                    }
                    return ApiResult.Success(
                        UserTimelineResult(
                            user = matchingUser ?: org.kazusa.matter.data.model.WeiboUserDto(screenName = cleanName),
                            statuses = searchRes.data,
                            total = searchRes.data.size,
                            hasMore = searchRes.data.isNotEmpty()
                        )
                    )
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
        } catch (e: Exception) {
            android.util.Log.e("WeiboApi", "getUserTimeline failed", e)
            ApiResult.Error(message = e.localizedMessage ?: "加载博主动态异常", throwable = e)
        }
    }
}
