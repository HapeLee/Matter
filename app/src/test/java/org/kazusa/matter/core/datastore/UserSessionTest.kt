package org.kazusa.matter.core.datastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ALF` 是会话的硬过期时间（实测 = 登录时刻 + 30 天）。
 * 这里锁住解析口径：解析不出来必须返回 null，不能返回 0 让调用方误判成「已过期」。
 */
class UserSessionTest {

    private fun session(rawCookie: String) = UserSession(uid = "1", rawCookie = rawCookie)

    @Test
    fun `从 cookie 解析 ALF 作为会话过期时间`() {
        // 实测样本：SSOLoginState 与 ALF 相差正好 30 天
        val sample = session(
            "SUBP=0033Wr; SUB=_2A25GAF-gDeRh; SSOLoginState=1795436528; " +
                "ALF=1798028528; MLOGIN=1; _T_WM=46696026542"
        )
        assertEquals(1798028528L, sample.sessionExpiresAt)
        assertEquals(30L, (1798028528L - 1795436528L) / 86_400L)
    }

    @Test
    fun `ALF 位于串首也能解析`() {
        assertEquals(123L, session("ALF=123; SUB=x").sessionExpiresAt)
    }

    @Test
    fun `没有 ALF 时返回 null 而不是 0`() {
        assertNull(session("SUB=_2A25; SUBP=abc").sessionExpiresAt)
        assertNull(session("").sessionExpiresAt)
        // 名字相似但不是 ALF 的键不能被误匹配
        assertNull(session("XALF=123; SUB=x").sessionExpiresAt)
    }

    @Test
    fun `剩余天数按整天计算且可为负`() {
        val sample = session("ALF=1000")
        assertEquals(0L, sample.sessionDaysRemaining(nowEpochSeconds = 1000))
        assertEquals(-1L, sample.sessionDaysRemaining(nowEpochSeconds = 1000 + 86_400))
        assertEquals(2L, sample.sessionDaysRemaining(nowEpochSeconds = 1000 - 2 * 86_400))
    }

    @Test
    fun `ALF 参与 cookieHeader 但不会覆盖 SUB`() {
        val sample = session("ALF=1000; SUB=real_sub; SUBP=real_subp")
        assertTrue(sample.cookieHeader.contains("ALF=1000"))
        assertTrue(sample.cookieHeader.contains("SUB=real_sub"))
    }
}
