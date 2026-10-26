package org.kazusa.matter.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.*
import io.ktor.http.content.OutgoingContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import org.kazusa.matter.core.datastore.UserSession
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.repository.FavoritesRepository
import org.kazusa.matter.data.repository.favoriteId

class FavoritesApiTest {
    @Test
    fun readsWebFavoritesListAndFollowsTotalPage() = runBlocking {
        val client = cookieAwareClient { request ->
            assertEquals("/ajax/favorites/all_fav", request.url.encodedPath)
            assertEquals("weibo.com", request.url.host)
            assertEquals("2", request.url.parameters["page"])
            assertEquals("0", request.url.parameters["tid"])
            assertEquals("true", request.url.parameters["with_total"])
            respond("""{"ok":1,"data":[
                {"id":"123","text":"saved"},
                {"id":"124"},
                {"id":"123"},
                {"deleted":true,"id":"125"}
            ],"count":10,"total_number":755,"total_page":3}""")
        }
        try {
            val result = WeiboApiService(client).getFavorites("SUB=test", 2) as ApiResult.Success
            assertEquals(listOf(123L, 124L), result.data.posts.map { it.id })
            assertTrue(result.data.posts.all { it.isFavorited })
            assertEquals(3, result.data.nextPage)
        } finally { client.close() }
    }

    @Test
    fun emptyFavoritesEndPaginationButLoginFailureIsAnError() = runBlocking {
        for ((response, success) in listOf(
            """{"ok":1,"data":[],"total_page":0}""" to true,
            """{"ok":0,"msg":"这里还没有内容"}""" to true,
            """{"ok":1}""" to true,
            """{"ok":0,"msg":"请先登录"}""" to false
        )) {
            val client = cookieAwareClient { respond(response) }
            try {
                val result = WeiboApiService(client).getFavorites("SUB=test", 1)
                assertEquals(success, result is ApiResult.Success)
                if (result is ApiResult.Success) assertNull(result.data.nextPage)
            } finally { client.close() }
        }
    }

    @Test
    fun writesGoThroughWebFavoritesEndpointsWithXsrfToken() = runBlocking {
        for (favorite in listOf(true, false)) {
            var requests = 0
            val client = cookieAwareClient { request ->
                requests++
                when {
                    request.url.encodedPath == "/api/config" -> {
                        assertEquals("m.weibo.cn", request.url.host)
                        respond("""{"ok":1,"data":{"login":true,"st":"fresh-token"}}""")
                    }
                    request.method == HttpMethod.Get -> {
                        // 网页域没带 XSRF-TOKEN 时先 GET weibo.com/ 取令牌
                        assertEquals("weibo.com", request.url.host)
                        respond("", headers = headersOf(HttpHeaders.SetCookie, "XSRF-TOKEN=fresh-token; Path=/"))
                    }
                    else -> {
                        assertEquals(HttpMethod.Post, request.method)
                        assertEquals("weibo.com", request.url.host)
                        assertEquals(
                            "/ajax/statuses/" + if (favorite) "createFavorites" else "destoryFavorites",
                            request.url.encodedPath
                        )
                        val body = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
                        val json = Json.parseToJsonElement(body).jsonObject
                        assertEquals(listOf("id"), json.keys.toList())
                        assertEquals("123", json["id"]?.jsonPrimitive?.content)
                        assertEquals("fresh-token", request.headers["X-XSRF-TOKEN"])
                        assertTrue(request.headers[HttpHeaders.Cookie]!!.contains("SUB=test"))
                        assertTrue(request.headers[HttpHeaders.Cookie]!!.contains("XSRF-TOKEN=fresh-token"))
                        respond("""{"ok":1,"status":{"id":"123"}}""")
                    }
                }
            }
            try {
                assertTrue(WeiboApiService(client).setFavorite("SUB=test", "123", favorite) is ApiResult.Success)
                assertEquals(2, requests)
            } finally { client.close() }
        }
    }

    @Test
    fun failedWritesDoNotChangeFavoriteStateAndCanBeRetried() = runBlocking {
        var writes = 0
        val client = cookieAwareClient { request ->
            when {
                request.url.encodedPath == "/api/config" ->
                    respond("""{"ok":1,"data":{"login":true,"st":"token"}}""")
                request.method == HttpMethod.Get ->
                    respond("", headers = headersOf(HttpHeaders.SetCookie, "XSRF-TOKEN=token; Path=/"))
                else -> { writes++; respond("""{"ok":0,"msg":"操作失败"}""") }
            }
        }
        try {
            val repository = FavoritesRepository(UserSession(uid = "1", sub = "test", isLoggedIn = true), WeiboApiService(client))
            repeat(2) { assertTrue(repository.toggle(WeiboStatusDto(id = 123)) is ApiResult.Error) }
            assertEquals(2, writes)
            assertTrue(repository.state.value.overrides.isEmpty())
            assertTrue(repository.state.value.pending.isEmpty())
        } finally { client.close() }
    }

    @Test
    fun guestRequestsNeverReachTheNetwork() = runBlocking {
        val client = cookieAwareClient { error("Guest must not send authenticated requests") }
        try {
            val repository = FavoritesRepository(UserSession(uid = "guest", isLoggedIn = true), WeiboApiService(client))
            assertTrue(repository.getFavorites(1) is ApiResult.Error)
            assertTrue(repository.toggle(WeiboStatusDto(id = 123)) is ApiResult.Error)
        } finally { client.close() }
    }

    @Test
    fun concurrentClicksAreDeduplicatedAndSharedStateOverridesStalePosts() = runBlocking {
        val writing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val actions = mutableListOf<String>()
        val client = cookieAwareClient { request ->
            when {
                request.url.encodedPath == "/api/config" ->
                    respond("""{"ok":1,"data":{"login":true,"st":"token"}}""")
                request.method == HttpMethod.Get ->
                    respond("", headers = headersOf(HttpHeaders.SetCookie, "XSRF-TOKEN=token; Path=/"))
                else -> {
                    actions += request.url.encodedPath
                    writing.complete(Unit)
                    release.await()
                    respond("""{"ok":1}""")
                }
            }
        }
        try {
            val repository = FavoritesRepository(UserSession(uid = "1", sub = "test", isLoggedIn = true), WeiboApiService(client))
            val post = WeiboStatusDto(id = 123)
            val first = async { repository.toggle(post) }
            writing.await()
            assertTrue("123" in repository.state.value.pending)
            assertTrue(repository.toggle(post) is ApiResult.Error)
            release.complete(Unit)
            assertEquals(true, (first.await() as ApiResult.Success).data)
            assertEquals(true, repository.state.value.overrides["123"])
            assertEquals(false, (repository.toggle(post) as ApiResult.Success).data)
            assertEquals(
                listOf("/ajax/statuses/createFavorites", "/ajax/statuses/destoryFavorites"),
                actions
            )
            assertTrue(repository.state.value.pending.isEmpty())
        } finally { release.complete(Unit); client.close() }
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNotReportedAsANetworkFailure() = runBlocking {
        val client = cookieAwareClient { throw CancellationException("cancelled") }
        try { WeiboApiService(client).getFavorites("SUB=test", 1); Unit } finally { client.close() }
    }

    @Test
    fun favoriteIdUsesOnlyValidNumericIds() {
        assertEquals("123", WeiboStatusDto(idStr = "", mid = "abc", id = 123).favoriteId)
        assertEquals("456", WeiboStatusDto(idStr = "456", id = 123).favoriteId)
        assertNull(WeiboStatusDto(mid = "abc").favoriteId)
    }
}
