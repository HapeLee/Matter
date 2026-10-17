package org.kazusa.matter.data.cache

import android.content.Context
import org.kazusa.matter.core.network.KtorClientFactory
import org.kazusa.matter.data.model.WeiboStatusDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class TimelineCacheData(
    val posts: List<WeiboStatusDto> = emptyList(),
    val page: Int = 1,
    val hasMore: Boolean = true,
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
    val lastReadPostId: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

class TimelineCacheManager(private val context: Context) {

    private fun getCacheFile(gid: String?): File {
        val safeGid = if (gid.isNullOrBlank() || gid == "all") "all" else gid.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(context.filesDir, "timeline_cache_$safeGid.json")
    }

    suspend fun saveCache(
        gid: String?,
        posts: List<WeiboStatusDto>,
        page: Int,
        hasMore: Boolean,
        scrollIndex: Int,
        scrollOffset: Int,
        lastReadPostId: String?
    ) = withContext(Dispatchers.IO) {
        try {
            val file = getCacheFile(gid)
            val tempFile = File(file.parentFile, "${file.name}.tmp")
            val data = TimelineCacheData(
                posts = posts.take(150), // 限制最多缓存 150 条以保持轻巧高效
                page = page,
                hasMore = hasMore,
                scrollIndex = scrollIndex,
                scrollOffset = scrollOffset,
                lastReadPostId = lastReadPostId
            )
            val jsonString = KtorClientFactory.jsonConfig.encodeToString(TimelineCacheData.serializer(), data)
            tempFile.writeText(jsonString)
            if (tempFile.exists()) {
                if (file.exists()) file.delete()
                tempFile.renameTo(file)
            }
        } catch (e: Exception) {
            android.util.Log.e("TimelineCache", "saveCache failed", e)
        }
    }

    suspend fun loadCache(gid: String?): TimelineCacheData? = withContext(Dispatchers.IO) {
        try {
            val file = getCacheFile(gid)
            if (!file.exists()) return@withContext null
            val jsonString = file.readText()
            if (jsonString.isBlank()) return@withContext null
            KtorClientFactory.jsonConfig.decodeFromString(TimelineCacheData.serializer(), jsonString)
        } catch (e: Exception) {
            android.util.Log.e("TimelineCache", "loadCache failed", e)
            null
        }
    }

    suspend fun updateScrollPosition(
        gid: String?,
        scrollIndex: Int,
        scrollOffset: Int,
        lastReadPostId: String?
    ) = withContext(Dispatchers.IO) {
        try {
            val current = loadCache(gid)
            if (current != null) {
                saveCache(
                    gid = gid,
                    posts = current.posts,
                    page = current.page,
                    hasMore = current.hasMore,
                    scrollIndex = scrollIndex,
                    scrollOffset = scrollOffset,
                    lastReadPostId = lastReadPostId ?: current.lastReadPostId
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("TimelineCache", "updateScrollPosition failed", e)
        }
    }

    suspend fun clearCache(gid: String? = null) = withContext(Dispatchers.IO) {
        try {
            if (gid != null) {
                getCacheFile(gid).delete()
            } else {
                context.filesDir.listFiles { _, name -> name.startsWith("timeline_cache_") }?.forEach {
                    it.delete()
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TimelineCache", "clearCache failed", e)
        }
    }
}
