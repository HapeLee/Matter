package org.kazusa.matter.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 搜索记录表（本地）。
 *
 * 本地搜索历史持久化表，按当前登录账号 uid 隔离。
 */
@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    /** 搜索关键词（已 trim，非空） */
    @ColumnInfo(name = "keyword")
    val keyword: String = "",

    /** 归属的登录账号 uid；为空表示全局条目 */
    @ColumnInfo(name = "uid")
    val uid: String? = null,

    /** 最近一次搜索时间（毫秒） */
    @ColumnInfo(name = "create_at")
    val createAt: Long = 0L
)
