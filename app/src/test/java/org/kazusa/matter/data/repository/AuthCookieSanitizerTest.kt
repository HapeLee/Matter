package org.kazusa.matter.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 覆盖 docs/credential-auth-review.md §2.4 的串号场景：
 * 多域名 Cookie 池未经去重时，两个账号的 SUB 同头发送，服务端按第一个取值。
 */
class AuthCookieSanitizerTest {

    @Test
    fun `同名 Cookie 只保留第一个，多账号 SUB 不再拼接`() {
        val oldAccountSub = "SUB=old_account_sub"
        val newAccountSub = "SUB=new_account_sub"
        val pool = "$oldAccountSub; SUBP=old_subp; _T_WM=1; $newAccountSub; SUBP=new_subp; _T_WM=2"

        val result = AuthCookieSanitizer.canonicalize(pool)

        assertTrue(result.contains(oldAccountSub))
        assertFalse(result.contains(newAccountSub))
        assertEquals(1, Regex("SUB=").findAll(result).count())
        assertEquals(1, Regex("SUBP=").findAll(result).count())
        // 其余同名键同样以先出现者为准
        assertTrue(result.contains("_T_WM=1"))
        assertFalse(result.contains("_T_WM=2"))
    }

    @Test
    fun `空值 SUB 被剔除，取到后面的真实 SUB`() {
        val pool = "SUB=\"\"; SUBP=\"\"; SUB=real_sub; SUBP=real_subp"

        val result = AuthCookieSanitizer.canonicalize(pool)

        assertTrue(result.contains("SUB=real_sub"))
        assertTrue(result.contains("SUBP=real_subp"))
        assertFalse(result.contains("\"\""))
    }

    @Test
    fun `值中的等号原样保留`() {
        val pool = "SUB=abc==def; XSRF-TOKEN=a%2Fb"

        val result = AuthCookieSanitizer.canonicalize(pool)

        assertEquals("SUB=abc==def; XSRF-TOKEN=a%2Fb", result)
    }

    @Test
    fun `空白与无值条目被忽略`() {
        val pool = "  ; SUB=token ;; HttpOnly; ;SECURE=; MLOGIN=0"

        val result = AuthCookieSanitizer.canonicalize(pool)

        // SECURE= 值为空被剔除，HttpOnly 无等号被剔除
        assertEquals("SUB=token; MLOGIN=0", result)
    }

    @Test
    fun `垃圾输入输出空串`() {
        assertEquals("", AuthCookieSanitizer.canonicalize(""))
        assertEquals("", AuthCookieSanitizer.canonicalize(";;;"))
        assertEquals("", AuthCookieSanitizer.canonicalize("not a cookie at all"))
    }

    @Test
    fun `空串包围的分隔符不会产生空条目`() {
        val result = AuthCookieSanitizer.canonicalize(" SUB=a ;  ; SUBP=b ")
        assertEquals("SUB=a; SUBP=b", result)
    }
}
