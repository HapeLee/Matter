package org.kazusa.matter.data.repository

/**
 * 把登录页从多个域名拼出来的 Cookie 池收敛成单一规范串：
 * - 同名 Cookie 以先出现者为准（cookiePool 按域名优先级顺序拼接，m.weibo.cn 最前）；
 * - 值为空或 "" 的条目剔除（登录页未生效的 SUB 会被写成 SUB=""）；
 * - 值里的 '=' 原样保留（凭据值本身可能含 '='）。
 *
 * 不去重时池里可能同时出现两个账号的 SUB，服务端按第一个取值，
 * 造成「验证成 A 账号、把 A 的凭据写成权威数据」的串号。
 */
object AuthCookieSanitizer {

    fun canonicalize(cookieString: String): String {
        val seen = linkedMapOf<String, String>()
        cookieString.split(";").forEach { item ->
            val trimmed = item.trim()
            if (trimmed.isEmpty()) return@forEach
            val eqIdx = trimmed.indexOf("=")
            if (eqIdx <= 0) return@forEach
            val key = trimmed.substring(0, eqIdx).trim()
            val value = trimmed.substring(eqIdx + 1).trim()
            if (key.isBlank() || value.isEmpty() || value == "\"\"") return@forEach
            if (!seen.containsKey(key)) seen[key] = value
        }
        return seen.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }
}
