package org.kazusa.matter.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 浏览历史 DAO。
 * SQL 语义：按登录账号（`uid is null or uid = ?`）隔离、
 * 关键词 LIKE 检索、位掩码分类过滤、`create_at DESC` 排序 + LIMIT/OFFSET 分页。
 */
@Dao
interface BrowsingHistoryDao {

    /** 列表 / 搜索：一次覆盖「全部 / 关键词 / 分类」三种过滤组合 */
    @Query(
        """
        SELECT * FROM browsing_history
        WHERE (uid IS NULL OR uid = :uid)
          AND (:keyword = '' OR total_text LIKE '%' || :keyword || '%' OR username LIKE '%' || :keyword || '%')
          AND (:categoryMask = 0 OR (category & :categoryMask) > 0)
        ORDER BY create_at DESC
        LIMIT :limit OFFSET :offset
        """
    )
    fun observe(
        uid: String?,
        keyword: String,
        categoryMask: Int,
        limit: Int,
        offset: Int
    ): Flow<List<BrowsingHistoryEntity>>

    /** 与 [observe] 相同条件下的总条数，用于「共 x 条」与分页判断 */
    @Query(
        """
        SELECT count(*) FROM browsing_history
        WHERE (uid IS NULL OR uid = :uid)
          AND (:keyword = '' OR total_text LIKE '%' || :keyword || '%' OR username LIKE '%' || :keyword || '%')
          AND (:categoryMask = 0 OR (category & :categoryMask) > 0)
        """
    )
    fun observeCount(uid: String?, keyword: String, categoryMask: Int): Flow<Int>

    /** 去重用：同一对象（微博 id / 博主 uid）在同一个账号下只保留一条 */
    @Query("SELECT * FROM browsing_history WHERE status_id = :statusId AND (uid IS NULL OR uid = :uid) LIMIT 1")
    suspend fun findByStatusId(statusId: String, uid: String?): BrowsingHistoryEntity?

    @Insert
    suspend fun insert(entity: BrowsingHistoryEntity): Long

    /** 命中重复条目时只刷新浏览时间与快照，避免列表里堆同一篇微博 */
    @Query(
        """
        UPDATE browsing_history
        SET create_at = :createAt, username = :username, total_text = :totalText, json = :json
        WHERE id = :id
        """
    )
    suspend fun updateSnapshot(
        id: Long,
        createAt: Long,
        username: String?,
        totalText: String?,
        json: String?
    )

    @Query("DELETE FROM browsing_history WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM browsing_history")
    suspend fun clearAll()

    @Query("SELECT count(*) FROM browsing_history WHERE (uid IS NULL OR uid = :uid)")
    suspend fun countForUid(uid: String?): Int

    /** 取最旧的若干条 id（配合 [deleteByIds] 做容量裁剪） */
    @Query(
        """
        SELECT id FROM browsing_history
        WHERE (uid IS NULL OR uid = :uid)
        ORDER BY create_at ASC
        LIMIT :limit OFFSET :offset
        """
    )
    suspend fun oldestIds(uid: String?, limit: Int, offset: Int): List<Long>
}
