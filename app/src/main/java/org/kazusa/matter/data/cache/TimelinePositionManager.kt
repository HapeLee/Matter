package org.kazusa.matter.data.cache

import android.content.Context
import android.content.SharedPreferences

data class TimelinePosition(
    val index: Int = 0,
    val offset: Int = 0,
    val postId: String? = null
)

/**
 * 专用于即时、无损持久化记录时间线视口位置（采用 status_position / status_offset 机制）
 * 采用轻量级 SharedPreferences，支持在应用退出/生命周期切换时使用 commit() 同步落盘，
 * 彻底避免异步重写几十上百条微博 JSON 缓存时因进程销毁导致的丢状态问题。
 */
class TimelinePositionManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("weibo_timeline_position", Context.MODE_PRIVATE)

    private fun getKeyPrefix(gid: String?): String {
        val safeGid = if (gid.isNullOrBlank() || gid == "all") "all" else gid
        return "pos_${safeGid}_"
    }

    fun savePosition(
        gid: String?,
        index: Int,
        offset: Int,
        postId: String?,
        synchronous: Boolean = false
    ) {
        val prefix = getKeyPrefix(gid)
        val editor = prefs.edit()
            .putInt("${prefix}index", index)
            .putInt("${prefix}offset", offset)
            .putString("${prefix}post_id", postId)

        if (synchronous) {
            editor.commit()
        } else {
            editor.apply()
        }
    }

    fun getPosition(gid: String?): TimelinePosition {
        val prefix = getKeyPrefix(gid)
        val index = prefs.getInt("${prefix}index", 0)
        val offset = prefs.getInt("${prefix}offset", 0)
        val postId = prefs.getString("${prefix}post_id", null)
        return TimelinePosition(index = index, offset = offset, postId = postId)
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
