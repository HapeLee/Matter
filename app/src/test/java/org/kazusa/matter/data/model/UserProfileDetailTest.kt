package org.kazusa.matter.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC 端 profile/detail 字段解析回归。
 *
 * 微博对 `education` / `label_desc` 等字段的形态并不稳定：
 * - `education` 可能是对象，也可能是对象数组；
 * - `label_desc` 可能是 `[{"name": ...}]`，也可能是纯字符串数组；
 * - `birthday` 有时把星座拼在同一个字符串里。
 * 任一形态处理不当都会让「博主资料」整块消失。
 */
class UserProfileDetailTest {

    private fun parse(raw: String): UserProfileDetail =
        parseUserProfileDetail(Json.parseToJsonElement(raw).jsonObject)

    @Test
    fun fullDetailFieldsAreParsed() {
        val detail = parse(
            """{
                "location":"北京",
                "gender":"m",
                "birthday":"1948-06-15 双子座",
                "ip_location":"发布于 北京",
                "created_at":"2012-07-22 02:28:35",
                "description":"人民日报法人微博",
                "education":{"school":"清华大学"},
                "career":{"company":"人民日报社"},
                "sunshine_credit":{"level":"信用极好"},
                "label_desc":[
                    {"name":"昨日发博27，阅读数100万+，互动数37万"},
                    {"name":"视频累计播放量377.82亿"}
                ],
                "domain":"rmrb"
            }"""
        )

        assertEquals("北京", detail.location)
        assertEquals("男", detail.genderLabel)
        assertEquals("1948-06-15", detail.birthday)
        assertEquals("双子座", detail.constellation)
        assertEquals("发布于 北京", detail.ipLocation)
        assertEquals("2012-07-22", detail.registeredDate)
        assertEquals("清华大学", detail.school)
        assertEquals("人民日报社", detail.company)
        assertEquals("信用极好", detail.sunshineCreditLevel)
        assertEquals(2, detail.labels.size)
        assertEquals("rmrb", detail.domain)
        assertTrue(detail.hasProfileInfo)
    }

    @Test
    fun alternativeShapesStillParse() {
        val detail = parse(
            """{
                "gender":"f",
                "birthday":"1990-01-01",
                "education":[{"school":"北京大学"}],
                "career":[{"company":"某公司"}],
                "sunshine_credit":"信用良好",
                "label_desc":["标签一","标签二"]
            }"""
        )

        assertEquals("女", detail.genderLabel)
        assertEquals("1990-01-01", detail.birthday)
        assertEquals(null, detail.constellation)
        assertEquals("北京大学", detail.school)
        assertEquals("某公司", detail.company)
        assertEquals("信用良好", detail.sunshineCreditLevel)
        assertEquals(listOf("标签一", "标签二"), detail.labels)
    }

    @Test
    fun emptyDetailDoesNotClaimProfileInfo() {
        val detail = parse("""{"description":"只有简介"}""")

        assertFalse(detail.hasProfileInfo)
        assertEquals(null, detail.genderLabel)
        assertEquals(null, detail.registeredDate)
    }
}
