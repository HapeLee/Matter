package org.kazusa.matter.data.cache

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.kazusa.matter.data.model.WeiboStatusDto
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TimelineCacheManagerTest {

    private val manager = TimelineCacheManager(RuntimeEnvironment.getApplication())

    @Test
    fun originalCursorSurvivesCacheAndPositionUpdates() = runBlocking {
        manager.saveCache("special", "100", listOf(WeiboStatusDto(id = 90)), 1, true, 0, 0, null,
            nextMaxId = 70)
        manager.updateScrollPosition("special", "100", 1, 8, null)
        assertEquals(70L, manager.loadCache("special", "100")?.nextMaxId)
    }

    @Test
    fun truncatedCacheDoesNotSkipPostsThatWereNotSaved() = runBlocking {
        manager.saveCache("long", "100", (200L downTo 1L).map { WeiboStatusDto(id = it) },
            8, false, 0, 0, null, nextMaxId = null)
        val cached = manager.loadCache("long", "100")!!
        assertEquals(150, cached.posts.size)
        assertEquals(51L, cached.nextMaxId)
        assertTrue(cached.hasMore)
    }

    @Test
    fun legacyCacheWithoutRawCursorIsInvalidated() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        java.io.File(context.filesDir, "timeline_cache_legacy.json").writeText(
            """{"uid":"100","posts":[{"id":90}],"hasMore":true}"""
        )
        assertNull(manager.loadCache("legacy", "100"))
    }

    @Test
    fun cacheWrittenByAnotherAccountIsIgnored() = runBlocking {
        manager.saveCache(
            gid = null,
            uid = "100",
            posts = listOf(WeiboStatusDto(id = 1L)),
            page = 1,
            hasMore = true,
            scrollIndex = 3,
            scrollOffset = 0,
            lastReadPostId = null
        )

        val mine = manager.loadCache(gid = null, uid = "100")
        assertNotNull(mine)
        assertEquals(1, mine?.posts?.size)

        // 缓存文件名只按分组区分，换账号后文件还在，但必须按「没有缓存」处理
        assertNull(manager.loadCache(gid = null, uid = "200"))
        // 访客（uid 为空）同样不能复用登录账号留下的缓存
        assertNull(manager.loadCache(gid = null, uid = null))
    }
}
