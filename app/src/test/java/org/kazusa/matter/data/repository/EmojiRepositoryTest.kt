package org.kazusa.matter.data.repository

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.OnlineEmojiSource
import org.kazusa.matter.data.model.*

@OptIn(ExperimentalCoroutinesApi::class)
class EmojiRepositoryTest {
    private val pack = OnlineEmojiPackage("com.sina.test", "1", "https://face.t.sinajs.cn/emoji/package/test.zip")
    private val installed = InstalledEmojiPackage(pack.id, "1", "测试包", listOf(OnlineEmoji("[测试]", "file:/test.png", listOf("[test]"))))
    private fun store(saved: List<InstalledEmojiPackage> = emptyList()) = object : EmojiPackageStore {
        override suspend fun restoreCatalog() = emptyList<InstalledEmojiPackage>()
        override suspend fun saveCatalog(groups: List<InstalledEmojiPackage>) {}
        override suspend fun restore() = saved
        override suspend fun install(pack: OnlineEmojiPackage, zip: ByteArray) = installed.copy(version = pack.version)
    }

    @Test
    fun failedCatalogRefreshRetainsLastSuccessfulGroupsAndImagePriority() = runTest {
        val base = InstalledEmojiPackage("catalog.default", "1", "微博表情",
            listOf(OnlineEmoji("[测试]", "https://face.t.sinajs.cn/current.png")))
        var offline = false
        var cached = emptyList<InstalledEmojiPackage>()
        val source = object : OnlineEmojiSource {
            override suspend fun catalog(): ApiResult<List<InstalledEmojiPackage>> =
                if (offline) ApiResult.Error(message = "离线") else ApiResult.Success(listOf(base))
            override suspend fun packages() = ApiResult.Success(listOf(pack))
            override suspend fun download(pack: OnlineEmojiPackage) = ApiResult.Success(byteArrayOf(1))
        }
        val store = object : EmojiPackageStore {
            override suspend fun restoreCatalog() = cached
            override suspend fun saveCatalog(groups: List<InstalledEmojiPackage>) { cached = groups }
            override suspend fun restore() = emptyList<InstalledEmojiPackage>()
            override suspend fun install(pack: OnlineEmojiPackage, zip: ByteArray) = installed
        }
        val repository = EmojiRepository(source, store)
        repository.refresh()
        repository.install(pack.id)
        assertEquals("https://face.t.sinajs.cn/current.png", repository.images.value["[测试]"])
        offline = true
        repository.refresh(force = true)
        assertEquals(listOf(base), repository.state.value.catalog)
        assertEquals(listOf(base), cached)
        assertEquals("离线", repository.state.value.error)
        val restored = EmojiRepository(source, store)
        restored.restore()
        assertEquals(listOf(base), restored.state.value.catalog)
        assertEquals("https://face.t.sinajs.cn/current.png", restored.images.value["[测试]"])
    }

    @Test
    fun downloadIsDeduplicatedAndOnlyCompletedPackPublishesImages() = runTest {
        var downloads = 0
        val response = CompletableDeferred<ApiResult<ByteArray>>()
        val source = object : OnlineEmojiSource {
            override suspend fun catalog() = ApiResult.Success(emptyList<InstalledEmojiPackage>())
            override suspend fun packages() = ApiResult.Success(listOf(pack))
            override suspend fun download(pack: OnlineEmojiPackage): ApiResult<ByteArray> { downloads++; return response.await() }
        }
        val repository = EmojiRepository(source, store())
        repository.refresh()
        val job = launch { repository.install(pack.id) }
        runCurrent()
        repository.install(pack.id)
        assertEquals(1, downloads)
        assertTrue(repository.images.value.isEmpty())
        response.complete(ApiResult.Success(byteArrayOf(1)))
        job.join()
        assertEquals("file:/test.png", repository.images.value["[test]"])
        assertTrue(repository.state.value.downloading.isEmpty())
    }

    @Test
    fun directoryUpdatesDuringDownloadOrUnpackOnlyPublishLatestVersion() = runTest {
        for (duringUnpack in listOf(false, true)) {
            var version = "1"
            val first = CompletableDeferred<ApiResult<ByteArray>>()
            val unpack = CompletableDeferred<Unit>()
            val downloads = mutableListOf<String>()
            val installs = mutableListOf<String>()
            val source = object : OnlineEmojiSource {
                override suspend fun catalog() = ApiResult.Success(emptyList<InstalledEmojiPackage>())
                override suspend fun packages() = ApiResult.Success(listOf(pack.copy(version = version)))
                override suspend fun download(pack: OnlineEmojiPackage): ApiResult<ByteArray> {
                    downloads += pack.version
                    return if (pack.version == "1" && !duringUnpack) first.await() else ApiResult.Success(byteArrayOf(2))
                }
            }
            val store = object : EmojiPackageStore {
                override suspend fun restoreCatalog() = emptyList<InstalledEmojiPackage>()
                override suspend fun saveCatalog(groups: List<InstalledEmojiPackage>) {}
                override suspend fun restore() = emptyList<InstalledEmojiPackage>()
                override suspend fun install(pack: OnlineEmojiPackage, zip: ByteArray): InstalledEmojiPackage {
                    installs += pack.version
                    if (pack.version == "1" && duringUnpack) unpack.await()
                    return installed.copy(version = pack.version, emojis = listOf(OnlineEmoji("[测试]", "file:/${pack.version}")))
                }
            }
            val repository = EmojiRepository(source, store)
            val publishedImages = mutableListOf<Map<String, String>>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { repository.images.collect { publishedImages += it } }
            repository.refresh()
            val download = launch { repository.install(pack.id) }
            runCurrent()
            version = "2"
            repository.refresh(force = true)
            repository.install(pack.id)
            first.complete(ApiResult.Success(byteArrayOf(1)))
            unpack.complete(Unit)
            download.join()
            assertEquals(listOf("1", "2"), downloads)
            assertEquals(if (duringUnpack) listOf("1", "2") else listOf("2"), installs)
            assertEquals("2", repository.state.value.installed.single().version)
            assertTrue(publishedImages.none { it["[测试]"] == "file:/1" })
            assertEquals("file:/2", repository.images.value["[测试]"])
            assertTrue(repository.state.value.downloading.isEmpty())
        }
    }

    @Test
    fun catalogCacheFailureDoesNotBlockLiveCatalogOrPackageDirectory() = runTest {
        val catalog = installed.copy(id = "catalog.default")
        val source = object : OnlineEmojiSource {
            override suspend fun catalog() = ApiResult.Success(listOf(catalog))
            override suspend fun packages() = ApiResult.Success(listOf(pack))
            override suspend fun download(pack: OnlineEmojiPackage) = ApiResult.Success(byteArrayOf(1))
        }
        val store = object : EmojiPackageStore {
            override suspend fun restoreCatalog() = emptyList<InstalledEmojiPackage>()
            override suspend fun saveCatalog(groups: List<InstalledEmojiPackage>) { throw java.io.IOException("disk full") }
            override suspend fun restore() = emptyList<InstalledEmojiPackage>()
            override suspend fun install(pack: OnlineEmojiPackage, zip: ByteArray) = installed
        }
        val repository = EmojiRepository(source, store)
        repository.refresh()
        assertEquals(listOf(catalog), repository.state.value.catalog)
        assertEquals(listOf(pack), repository.state.value.packages)
        assertEquals("file:/test.png", repository.images.value["[测试]"])
        assertNotNull(repository.state.value.error)
        assertFalse(repository.state.value.refreshing)
    }

    @Test
    fun cancellationReleasesPendingAndAllowsSamePackRetry() = runTest {
        var downloads = 0
        val source = object : OnlineEmojiSource {
            override suspend fun catalog() = ApiResult.Success(emptyList<InstalledEmojiPackage>())
            override suspend fun packages() = ApiResult.Success(listOf(pack))
            override suspend fun download(pack: OnlineEmojiPackage): ApiResult<ByteArray> {
                if (++downloads == 1) CompletableDeferred<Unit>().await()
                return ApiResult.Success(byteArrayOf(1))
            }
        }
        val repository = EmojiRepository(source, store())
        repository.refresh()
        val first = launch { repository.install(pack.id) }
        runCurrent()
        first.cancelAndJoin()
        assertTrue(repository.state.value.downloading.isEmpty())
        repository.install(pack.id)
        assertEquals(2, downloads)
        assertEquals(listOf(installed), repository.state.value.installed)
    }

    @Test
    fun failedRefreshPreservesCachedPackagesAndDownloadedImages() = runTest {
        val source = object : OnlineEmojiSource {
            override suspend fun catalog() = ApiResult.Success(emptyList<InstalledEmojiPackage>())
            override suspend fun packages() = ApiResult.Error(message = "离线")
            override suspend fun download(pack: OnlineEmojiPackage) = ApiResult.Error(message = "离线")
        }
        val repository = EmojiRepository(source, store(listOf(installed)))
        repository.refresh()
        assertEquals(listOf(installed), repository.state.value.installed)
        assertEquals("file:/test.png", repository.images.value["[测试]"])
        assertEquals("离线", repository.state.value.error)
        assertFalse(repository.state.value.refreshing)
    }
}
