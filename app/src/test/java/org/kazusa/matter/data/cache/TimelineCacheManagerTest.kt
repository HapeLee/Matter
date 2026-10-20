package org.kazusa.matter.data.cache

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
