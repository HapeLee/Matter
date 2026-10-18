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
import org.junit.Assert.*
import org.junit.Test
import org.kazusa.matter.core.datastore.UserSession
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.repository.FavoritesRepository
import org.kazusa.matter.data.repository.favoriteId

class FavoritesApiTest {
    @Test
    fun readsMobileCardsAndUsesServerPageEvenForShortPages() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            assertEquals("230259", request.url.parameters["containerid"])
            assertEquals("0", request.url.parameters["openApp"])
            assertEquals("2", request.url.parameters["page"])
            respond("""{"ok":1,"data":{"cardlistInfo":{"total":755,"page":3},"cards":[
                {"card_type":9,"mblog":{"id":"123","text":"saved"}},
                {"card_type":11,"card_group":[{"card_type":9,"mblog":{"id":"124"}}]},
                {"card_type":9,"mblog":{"id":"123"}}
            ]}}""")
        })
        try {
            val result = FavoritesApi(client).getFavorites("SUB=test", 2) as ApiResult.Success
            assertEquals(listOf(123L, 124L), result.data.posts.map { it.id })
            assertTrue(result.data.posts.all { it.isFavorited })
            assertEquals(3, result.data.nextPage)
        } finally { client.close() }
    }

    @Test
    fun emptyCardsEndPaginationButLoginFailureIsAnError() = runBlocking {
        for ((response, success) in listOf(
            """{"ok":1,"data":{"cards":[],"cardlistInfo":{"page":9}}}""" to true,
            """{"ok":0,"msg":"这里还没有内容","data":{"cards":[]}}""" to true,
            """{"ok":0,"msg":"这里还没有内容"}""" to true,
            """{"ok":0,"msg":"请先登录"}""" to false,
            """{"ok":1,"data":{}}""" to false
        )) {
            val client = HttpClient(MockEngine { respond(response) })
            try {
                val result = FavoritesApi(client).getFavorites("SUB=test", 1)
                assertEquals(success, result is ApiResult.Success)
                if (result is ApiResult.Success) assertNull(result.data.nextPage)
            } finally { client.close() }
        }
    }

    @Test
    fun writesUseFreshTokenCookiesAndDifferentMobileActionPaths() = runBlocking {
        for (favorite in listOf(true, false)) {
            var requests = 0
            val client = HttpClient(MockEngine { request ->
                requests++
                if (requests == 1) {
                    assertEquals("/api/config", request.url.encodedPath)
                    respond("""{"ok":1,"data":{"login":true,"st":"fresh-token"}}""",
                        headers = headersOf(HttpHeaders.SetCookie, "XSRF-TOKEN=fresh-token; Path=/"))
                } else {
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals("/api/statuses/" + if (favorite) "createfav" else "destroyfav", request.url.encodedPath)
                    val form = (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString().parseUrlEncodedParameters()
                    assertEquals("123", form["id"])
                    assertEquals("fresh-token", form["st"])
                    assertEquals("fresh-token", request.headers["X-XSRF-TOKEN"])
                    assertTrue(request.headers[HttpHeaders.Cookie]!!.contains("SUB=test"))
                    assertTrue(request.headers[HttpHeaders.Cookie]!!.contains("XSRF-TOKEN=fresh-token"))
                    assertFalse(request.headers[HttpHeaders.Cookie]!!.contains("stale-token"))
                    respond("""{"ok":1}""")
                }
            })
            try {
                assertTrue(FavoritesApi(client).setFavorite("SUB=test; XSRF-TOKEN=stale-token", "123", favorite) is ApiResult.Success)
                assertEquals(2, requests)
            } finally { client.close() }
        }
    }

    @Test
    fun failedWritesDoNotChangeFavoriteStateAndCanBeRetried() = runBlocking {
        var writes = 0
        val client = HttpClient(MockEngine { request ->
            if (request.method == HttpMethod.Get) respond("""{"ok":1,"data":{"login":true,"st":"token"}}""")
            else { writes++; respond("""{"ok":0,"msg":"操作失败"}""") }
        })
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
        val client = HttpClient(MockEngine { error("Guest must not send authenticated requests") })
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
        val client = HttpClient(MockEngine { request ->
            if (request.method == HttpMethod.Get) respond("""{"ok":1,"data":{"login":true,"st":"token"}}""")
            else {
                actions += request.url.encodedPath
                writing.complete(Unit)
                release.await()
                respond("""{"ok":1}""")
            }
        })
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
            assertEquals(listOf("/api/statuses/createfav", "/api/statuses/destroyfav"), actions)
            assertTrue(repository.state.value.pending.isEmpty())
        } finally { release.complete(Unit); client.close() }
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNotReportedAsANetworkFailure() = runBlocking {
        val client = HttpClient(MockEngine { throw CancellationException("cancelled") })
        try { FavoritesApi(client).getFavorites("SUB=test", 1); Unit } finally { client.close() }
    }

    @Test
    fun favoriteIdUsesOnlyValidNumericIds() {
        assertEquals("123", WeiboStatusDto(idStr = "", mid = "abc", id = 123).favoriteId)
        assertEquals("456", WeiboStatusDto(idStr = "456", id = 123).favoriteId)
        assertNull(WeiboStatusDto(mid = "abc").favoriteId)
    }
}
