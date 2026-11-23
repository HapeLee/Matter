package org.kazusa.matter.core.datastore

import kotlinx.serialization.Serializable

@Serializable
data class UserSession(
    val uid: String = "",
    val sub: String = "",
    val subp: String = "",
    val rawCookie: String = "",
    val screenName: String = "",
    val avatarUrl: String = "",
    val description: String = "",
    val followersCount: String = "",
    val friendsCount: String = "",
    val statusesCount: String = "",
    val isLoggedIn: Boolean = false
) {
    val displayFollowersCount: String
        get() = followersCount.ifBlank { "--" }

    val displayFriendsCount: String
        get() = friendsCount.ifBlank { "--" }

    val displayStatusesCount: String
        get() = statusesCount.ifBlank { "--" }

    /**
     * 会话硬过期时间（Unix 秒），来自 Cookie 里的 `ALF`。
     *
     * 实测（2026-11-23，真实账号）：`ALF` 减去 `SSOLoginState` 恰好 **30 天整**；
     * 且 `/api/config`、`weibo.com/`、内容接口都不会通过 `Set-Cookie` 把它往后延。
     * 也就是说登录态是**30 天硬过期**，服务端不给续期机会——这是「用一阵子就掉
     * 登录」最可能的解释，而不是轮换或风控。
     *
     * 解析不出时返回 null（手动导入的残缺 Cookie、旧版本会话），调用方不得据此判失效。
     */
    val sessionExpiresAt: Long?
        get() = ALF_PATTERN.find(rawCookie)?.groupValues?.get(1)?.toLongOrNull()

    /** 距过期还有几天；null 表示本地没有过期信息。负数表示已过期。 */
    fun sessionDaysRemaining(nowEpochSeconds: Long): Long? =
        sessionExpiresAt?.let { (it - nowEpochSeconds) / SECONDS_PER_DAY }

    private companion object {
        /** `ALF=1798028528`：前一项可能是别的 Cookie，用 `;` 或串首界定。 */
        val ALF_PATTERN = Regex("(?:^|;)\\s*ALF=(\\d+)")
        const val SECONDS_PER_DAY = 86_400L
    }

    /**
     * 拼接好的 Cookie 头。
     *
     * 用 `by lazy` 固定：这个值每个请求都要读，而计算过程是「整串 split → 建 map → 再 join」，
     * 放在 getter 里等于每次请求都重新分配一遍字符串（会话对象本身是不可变的，缓存安全）。
     */
    val cookieHeader: String by lazy {
        if (rawCookie.isNotBlank()) {
            // 如果已持久化 sub/subp 凭据，确保 cookieHeader 中的 SUB 与 SUBP 以权威凭据为准，剔除过期的访客或冲突 Cookie
            val cookieMap = linkedMapOf<String, String>()
            rawCookie.split(";").forEach { part ->
                val trimmed = part.trim()
                val eqIdx = trimmed.indexOf("=")
                if (eqIdx > 0) {
                    val k = trimmed.substring(0, eqIdx).trim()
                    val v = trimmed.substring(eqIdx + 1).trim()
                    if (k.isNotBlank()) {
                        cookieMap[k] = v
                    }
                }
            }
            if (sub.isNotBlank()) cookieMap["SUB"] = sub
            if (subp.isNotBlank()) cookieMap["SUBP"] = subp
            cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
        } else {
            val parts = mutableListOf<String>()
            if (sub.isNotBlank()) parts.add("SUB=$sub")
            if (subp.isNotBlank()) parts.add("SUBP=$subp")
            parts.joinToString("; ")
        }
    }
}
