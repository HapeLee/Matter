package org.kazusa.matter.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分组类型按网页端 gid 前缀识别（前 5 位就是类型），不再依赖 `friends` / `original`
 * 这类早已不存在的字符串 id。
 */
class WeiboGroupKindTest {

    private fun group(gid: String, name: String = "") = WeiboGroupDto(gid = gid, name = name)

    @Test
    fun 按gid前缀识别系统分组() {
        assertEquals(WeiboGroupKind.ALL, group("all").kind)
        assertEquals(WeiboGroupKind.ALL, group("100011024125180916").kind)
        assertEquals(WeiboGroupKind.QUIET, group("100051024125180916").kind)
        assertEquals(WeiboGroupKind.MUTUAL, group("100091024125180916").kind)
        assertEquals(WeiboGroupKind.GROUP_POST, group("100101024125180916").kind)
        assertEquals(WeiboGroupKind.V_PLUS, group("100131024125180916").kind)
        assertEquals(WeiboGroupKind.LATEST, group("110001024125180916").kind)
        assertEquals(WeiboGroupKind.ORIGINAL, group("110011024125180916").kind)
        assertEquals(WeiboGroupKind.VIDEO, group("110021024125180916").kind)
    }

    @Test
    fun 自建分组是CUSTOM可改名删除() {
        val custom = group("4483318718559324", name="我的分组")
        assertEquals(WeiboGroupKind.CUSTOM, custom.kind)
        assertFalse(custom.isSystem)
    }

    @Test
    fun 特别关注的gid前缀未核对时按名称兜底() {
        assertEquals(WeiboGroupKind.SPECIAL, group("9999", name = "特别关注").kind)
        assertTrue(group("9999", name = "特别关注").isSystem)
    }

    @Test
    fun 系统分组都不允许改名删除() {
        listOf(
            "all",
            "100011024125180916",
            "100091024125180916",
            "110001024125180916",
            "110011024125180916",
            "110021024125180916"
        ).forEach { assertTrue(group(it).isSystem) }
    }
}
