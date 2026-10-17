package org.kazusa.matter.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kazusa.matter.data.model.WeiboCardResponse
import org.kazusa.matter.data.model.toHotSearchBoard

/**
 * 发现页热搜榜解析回归。
 *
 * 真机 `filter_type=realtimehot` 响应不是一条扁平列表，而是若干张卡片（榜段），
 * 段内条目形态不统一 —— 平铺解析会导致「置顶被当第 1 名」「上升热点接到 50 名之后」
 * 「查看更多…被当成热搜词」，这里把四条规则钉住：
 * 置顶（`cate:5000`，无名次）/ 排名（`realpos:N`）/ 推广位（`is_ad_pos:1`，不占名次）/ 查看更多（`card_type=6`）。
 */
class HotSearchResponseTest {

    @Test
    fun hotSearchKeepsSectionsPinnedRankAndMoreEntries() {
        val board = decode(FIXTURE).data!!.toHotSearchBoard()

        // 三个榜段：实时热点 / 实时上升热点 / 文娱热搜（段标题写在组内 ct=101 标题卡上）
        assertEquals(
            listOf("实时热点，每分钟更新一次", "实时上升热点", "文娱热搜"),
            board.sections.map { it.title }
        )

        val spot = board.sections[0].items
        // 置顶：无名次、有服务端角标图，不计入排名
        assertTrue(spot[0].isPinned)
        assertNull(spot[0].rank)
        assertTrue(spot[0].badge!!.contains("stick"))
        // 排名项：名次取服务端 realpos
        assertEquals(1, spot[1].rank)
        assertEquals("1124257", spot[1].heat)
        // 商业推广位：不占名次
        assertTrue(spot[2].isPromoted)
        assertNull(spot[2].rank)
        // 「查看更多…」条目：服务端文案 + 右箭头，不能当热搜词
        assertTrue(spot[3].isMore)
        assertEquals("查看更多实时上升热点", spot[3].title)

        // 上升热点是独立榜段，热度是服务端文案而不是数字
        assertEquals(1, board.sections[1].items.size)
        assertEquals("热度飙升", board.sections[1].items[0].heat)
        assertFalse(board.sections[1].items[0].isPinned)

        // 段标题卡不应混进条目
        assertEquals("文娱热搜", board.sections[2].title)
        assertEquals(1, board.sections[2].items.size)

        // 单条 ct=101 的卡片是入口卡，不是榜段
        assertEquals("好友热搜", board.entries.single().title)
        assertEquals("好友圈的新鲜热点", board.entries.single().subtitle)
    }

    @Test
    fun boardsAndPageTitleComeFromServerProvidedChannelList() {
        val board = decode(FIXTURE).data!!.toHotSearchBoard()

        // 页面大标题取服务端沉浸式头部卡的 title_top
        assertEquals("微博热搜", board.title)

        // 可切换榜单：服务端下发，客户端只认 containerid
        assertEquals(listOf("我的", "热搜", "文娱"), board.channels.map { it.name })
        assertEquals("106003type=25&t=3&disable_hot=1&filter_type=fun", board.channels[2].containerid)
        assertTrue(board.channels[1].isDefault)
    }

    @Test
    fun cityBoardWithoutHotEntriesIsEmpty() {
        // 真机「同城」榜在所在城市没有热点时：城市切换卡 + 一张通栏图（card_type=22，无文案）。
        // 两者都不是榜单内容，整页应判为空 → 走空占位，不渲染段标题 / 通栏图 / 入口卡。
        val board = decode(REGION_FIXTURE).data!!.toHotSearchBoard()

        assertTrue(board.isEmpty)
        assertTrue(board.sections.isEmpty())
    }

    private fun decode(raw: String): WeiboCardResponse =
        KtorClientFactory.jsonConfig.decodeFromString<WeiboCardResponse>(raw)

    private companion object {
        /** 按真机响应裁剪：保留置顶 / 排名 / 推广 / 查看更多 / 段标题卡 / 入口卡 六种形态 + 榜单列表。 */
        const val FIXTURE = """
{"ok":1,"data":{
 "cardlistInfo":{"cardlist_head_cards":[
   {"head_type":1,"head_type_name":"immersive","title_top":"微博热搜"},
   {"head_type":0,"head_type_name":"channel_list","channel_list":[
     {"id":1,"name":"我的","containerid":"106003_-_type:25_-_filter_type:mineband","default_add":1},
     {"id":2,"name":"热搜","containerid":"106003type=25&t=3&disable_hot=1&filter_type=realtimehot","default_add":1},
     {"id":3,"name":"文娱","containerid":"106003type=25&t=3&disable_hot=1&filter_type=fun","default_add":1}
   ]}]},
 "cards":[
 {"card_type":11,"title":"实时热点，每分钟更新一次","card_group":[
   {"card_type":4,"desc":"坚定实施健康优先发展战略",
    "icon":"https://simg.s.weibo.com/moter/flags/2_0.png",
    "pic":"https://simg.s.weibo.com/20180205110043_img_search_stick@3x.png",
    "itemid":"c_type:51|cate:5000|pos:1-2-0","scheme":"https://m.weibo.cn/search?containerid=1"},
   {"card_type":4,"desc":"深圳全市全天禁鸣喇叭5年","desc_extr":"1124257",
    "icon":"https://simg.s.weibo.com/moter/flags/2_0.png",
    "pic":"https://simg.s.weibo.com/wb_search_img_search_1.png",
    "itemid":"c_type:31|cate:5001|realpos:1","scheme":"https://m.weibo.cn/search?containerid=2"},
   {"card_type":4,"desc":"MONA L05预售价16.98万元起","desc_extr":"",
    "pic":"https://simg.s.weibo.com/20210408_search_point_orange.png",
    "itemid":"c_type:31|pos:1-1-3|cate:5001|is_ad_pos:1|adid:362174",
    "promotion":{"monitor_url":[]},"scheme":"https://m.weibo.cn/search?containerid=3"},
   {"card_type":6,"desc":"查看更多实时上升热点","display_arrow":1,
    "scheme":"https://m.weibo.cn/p/index?filter_type=realtimespot"}
 ]},
 {"card_type":11,"title":"实时上升热点","card_group":[
   {"card_type":4,"desc":"看似热量高实则巨低卡的善良食物","desc_extr":"热度飙升",
    "pic":"https://simg.s.weibo.com/20170306_plus_new1.png",
    "itemid":"c_type:25|cate:5002","scheme":"https://m.weibo.cn/search?containerid=4"}
 ]},
 {"card_type":11,"card_group":[
   {"card_type":101,"title":" 文娱热搜 ","left_tag_img":"https://simg.s.weibo.com/ent_icon.png"},
   {"card_type":4,"desc":"徐艺洋24年6月的肚子","desc_extr":"1754958",
    "pic":"https://simg.s.weibo.com/wb_search_img_search_1.png",
    "itemid":"c_type:157|cate:5023","scheme":"https://m.weibo.cn/search?containerid=5"}
 ]},
 {"card_type":11,"card_group":[
   {"card_type":101,"title":" 好友热搜","sub_title":"好友圈的新鲜热点",
    "left_tag_img":"https://simg.s.weibo.com/friend_icon.png",
    "scheme":"https://m.weibo.cn/p/index?filter_type=friends"}
 ]}
]}}
"""

        /** 按真机「同城」榜（所在城市无热点）裁剪：城市切换入口卡 + 一张通栏空状态图。 */
        const val REGION_FIXTURE = """
{"ok":1,"data":{"cards":[
 {"card_type":11,"card_group":[
   {"card_type":101,"title":"当前","desc":"点击切换",
    "left_tag_img":"https://simg.s.weibo.com/20200508_hot_region_title.png",
    "scheme":"https://m.s.weibo.com/hot/setplaceall"}
 ]},
 {"card_type":11,"title":"同城用户在关注","card_group":[
   {"card_type":22,"pic":"https://simg.s.weibo.com/20200521_2.png","width":1125,"height":391,
    "scheme":"https://m.weibo.cn/search?containerid=100103type%3D1%26q%3D%E5%90%8C%E5%9F%8E"}
 ]}
]}}
"""
    }
}
