package org.kazusa.matter.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import org.kazusa.matter.core.datastore.AuthPreferences
import org.kazusa.matter.data.local.SearchHistoryDao
import org.kazusa.matter.data.local.SearchHistoryEntity

/**
 * 本地搜索记录仓库。
 *
 * 只做本地存取，不发任何网络请求：在搜索结果页提交搜索时记录关键词，
 * 搜索框为空时把这些记录展示出来。行为与浏览历史保持一致：
 * 按登录账号隔离、同关键词只保留最近一次、超出容量删最旧的。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SearchHistoryRepository(
    private val dao: SearchHistoryDao,
    private val authPreferences: AuthPreferences,
    private val maxEntriesPerAccount: Int = DEFAULT_MAX_ENTRIES
) {

    private fun currentUidFlow(): Flow<String?> = authPreferences.sessionFlow.map { session ->
        session.uid.takeIf { it.isNotBlank() && it != GUEST_UID }
    }

    private suspend fun currentUid(): String? = authPreferences.sessionFlow.firstOrNull()?.uid
        ?.takeIf { it.isNotBlank() && it != GUEST_UID }

    /** 观察当前账号的搜索记录（最近的在前） */
    fun observe(limit: Int = DEFAULT_LIMIT): Flow<List<SearchHistoryEntity>> =
        currentUidFlow().flatMapLatest { uid -> dao.observe(uid = uid, limit = limit) }

    /** 记录一次搜索；空白关键词直接忽略 */
    suspend fun record(rawKeyword: String) {
        val keyword = rawKeyword.trim()
        if (keyword.isBlank()) return

        val uid = currentUid()
        val now = System.currentTimeMillis()

        val existing = dao.findByKeyword(keyword, uid)
        if (existing != null) {
            dao.touch(id = existing.id, createAt = now)
        } else {
            dao.insert(SearchHistoryEntity(keyword = keyword, uid = uid, createAt = now))
        }
        trimToLimit(uid)
    }

    suspend fun remove(keyword: String) {
        val uid = currentUid()
        dao.deleteByKeyword(keyword = keyword, uid = uid)
    }

    suspend fun clear() {
        dao.clearAll()
    }

    private suspend fun trimToLimit(uid: String?) {
        val overflow = dao.countForUid(uid) - maxEntriesPerAccount
        if (overflow <= 0) return
        dao.deleteByIds(dao.oldestIds(uid = uid, limit = overflow))
    }

    companion object {
        /** 空搜索词时展示的最大条数 */
        const val DEFAULT_LIMIT = 20

        /** 单账号最多保留的搜索记录条数 */
        const val DEFAULT_MAX_ENTRIES = 50

        private const val GUEST_UID = "guest"
    }
}
