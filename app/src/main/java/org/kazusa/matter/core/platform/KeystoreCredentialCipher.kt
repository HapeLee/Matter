package org.kazusa.matter.core.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.kazusa.matter.core.session.CredentialCipher
import java.security.KeyStore
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AndroidKeyStore + AES/GCM 凭据加密。
 *
 * 密钥不可导出、不要求用户认证（凭据需要后台静默解密）；`setRandomizedEncryptionRequired`
 * 由系统为每次加密生成独立 IV，随密文一起存进 DataStore（`v1:` 前缀 + Base64(iv ‖ ct)）。
 * 密钥被系统判定失效（如换机恢复把密文带过来而密钥没跟来）时解密返回空串，
 * 用户重新走一遍网页授权即可，不影响其他数据。
 */
class KeystoreCredentialCipher : CredentialCipher {

    private val keyAlias = "matter_auth_credentials"

    override fun encrypt(plainText: String): String = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, freshKey())
        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        PREFIX + Base64.encodeToString(cipher.iv + cipherText, Base64.NO_WRAP)
    } catch (_: Exception) {
        // Keystore 不可用时退回明文保证可用性；备份通道已在 T1 关闭，
        // 明文残留的风险面只剩 root/调试设备
        plainText
    }

    override fun decrypt(value: String): String {
        if (!isEncrypted(value)) return value
        return try {
            val data = Base64.decode(value.removePrefix(PREFIX), Base64.NO_WRAP)
            val iv = data.copyOfRange(0, GCM_IV_BYTES)
            val cipherText = data.copyOfRange(GCM_IV_BYTES, data.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, obtainKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    override fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)

    private fun obtainKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    /** 取密钥；已被系统判定失效时删旧建新一次，仍失败则抛出让 encrypt/decrypt 兜底。 */
    private fun freshKey(): SecretKey = try {
        obtainKey()
    } catch (_: GeneralSecurityException) {
        runCatching {
            KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }.deleteEntry(keyAlias)
        }
        obtainKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFIX = "v1:"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
    }
}
