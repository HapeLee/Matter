package org.kazusa.matter.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.kazusa.matter.data.model.WeiboCardResponse
import org.kazusa.matter.data.model.WeiboCardGroupItemDto
import org.kazusa.matter.data.model.WeiboUserDto
import org.kazusa.matter.data.model.classifySearchCard
import org.kazusa.matter.data.model.toSearchCardInfo
import org.kazusa.matter.data.model.toSearchChipGroupOrNull

/**
 * 搜索（`page_type=searchall`）响应解析回归。
 *
 * 真机样本里同一个 key 的形态不固定，任一处理不当都会让**整棵卡片树**解析抛异常，
 * 搜索页直接报错、一个结果都出不来（已实际踩过）：
 * - `buttons[].actionlog` / `itemid`：可能是字符串，也可能是埋点对象 `{"act_code":554,…}`；
 * - `card_type`：可能是数字，也可能是字符串 `"4"`；
 * - `buttons[].type`：真机是字符串（`"follow"`）。
 */
class SearchAllResponseTest {

    @Test
    fun searchChannelsBecomeServerDrivenTabs() {
        val response = decode(
            """{"ok":1,"data":{
                "cardlistInfo":{"containerid":"100103type=1&q=美食&t=1","cardlist_head_cards":[
                    {"head_type":0,"head_type_name":"channel_list","show_menu":1,"channel_list":[
                        {"id":"1","name":"综合","containerid":"100103type=1&q=美食&t=1","scheme":"sinaweibo://selectchannel?containerid=100103type=1&q=美食&t=1","must_show":1},
                        {"id":"63","name":"图片","type":"cardlist","containerid":"100103type=63&q=美食&t=1"},
                        {"id":"3","name":"用户","containerid":"100103type=3&q=美食&t=1"}
                    ]}]},
                "cards":[]}}"""
        )
        val channels = response.data!!.cardlistInfo!!.cardlistHeadCards.single().channelList
        assertEquals(listOf("综合", "图片", "用户"), channels.map { it.name })
        assertEquals("100103type=63&q=美食&t=1", channels[1].containerid)
        // 分栏名与 containerid 全部来自服务端
        assertEquals("cardlist", channels[1].type)
    }

    @Test
    fun objectShapedTrackingFieldsAndStringCardTypeDoNotBreakDecoding() {
        val response = decode(
            """{"ok":1,"data":{
                "cardlistInfo":{"cardlist_head_cards":[]},
                "cards":[{"card_type":11,"card_group":[
                    {"card_type":"4","itemid":{"act_code":554,"ext":"seqid:1"},
                     "desc1":"我爱生活，更爱吃喝！","desc2":"粉丝：58.8万",
                     "scheme":"https://m.weibo.cn/u/1828872085",
                     "user":{"id":1828872085,"screen_name":"美食"},
                     "buttons":[{"name":"+关注","type":"follow","actionlog":{"act_code":92,"ext":"seqid:2"}}]}
                ]}]}}"""
        )
        val item = response.data!!.cards.single().cardGroup.single()
        assertEquals(4, item.cardType)
        assertEquals("美食", item.user!!.screenName)
        assertEquals("+关注", item.buttons.single().name)
        assertEquals(org.kazusa.matter.data.model.SearchResultKind.USER, item.toSearchCardInfo().classifySearchCard())
    }

    @Test
    fun plainTextGroupBecomesChipGroupAndMixedGroupDoesNot() {
        val chips = listOf(
            chip("美食做法", "sinaweibo://searchall?q=美食做法"),
            chip("美食视频", "sinaweibo://searchall?q=美食视频")
        )
        val group = chips.toSearchChipGroupOrNull("相关搜索")
        assertNotNull(group)
        assertEquals("相关搜索", group!!.title)
        // 词条文本与跳转关键词全部来自服务端下发的条目
        assertEquals(listOf("美食做法", "美食视频"), group.chips.map { it.keyword })

        // 组里混进内容卡（有 user）时不再当词条组，交给普通卡片分派
        val mixed = chips + WeiboCardGroupItemDto(title = "某个博主", user = WeiboUserDto(id = 1))
        assertNull(mixed.toSearchChipGroupOrNull("相关搜索"))
    }

    private fun chip(text: String, scheme: String) = WeiboCardGroupItemDto(title = text, scheme = scheme)

    private fun decode(raw: String): WeiboCardResponse =
        KtorClientFactory.jsonConfig.decodeFromString<WeiboCardResponse>(raw)
}
