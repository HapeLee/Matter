package org.kazusa.matter.data.repository

import org.kazusa.matter.core.datastore.AuthPreferences
import org.kazusa.matter.core.datastore.UserSession
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.data.model.WeiboUserDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull

class AuthRepository(
    private val authPreferences: AuthPreferences,
    private val apiService: WeiboApiService
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

    suspend fun sendSmsCode(phone: String): ApiResult<String> {
        return apiService.sendSmsCode(phone)
    }

    suspend fun loginWithSms(phone: String, smsCode: String): ApiResult<UserSession> {
        return when (val result = apiService.loginWithSms(phone, smsCode)) {
            is ApiResult.Success -> {
                val (uid, cookie) = result.data
                if (cookie.isNotBlank()) {
                    loginWithCookie(cookie)
                } else {
                    val session = UserSession(uid = uid, isLoggedIn = true, screenName = "微博用户")
                    authPreferences.saveSession(uid = uid, screenName = "微博用户")
                    ApiResult.Success(session)
                }
            }
            is ApiResult.Error -> ApiResult.Error(result.code, result.message, result.throwable)
            ApiResult.Loading -> ApiResult.Loading
        }
    }

    suspend fun loginWithPassword(username: String, password: String): ApiResult<UserSession> {
        return when (val result = apiService.loginWithPassword(username, password)) {
            is ApiResult.Success -> {
                val (uid, cookie) = result.data
                if (cookie.isNotBlank()) {
                    loginWithCookie(cookie)
                } else {
                    val session = UserSession(uid = uid, isLoggedIn = true, screenName = username)
                    authPreferences.saveSession(uid = uid, screenName = username)
                    ApiResult.Success(session)
                }
            }
            is ApiResult.Error -> ApiResult.Error(result.code, result.message, result.throwable)
            ApiResult.Loading -> ApiResult.Loading
        }
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
        val cookies = parseCookie(cookieString)
        val sub = cookies["SUB"].orEmpty()
        val subp = cookies["SUBP"].orEmpty()
        // 使用完整的 cookie 字符串，确保保留 _T_WM, SUB, SUBP 等所有关键状态
        val formattedCookie = if (cookieString.contains("SUB=")) cookieString else "SUB=$sub; ${if (subp.isNotBlank()) "SUBP=$subp" else ""}".trim()

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

                authPreferences.saveSession(
                    uid = uid,
                    sub = sub,
                    subp = subp,
                    rawCookie = formattedCookie,
                    screenName = screenName,
                    avatarUrl = avatarUrl,
                    description = description
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

        authPreferences.updateProfile(
            screenName = screenName,
            avatarUrl = avatarUrl,
            description = description
        )
        return current.copy(
            screenName = screenName,
            avatarUrl = avatarUrl,
            description = description
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

    suspend fun logout() {
        authPreferences.clearSession()
    }
}
