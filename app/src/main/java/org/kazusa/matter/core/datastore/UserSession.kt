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
