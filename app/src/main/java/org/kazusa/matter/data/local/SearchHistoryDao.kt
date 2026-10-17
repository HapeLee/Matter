package org.kazusa.matter.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 搜索记录 DAO：按登录账号隔离、按最近时间倒序、同关键词只保留一条。
 */
@Dao
interface SearchHistoryDao {

    /** 搜索词为空时展示的记录列表（最近 [limit] 条） */
    @Query(
        """
        SELECT * FROM search_history
        WHERE (uid IS NULL OR uid = :uid)
        ORDER BY create_at DESC
        LIMIT :limit
        """
    )
    fun observe(uid: String?, limit: Int): Flow<List<SearchHistoryEntity>>

    /** 去重用：同账号下同关键词只留一条，重复搜索只更新时间 */
    @Query("SELECT * FROM search_history WHERE keyword = :keyword AND (uid IS NULL OR uid = :uid) LIMIT 1")
    suspend fun findByKeyword(keyword: String, uid: String?): SearchHistoryEntity?

    @Insert
    suspend fun insert(entity: SearchHistoryEntity): Long

    @Query("UPDATE search_history SET create_at = :createAt WHERE id = :id")
    suspend fun touch(id: Long, createAt: Long)

    @Query("DELETE FROM search_history WHERE keyword = :keyword AND (uid IS NULL OR uid = :uid)")
    suspend fun deleteByKeyword(keyword: String, uid: String?)

    @Query("DELETE FROM search_history WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM search_history")
    suspend fun clearAll()

    @Query("SELECT count(*) FROM search_history WHERE (uid IS NULL OR uid = :uid)")
    suspend fun countForUid(uid: String?): Int

    /** 取最旧的若干条 id，用于容量裁剪 */
    @Query("SELECT id FROM search_history WHERE (uid IS NULL OR uid = :uid) ORDER BY create_at ASC LIMIT :limit")
    suspend fun oldestIds(uid: String?, limit: Int): List<Long>
}
