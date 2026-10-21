package org.kazusa.matter.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.kazusa.matter.core.datastore.UserSession
import org.kazusa.matter.data.model.UserPostCollection
import org.kazusa.matter.data.repository.FavoritesRepository

class LikedPostsApiTest {
    @Test
    fun likedPostsUseTheirOwnEndpointAndKeepTheActualFavoriteState() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/api/likes/byme", request.url.encodedPath)
            assertEquals("2", request.url.parameters["page"])
            assertEquals("SUB=test", request.headers["Cookie"])
            respond("""{"ok":1,"data":{"total_number":847,"page":3,"statuses":[
                {"id":123,"favorited":false},{"id":124,"favorited":true},{"id":123}
            ]}}""")
        })
        try {
            val page = (WeiboApiService(client).getLikedPosts("SUB=test", 2) as ApiResult.Success).data
            assertEquals(listOf(123L, 124L), page.posts.map { it.id })
            assertFalse(page.posts.first().isFavorited)
            assertTrue(page.posts.last().isFavorited)
            assertEquals(3, page.nextPage)
        } finally { client.close() }
    }

    @Test
    fun nullMissingOrEmptyStatusesEndPaginationEvenWithANextPage() = runBlocking {
        for (data in listOf("""{"page":1000,"statuses":null}""", """{"page":1000}""", """{"page":1000,"statuses":[]}""")) {
            val client = HttpClient(MockEngine { respond("""{"ok":1,"data":$data}""") })
            try {
                val page = (WeiboApiService(client).getLikedPosts("SUB=test", 999) as ApiResult.Success).data
                assertTrue(page.posts.isEmpty())
                assertNull(page.nextPage)
            } finally { client.close() }
        }
    }

    @Test
    fun loginAndMalformedResponsesAreNotEmptySuccesses() = runBlocking {
        for (response in listOf(
            """{"ok":0,"msg":"请先登录"}""",
            """{"ok":0,"msg":"请先登录","data":{"cards":[]}}""",
            """{"ok":-100,"msg":"登录失效","data":{"statuses":[]}}""",
            """{"ok":1}""",
            """{"ok":1,"data":{}}""",
            """{"ok":1,"data":{"statuses":{}}}""",
            "<html>login required</html>"
        )) {
            val client = HttpClient(MockEngine { respond(response) })
            try { assertTrue(WeiboApiService(client).getLikedPosts("SUB=test", 1) is ApiResult.Error) }
            finally { client.close() }
        }
    }

    @Test
    fun guestCannotReadEitherCollection() = runBlocking {
        val client = HttpClient(MockEngine { error("Must not issue a request for a guest") })
        try {
            val repository = FavoritesRepository(UserSession(uid = "guest", isLoggedIn = true), WeiboApiService(client))
            for (collection in UserPostCollection.entries) assertTrue(repository.getPosts(collection, 1) is ApiResult.Error)
        } finally { client.close() }
    }
}
