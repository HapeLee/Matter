package org.kazusa.matter.core.datastore

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.kazusa.matter.core.session.CredentialCipher
import org.kazusa.matter.core.session.SessionExpiryReason
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

    /**
     * 重新登录必须整体覆盖凭据：新串里没有 SUB 时要把旧的清掉。
     * 之前的 `if (isNotBlank())` 守卫会让旧账号的 SUB 留下来，与新的 uid / rawCookie
     * 混搭成自相矛盾的凭据，表现就是「刚登录就失效」。
     */
    @Test
    fun 重新登录清掉旧账号残留凭据() = runTest {
        val prefs = AuthPreferences(RuntimeEnvironment.getApplication() as Context)
        prefs.clearSession()

        prefs.saveSession(uid = "A", sub = "sub_a", subp = "subp_a", rawCookie = "SUB=sub_a")
        prefs.saveSession(uid = "B", sub = "", subp = "subp_b", rawCookie = "SUBP=subp_b")

        val session = prefs.sessionFlow.first()
        assertEquals("B", session.uid)
        assertEquals("", session.sub)
        assertEquals("subp_b", session.subp)
        assertEquals("SUBP=subp_b", session.rawCookie)
    }

    /** 服务端轮换回写：同值不写、换号后旧响应不写、写入后 cookieHeader 立即跟上。 */
    @Test
    fun 轮换凭据校验账号代次并去重() = runTest {
        val prefs = AuthPreferences(RuntimeEnvironment.getApplication() as Context)
        prefs.clearSession()
        prefs.saveSession(uid = "uid_1", sub = "old_sub", subp = "old_subp", rawCookie = "SUB=old_sub")

        assertTrue(prefs.rotateCredentials("uid_1", sub = "new_sub"))
        var session = prefs.sessionFlow.first()
        assertEquals("new_sub", session.sub)
        assertEquals("old_subp", session.subp)
        // cookieHeader 以 sub 覆盖 rawCookie 同名项，轮换后立即生效
        assertTrue(session.cookieHeader.contains("SUB=new_sub"))

        // 同一份值不重复落盘（每次 edit 都会让 sessionFlow 重新发射）
        assertFalse(prefs.rotateCredentials("uid_1", sub = "new_sub"))
        // 换号后旧响应晚到：uid 不匹配，不得写入
        assertFalse(prefs.rotateCredentials("uid_2", sub = "stale_sub"))
        session = prefs.sessionFlow.first()
        assertEquals("new_sub", session.sub)
    }

    /** 判定失效只置未登录标记，凭据密文必须保留（误判可恢复的唯一前提）。 */
    @Test
    fun 判定失效保留凭据() = runTest {
        val prefs = AuthPreferences(RuntimeEnvironment.getApplication() as Context)
        prefs.clearSession()
        prefs.saveSession(uid = "123", sub = "s", subp = "p", rawCookie = "SUB=s")

        prefs.markSessionInvalid(SessionExpiryReason.API_REJECTED)

        val session = prefs.sessionFlow.first()
        assertFalse(session.isLoggedIn)
        assertEquals("123", session.uid)
        assertEquals("s", session.sub)
        assertEquals("p", session.subp)
        assertEquals("SUB=s", session.rawCookie)
    }

    /**
     * 自动退出要留下可解释的痕迹：原因 + 时间。
     * 重新登录成功后必须清掉，否则登录页会一直显示过期的「上次退出」。
     */
    @Test
    fun 记录失效原因并重新登录后清除() = runTest {
        val prefs = AuthPreferences(RuntimeEnvironment.getApplication() as Context)
        prefs.clearSession()
        prefs.saveSession(uid = "1", sub = "s", rawCookie = "SUB=s")

        assertNull(prefs.lastSessionExpiryFlow.first())

        prefs.markSessionInvalid(SessionExpiryReason.PROBE_FAILED)

        val recorded = prefs.lastSessionExpiryFlow.first()
        assertEquals(SessionExpiryReason.PROBE_FAILED, recorded?.reason)
        assertTrue((recorded?.atEpochMillis ?: 0L) > 0L)
        // 凭据仍保留，只是标记未登录
        assertEquals("s", prefs.sessionFlow.first().sub)

        prefs.saveSession(uid = "1", sub = "s2", rawCookie = "SUB=s2")
        assertNull(prefs.lastSessionExpiryFlow.first())
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
