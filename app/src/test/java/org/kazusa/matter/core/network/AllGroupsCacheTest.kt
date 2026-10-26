package org.kazusa.matter.core.network

import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `allGroups` 是关注分组与热门频道共用的一份数据：同一次会话只应拉一次，
 * 换 Cookie 不能复用别人的快照，「重新加载」必须绕过缓存。
 */
class AllGroupsCacheTest {

    private val payload = """
        {"ok":1,"groups":[
          {"title":"默认分组","group_type":0,"group":[
            {"gid":"100011024125180916","title":"全部关注"},
            {"gid":"110001024125180916","title":"最新微博"},
            {"gid":"100091024125180916","title":"互相关注"}
          ]},
          {"title":"我的频道","group_type":1,"group":[
            {"gid":"102803","containerid":"102803","title":"热门"}
          ]}
        ]}
    """.trimIndent()

    @Test
    fun 分组与热门频道共用一次allGroups请求() = runBlocking {
        var requests = 0
        val client = cookieAwareClient { request ->
            requests++
            assertEquals("/ajax/feed/allGroups", request.url.encodedPath)
            respond(payload)
        }
        try {
            val api = WeiboApiService(client)
            val groups = api.getFollowGroups("SUB=test")
            assertTrue(groups is ApiResult.Success)
            assertEquals(
                listOf("all", "110001024125180916", "100091024125180916"),
                (groups as ApiResult.Success).data.map { it.identifier }
            )

            val categories = api.getHotFeedCategories("SUB=test")
            assertTrue(categories is ApiResult.Success)
            assertEquals("102803", (categories as ApiResult.Success).data.single().containerId)
            assertEquals(1, requests)
        } finally {
            client.close()
        }
    }

    @Test
    fun 强制刷新与换Cookie都不复用快照() = runBlocking {
        var requests = 0
        val client = cookieAwareClient { _ ->
            requests++
            respond(payload)
        }
        try {
            val api = WeiboApiService(client)
            api.getFollowGroups("SUB=userA")
            api.getFollowGroups("SUB=userA", forceRefresh = true)
            assertEquals(2, requests)

            // 换号（Cookie 变化）必须重新拉，不能拿上一个账号的分组列表
            api.getFollowGroups("SUB=userB")
            assertEquals(3, requests)
        } finally {
            client.close()
        }
    }

    @Test
    fun 分组变更后快照失效() = runBlocking {
        var requests = 0
        val client = cookieAwareClient { request ->
            requests++
            if (request.url.encodedPath == "/ajax/feed/createGroup") {
                respond("""{"ok":1,"gid":"4483318718559324"}""")
            } else {
                respond(payload)
            }
        }
        try {
            val api = WeiboApiService(client)
            api.getFollowGroups("SUB=test")
            assertEquals(1, requests)

            val created = api.createGroup("新分组", cookie = "SUB=test")
            assertTrue(created is ApiResult.Success)

            // 建组成功后缓存作废，下一次读分组必须回源，否则新分组不会出现
            api.getFollowGroups("SUB=test")
            assertEquals(3, requests)
        } finally {
            client.close()
        }
    }
}
