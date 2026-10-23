package org.kazusa.matter.core.session

/**
 * 凭据加密边界（docs/credential-auth-review.md T10 / D1）。
 *
 * SUB / SUBP 是长期全权 Cookie，DataStore 文件本身不该存明文。加密只应用于
 * 凭据字段（sub / subp / raw_cookie），其余偏好照旧明文，避免无谓的复杂度。
 * 接口保持纯 Kotlin：Android 实现走 AndroidKeyStore，桌面端未来可另给实现。
 */
interface CredentialCipher {

    /** 加密；实现无法完成时必须返回原文（可用性优先），不得抛异常阻断登录。 */
    fun encrypt(plainText: String): String

    /**
     * 解密。无法解密的密文（密钥丢失 / 换机恢复）返回空串，凭据视为失效，
     * 由会话层引导重新登录；非密文格式的历史明文原样返回，配合迁移流程改写。
     */
    fun decrypt(value: String): String

    /** 该值是否已是本实现的密文格式。 */
    fun isEncrypted(value: String): Boolean

    companion object {
        /** 直通实现：不改变任何值。测试与无加密环境使用。 */
        val None: CredentialCipher = object : CredentialCipher {
            override fun encrypt(plainText: String): String = plainText
            override fun decrypt(value: String): String = value
            override fun isEncrypted(value: String): Boolean = true
        }
    }
}
