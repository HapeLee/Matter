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
    /**
     * 写入缓存时的账号 uid。
     *
     * 缓存文件名只按分组区分，换账号后同名文件还在；读取时必须先比对这个字段，
     * 否则新账号首帧会先渲染上一个账号的动态（连同点赞 / 关注态）。
     * 旧版本缓存没有这个字段（null），一律视为不属于当前账号。
     */
    val uid: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

class TimelineCacheManager(private val context: Context) {

    private fun getCacheFile(gid: String?): File {
        val safeGid = if (gid.isNullOrBlank() || gid == "all") "all" else gid.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(context.filesDir, "timeline_cache_$safeGid.json")
    }

    suspend fun saveCache(
        gid: String?,
        uid: String?,
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
                lastReadPostId = lastReadPostId,
                uid = uid
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

    /**
     * 读取缓存；[uid] 与缓存里记录的账号不一致时按「没有缓存」处理。
     *
     * 比「换账号时删文件」更稳：删文件是另一条协程，新页面可能先读到旧内容；
     * 这里在读取点直接比对身份，与请求 / 写入的先后顺序无关。
     */
    suspend fun loadCache(gid: String?, uid: String?): TimelineCacheData? =
        withContext(Dispatchers.IO) {
            try {
                val file = getCacheFile(gid)
                if (!file.exists()) return@withContext null
                val jsonString = file.readText()
                if (jsonString.isBlank()) return@withContext null
                val data = KtorClientFactory.jsonConfig
                    .decodeFromString(TimelineCacheData.serializer(), jsonString)
                if (data.uid != uid) return@withContext null
                data
            } catch (e: Exception) {
                android.util.Log.e("TimelineCache", "loadCache failed", e)
                null
            }
        }

    suspend fun updateScrollPosition(
        gid: String?,
        uid: String?,
        scrollIndex: Int,
        scrollOffset: Int,
        lastReadPostId: String?
    ) = withContext(Dispatchers.IO) {
        try {
            val current = loadCache(gid, uid)
            if (current != null) {
                saveCache(
                    gid = gid,
                    uid = uid,
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
