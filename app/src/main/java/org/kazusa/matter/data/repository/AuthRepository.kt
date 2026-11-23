package org.kazusa.matter.data.repository

import org.kazusa.matter.core.datastore.AuthPreferences
import org.kazusa.matter.core.datastore.LastSessionExpiry
import org.kazusa.matter.core.datastore.UserSession
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.CredentialAudit
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.core.session.SessionExpiryReason
import org.kazusa.matter.core.session.WebSessionCookieStore
import org.kazusa.matter.data.model.WeiboUserDto
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull

/** 会话探活结果：只有 [SessionProbe.EXPIRED] 表示服务端明确判定凭据失效。 */
enum class SessionProbe { SKIP, VALID, EXPIRED, NETWORK_ERROR }

class AuthRepository(
    private val authPreferences: AuthPreferences,
    private val apiService: WeiboApiService,
    private val webCookieStore: WebSessionCookieStore,
    /** 探活首次被判失效后的复核间隔；测试注入 0，避免真实等待。 */
    private val probeConfirmDelayMs: Long = PROBE_CONFIRM_DELAY_MS
) {

    val sessionFlow: Flow<UserSession> = authPreferences.sessionFlow
    val isOnboardingCompletedFlow: Flow<Boolean> = authPreferences.isOnboardingCompletedFlow
    val lastGroupFlow: Flow<Pair<String?, String?>> = authPreferences.lastGroupFlow

    suspend fun saveSelectedGroup(gid: String?, groupName: String?) {
        authPreferences.saveSelectedGroup(gid, groupName)
    }

    suspend fun completeOnboarding() {
        authPreferences.setOnboardingCompleted()
    }

    /**
     * 解析完整的Cookie字符串（如包含 SUB, SUBP, _T_WM 等）
     */
    fun parseCookie(cookieString: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        cookieString.split(";").forEach { item ->
            val parts = item.trim().split("=")
            if (parts.size >= 2) {
                val key = parts[0].trim()
                val value = parts.subList(1, parts.size).joinToString("=").trim()
                map[key] = value
            }
        }
        return map
    }

    /**
     * 通过Cookie或Token进行登录并拉取用户信息校验
     */
    suspend fun loginWithCookie(cookieString: String): ApiResult<UserSession> {
        // 多域 Cookie 池先按「首个非空值生效」去重：不处理的话两个账号的 SUB 会同头
        // 发送，服务端按第一个取值，验证与保存的就可能是两个不同的账号。
        // 验证（verifyConfig）与保存（saveSession）必须用同一份去重结果。
        val formattedCookie = AuthCookieSanitizer.canonicalize(cookieString)
        val cookies = parseCookie(formattedCookie)
        val sub = cookies["SUB"].orEmpty()
        val subp = cookies["SUBP"].orEmpty()

        return when (val result = apiService.verifyConfig(formattedCookie)) {
            is ApiResult.Success -> {
                val config = result.data
                val configUser = config.data?.user
                val uid = configUser?.id?.toString() ?: config.data?.uid.orEmpty().ifBlank { cookies["uid"].orEmpty() }

                // 重要：m.weibo.cn/api/config 只返回 login/uid/st/user_token，不含任何用户资料，
                // 所以必须再按 uid 请求一次资料接口，才能拿到昵称、头像与个性签名。
                val profile = fetchProfileSafely(uid, formattedCookie)

                val screenName = profile?.screenName?.ifBlank { null }
                    ?: configUser?.screenName?.ifBlank { null }
                    ?: "微博用户"
                val avatarUrl = profile?.avatarHd ?: profile?.avatarLarge ?: profile?.profileImageUrl
                    ?: configUser?.avatarHd ?: configUser?.avatarLarge ?: configUser?.profileImageUrl
                    ?: ""
                val description = profile?.description.orEmpty()
                val followersCount = profile?.followersCount.orEmpty()
                    .ifBlank { configUser?.followersCount.orEmpty() }
                val friendsCount = profile?.friendsCount.orEmpty()
                    .ifBlank { profile?.followCount.orEmpty() }
                    .ifBlank { configUser?.friendsCount.orEmpty().ifBlank { configUser?.followCount.orEmpty() } }
                val statusesCount = profile?.statusesCount.orEmpty()
                    .ifBlank { configUser?.statusesCount.orEmpty() }

                authPreferences.saveSession(
                    uid = uid,
                    sub = sub,
                    subp = subp,
                    rawCookie = formattedCookie,
                    screenName = screenName,
                    avatarUrl = avatarUrl,
                    description = description,
                    followersCount = followersCount,
                    friendsCount = friendsCount,
                    statusesCount = statusesCount
                )
                ApiResult.Success(
                    UserSession(
                        uid = uid,
                        sub = sub,
                        subp = subp,
                        rawCookie = formattedCookie,
                        screenName = screenName,
                        avatarUrl = avatarUrl,
                        description = description,
                        followersCount = followersCount,
                        friendsCount = friendsCount,
                        statusesCount = statusesCount,
                        isLoggedIn = true
                    )
                )
            }
            is ApiResult.Error -> {
                ApiResult.Error(code = result.code, message = result.message)
            }
            ApiResult.Loading -> ApiResult.Loading
        }
    }

    /**
     * 拉取并刷新本地缓存的当前用户资料（昵称 / 头像 / 个性签名）。
     *
     * 之所以需要它：`m.weibo.cn/api/config` 只返回 `login/uid/st/user_token`，
     * 不含 `user` 字段，导致登录时昵称只能回退成「微博用户」、头像为空。
     * 进入个人中心时调用本方法即可自愈（也能兼容历史版本遗留的旧缓存）。
     *
     * @return 刷新后的会话；未登录、游客模式或资料请求失败时返回 null。
     */
    suspend fun refreshProfile(): UserSession? {
        val current = authPreferences.sessionFlow.firstOrNull() ?: return null
        if (current.uid.isBlank() || current.uid == "guest") return null

        val profile = fetchProfileSafely(current.uid, current.cookieHeader) ?: return null

        val screenName = profile.screenName.ifBlank { current.screenName }
        val avatarUrl = profile.avatarHd ?: profile.avatarLarge ?: profile.profileImageUrl ?: current.avatarUrl
        val description = profile.description.orEmpty()
        val followersCount = profile.followersCount.ifBlank { current.followersCount }
        val friendsCount = profile.friendsCount.ifBlank { profile.followCount }
            .ifBlank { current.friendsCount }
        val statusesCount = profile.statusesCount.ifBlank { current.statusesCount }

        authPreferences.updateProfile(
            screenName = screenName,
            avatarUrl = avatarUrl,
            description = description,
            followersCount = followersCount,
            friendsCount = friendsCount,
            statusesCount = statusesCount
        )
        return current.copy(
            screenName = screenName,
            avatarUrl = avatarUrl,
            description = description,
            followersCount = followersCount,
            friendsCount = friendsCount,
            statusesCount = statusesCount
        )
    }

    /**
     * 按 uid 请求博主资料，失败或只拿到占位数据时返回 null。
     * `getUserProfile` 在完全取不到资料时会返回「博主 xxx」占位对象，这里需要剔除，
     * 避免把占位昵称写进本地会话。
     */
    private suspend fun fetchProfileSafely(uid: String, cookie: String): WeiboUserDto? {
        if (uid.isBlank() || uid == "guest") return null
        val profile = when (val res = apiService.getUserProfile(uid, cookie)) {
            is ApiResult.Success -> res.data
            else -> return null
        }
        if (profile.screenName.isBlank() || profile.screenName.startsWith("博主 ")) return null
        return profile
    }

    suspend fun saveGuestSession() {
        authPreferences.saveSession(
            uid = "guest",
            sub = "",
            subp = "",
            rawCookie = "",
            screenName = "访客体验用户"
        )
    }

    /**
     * 会话探活：冷启动 / 回前台时用 /api/config 验证一次登录凭据。
     *
     * 三条保守规则，目的都是「宁可保持现状，也不要误判」：
     * 1. 本地凭据读不出来（解密瞬时失败 / 字段缺失）时**直接跳过**。空 Cookie 打
     *    `/api/config` 必然得到 `login=false`，那是本地读取失败的后果，不是服务端的结论。
     * 2. 单次 401 不算数，隔 [probeConfirmDelayMs] 再复核一次，两次都判未登录才算失效。
     *    风控、限流、灰度降级都会让这个接口返回 `login=false`。
     * 3. 复核期间会话被换掉（uid 变化）就以新会话为准，直接放弃本次判断。
     */
    suspend fun probeSession(): SessionProbe {
        val current = authPreferences.sessionFlow.firstOrNull()
            ?: return SessionProbe.SKIP
        if (!current.isLoggedIn || current.uid.isBlank() || current.uid == "guest") {
            return SessionProbe.SKIP
        }
        val cookie = current.cookieHeader
        if (cookie.isBlank()) return SessionProbe.SKIP

        val first = verifySession(cookie)
        if (first != SessionProbe.EXPIRED) {
            CredentialAudit.log("probe", "result" to first)
            return first
        }

        delay(probeConfirmDelayMs)
        val retry = authPreferences.sessionFlow.firstOrNull() ?: return SessionProbe.SKIP
        if (!retry.isLoggedIn || retry.uid != current.uid) {
            CredentialAudit.log("probe", "result" to "ABORT_SESSION_CHANGED")
            return SessionProbe.SKIP
        }
        val retryCookie = retry.cookieHeader
        if (retryCookie.isBlank()) return SessionProbe.SKIP
        val result = if (verifySession(retryCookie) == SessionProbe.EXPIRED) {
            SessionProbe.EXPIRED
        } else {
            // 第一次 401、第二次正常：单次判定会误登出，这条日志就是复核的价值证明
            SessionProbe.VALID
        }
        CredentialAudit.log("probe", "result" to result, "firstWasExpired" to true)
        return result
    }

    private suspend fun verifySession(cookie: String): SessionProbe =
        when (val result = apiService.verifyConfig(cookie)) {
            is ApiResult.Success -> SessionProbe.VALID
            is ApiResult.Error ->
                if (result.code == 401) SessionProbe.EXPIRED else SessionProbe.NETWORK_ERROR
            ApiResult.Loading -> SessionProbe.SKIP
        }

    suspend fun logout() {
        CredentialAudit.log("logout")
        authPreferences.clearSession()
        // 登录 WebView 的 Cookie 库里同样躺着 SUB/SUBP；不清掉的话下次进登录页
        // 会自动抓回旧凭据登回原账号。清理失败也不能阻断本地登出。
        runCatching { webCookieStore.clearAll() }
    }

    /**
     * 服务端判定会话失效（`-100` / 探活连续未通过）。
     *
     * **与 [logout] 的关键区别：不删凭据。** 判定依据只是一次响应，而风控、限流、
     * 游客凭据降级、解密瞬时失败都会给出同样的信号。把「疑似过期」当成「确定作废」，
     * 代价是用户重新走一遍网页授权（含人机验证），而且密文删掉后无法恢复。
     *
     * 保留密文后：重新登录成功会整体覆盖；若这次只是误判，凭据下次仍然可用。
     */
    suspend fun suspendSession(reason: SessionExpiryReason) {
        CredentialAudit.log("session_suspended", "reason" to reason.name)
        authPreferences.markSessionInvalid(reason)
    }

    /** 上一次自动失效的原因与时间；主动登出后为空。 */
    val lastSessionExpiryFlow: Flow<LastSessionExpiry?> = authPreferences.lastSessionExpiryFlow

    private companion object {
        /** 复核间隔：够短以免拖慢启动，够长以让风控 / 限流的瞬态窗口过去。 */
        const val PROBE_CONFIRM_DELAY_MS = 3_000L
    }
}
