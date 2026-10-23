package org.kazusa.matter.core.datastore

import android.content.Context
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
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
}
