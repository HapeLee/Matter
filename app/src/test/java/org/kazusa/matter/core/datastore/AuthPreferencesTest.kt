package org.kazusa.matter.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.kazusa.matter.core.session.CredentialCipher
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * clearSession 只清会话与凭据键，登出不得连带清掉引导完成标记与分组偏好
 * （docs/credential-auth-review.md T9 / A6）。
 *
 * 整个场景放在单个测试方法里：Context.authDataStore 是进程级单例委托，
 * 拆成多个方法会共享同一个 DataStore 实例与文件，产生相互干扰。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AuthPreferencesTest {

    @Test
    fun clearSession只清会话键并保留引导与分组偏好() = runTest {
        val prefs = AuthPreferences(RuntimeEnvironment.getApplication() as Context)

        prefs.saveSession(
            uid = "1234567",
            sub = "sub_value",
            subp = "subp_value",
            rawCookie = "SUB=sub_value; SUBP=subp_value; _T_WM=1",
            screenName = "测试用户",
            avatarUrl = "https://example.com/a.jpg"
        )
        prefs.setOnboardingCompleted()
        prefs.saveSelectedGroup("gid1", "好友")
        prefs.setGroupVisibility("gid2", isVisible = false)
        prefs.saveGroupCustomOrder(listOf("gid1", "gid2"))

        prefs.clearSession()

        // 会话与凭据全部回到默认空值
        assertEquals(UserSession(), prefs.sessionFlow.first())
        // 引导与分组偏好保留：登出后不重新进引导页、分组设置不丢
        assertTrue(prefs.isOnboardingCompletedFlow.first())
        assertEquals("gid1", prefs.lastGroupFlow.first().first)
        assertEquals("好友", prefs.lastGroupFlow.first().second)
        assertEquals(setOf("gid2"), prefs.hiddenGroupIdsFlow.first())
        assertEquals(listOf("gid1", "gid2"), prefs.groupCustomOrderFlow.first())
    }

    /** 简单可逆变换，模拟 Keystore 实现验证 AuthPreferences 的加解密与迁移语义。 */
    private class ReversingCipher : CredentialCipher {
        override fun encrypt(plainText: String): String = "enc:" + plainText.reversed()
        override fun decrypt(value: String): String =
            if (isEncrypted(value)) value.removePrefix("enc:").reversed() else value
        override fun isEncrypted(value: String): Boolean = value.startsWith("enc:")
    }

    @Test
    fun 凭据写入为密文读取回明文并迁移历史明文() = runTest {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = AuthPreferences(context, ReversingCipher())
        prefs.clearSession()

        // 1. 模拟旧版本：凭据以明文形态直接落进 DataStore
        context.authDataStore.edit { raw ->
            raw[stringPreferencesKey("sub")] = "legacy_plain_sub"
            raw[stringPreferencesKey("raw_cookie")] = "SUB=legacy_plain_sub; SUBP=p"
        }
        // 读路径兼容明文：会话照常可用
        assertEquals("legacy_plain_sub", prefs.sessionFlow.first().sub)

        // 2. 迁移：明文被就地改写为密文，读出的仍是明文
        prefs.migrateLegacyCredentials()
        context.authDataStore.edit { raw ->
            raw[stringPreferencesKey("sub")]?.let { assertTrue(it.startsWith("enc:")) }
            raw[stringPreferencesKey("raw_cookie")]?.let { assertTrue(it.startsWith("enc:")) }
        }
        assertEquals("legacy_plain_sub", prefs.sessionFlow.first().sub)

        // 3. 再次迁移是幂等的（密文不会被二次加密）
        prefs.migrateLegacyCredentials()
        assertEquals("legacy_plain_sub", prefs.sessionFlow.first().sub)

        // 4. 新登录写入的凭据直接落密文
        prefs.saveSession(uid = "1", sub = "new_sub", rawCookie = "SUB=new_sub")
        context.authDataStore.edit { raw ->
            assertEquals("enc:bus_wen", raw[stringPreferencesKey("sub")])
        }
        assertEquals("new_sub", prefs.sessionFlow.first().sub)
    }
}
