package org.kazusa.matter.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 浏览历史表。
 * 本地 Room 数据表：
 *
 * ```
 * CREATE TABLE IF NOT EXISTS `browsing_history` (
 *   `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
 *   `status_id` TEXT, `username` TEXT, `total_text` TEXT, `uid` TEXT, `json` TEXT,
 *   `create_at` INTEGER NOT NULL, `category` INTEGER NOT NULL
 * )
 * CREATE INDEX IF NOT EXISTS `index_browsing_history_status_id` ON `browsing_history` (`status_id`)
 * ```
 */
@Entity(
    tableName = "browsing_history",
    indices = [Index(value = ["status_id"], name = "index_browsing_history_status_id")]
)
data class BrowsingHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    /** 被浏览对象的唯一标识：微博 id 或博主 uid。用于去重（同一条只保留最近一次） */
    @ColumnInfo(name = "status_id")
    val statusId: String? = null,

    /** 列表展示用的作者 / 博主昵称 */
    @ColumnInfo(name = "username")
    val username: String? = null,

    /** 纯文本摘要，供关键词检索（`total_text LIKE ?`） */
    @ColumnInfo(name = "total_text")
    val totalText: String? = null,

    /** 归属的登录账号 uid；为空表示全局条目 */
    @ColumnInfo(name = "uid")
    val uid: String? = null,

    /** 原始数据快照（JSON），用于离线渲染与还原 */
    @ColumnInfo(name = "json")
    val json: String? = null,

    /** 最近一次浏览时间（毫秒） */
    @ColumnInfo(name = "create_at")
    val createAt: Long = 0L,

    /** 分类位掩码，见 [BrowsingCategory] */
    @ColumnInfo(name = "category")
    val category: Int = BrowsingCategory.NONE
)
