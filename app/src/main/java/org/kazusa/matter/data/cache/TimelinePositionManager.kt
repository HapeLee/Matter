package org.kazusa.matter.data.cache

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TimelinePosition(
    val index: Int = 0,
    val offset: Int = 0,
    val postId: String? = null
)

/**
 * 专用于即时、无损持久化记录时间线视口位置（采用 status_position / status_offset 机制）。
 *
 * 采用轻量级 SharedPreferences：位置只有三个小值，没必要跟着上百条微博的 JSON 缓存一起重写。
 *
 * 线程约定（都曾踩在主线程上）：
 * - [savePosition] 的 `synchronous` 分支走 `commit()`（fsync）。它由 `ON_PAUSE` / `ON_STOP` /
 *   `onDispose` 触发，正好压在页面转场动画上，所以落盘换到 [ioScope]；
 * - [getPosition] 是 suspend：**首次**访问会触发整个 XML 文件的读盘 + 解析，
 *   放在 ViewModel init 的主线程上就是一次冷启动卡顿。
 */
class TimelinePositionManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("weibo_timeline_position", Context.MODE_PRIVATE)

    /** 只用于把同步写盘挪出主线程；写入量极小，不需要回收策略。 */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun getKeyPrefix(gid: String?): String {
        val safeGid = if (gid.isNullOrBlank() || gid == "all") "all" else gid
        return "pos_${safeGid}_"
    }

    private fun editorFor(prefix: String, index: Int, offset: Int, postId: String?): SharedPreferences.Editor =
        prefs.edit()
            .putInt("${prefix}index", index)
            .putInt("${prefix}offset", offset)
            .putString("${prefix}post_id", postId)

    fun savePosition(
        gid: String?,
        index: Int,
        offset: Int,
        postId: String?,
        synchronous: Boolean = false
    ) {
        val prefix = getKeyPrefix(gid)
        if (synchronous) {
            // 「立即落盘」的语义保留，但不再占着主线程做 fsync：
            // editor 在 IO 线程上现建现提交，避免跨线程复用 Editor。
            ioScope.launch { editorFor(prefix, index, offset, postId).commit() }
        } else {
            editorFor(prefix, index, offset, postId).apply()
        }
    }

    suspend fun getPosition(gid: String?): TimelinePosition = withContext(Dispatchers.IO) {
        val prefix = getKeyPrefix(gid)
        TimelinePosition(
            index = prefs.getInt("${prefix}index", 0),
            offset = prefs.getInt("${prefix}offset", 0),
            postId = prefs.getString("${prefix}post_id", null)
        )
    }

    fun clearPosition(gid: String? = null) {
        if (gid != null) {
            val prefix = getKeyPrefix(gid)
            prefs.edit()
                .remove("${prefix}index")
                .remove("${prefix}offset")
                .remove("${prefix}post_id")
                .apply()
        } else {
            prefs.edit().clear().apply()
        }
    }
}
