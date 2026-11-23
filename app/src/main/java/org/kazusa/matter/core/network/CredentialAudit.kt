package org.kazusa.matter.core.network

import org.kazusa.matter.BuildConfig
import java.security.MessageDigest

/**
 * 凭据生命周期审计（仅 debug 构建）。
 *
 * 存在的理由：现在「掉登录」只有一句「登录已过期」，三条成因（服务端轮换 / 探活误判 /
 * 本地凭据读空）在日志里长得一模一样，无法判断该修哪一条。这里把判定依据原样记下来，
 * 跑一周 `adb logcat -s CredAuthAudit:D` 就能看出比例：
 *
 * - `set_cookie` 高频且 `changed=true` → 服务端确实在轮换，落盘是必需的；
 * - `set_cookie` 从不出现 → 轮换不存在，掉线就是别的通道；
 * - `probe` 里 `login=false` 多于 `suspend` → 复核挡住了误判；
 * - `keys=` 与浏览器对照缺项 → 本地凭据集合本身不完整。
 *
 * **不记录任何凭据值**：只记长度 + 前 8 位 SHA-256（指纹）与 Cookie 的键名列表，
 * 满足 docs/credential-auth-review.md T2「logcat 无 SUB / st / user_token」的约束。
 */
internal object CredentialAudit {

    private const val TAG = "CredAuthAudit"

    fun log(event: String, vararg fields: Pair<String, Any?>) {
        if (!BuildConfig.DEBUG) return
        val line = buildString {
            append(event)
            fields.forEach { (key, value) ->
                append(' ').append(key).append('=').append(value ?: "-")
            }
        }
        android.util.Log.d(TAG, line)
    }

    /** 凭据指纹：跨天对比同一个 SUB 有没有变，同时不把值写进日志。 */
    fun fingerprint(value: String): String {
        if (value.isBlank()) return "empty"
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        val hex = digest.take(4).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
        return "len${value.length}:$hex"
    }

    /**
     * Cookie 的键名列表（不含值）。用来和浏览器 DevTools 里的 Cookie 表逐项对照，
     * 判断本地凭据集合是不是缺了服务端要求的配套项。
     */
    fun cookieKeys(cookieHeader: String): String =
        cookieHeader.split(';')
            .mapNotNull { part -> part.substringBefore('=').trim().takeIf { it.isNotEmpty() } }
            .distinct()
            .sorted()
            .joinToString(",")
            .ifBlank { "none" }
}
