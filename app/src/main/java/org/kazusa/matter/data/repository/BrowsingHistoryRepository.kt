package org.kazusa.matter.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.kazusa.matter.core.datastore.AuthPreferences
import org.kazusa.matter.core.util.WeiboTextFormatter
import org.kazusa.matter.data.local.BrowsingCategory
import org.kazusa.matter.data.local.BrowsingHistoryDao
import org.kazusa.matter.data.local.BrowsingHistoryEntity
import org.kazusa.matter.data.local.MatterDatabase
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.model.WeiboUserDto

/**
 * 本地浏览历史仓库。
 *
 * 浏览历史为**纯本地**实现，不依赖微博服务端接口，数据落本地 Room 数据库。
 *
 * 关键行为：
 * - 历史按登录账号隔离（`uid is null or uid = ?`），换号后互不可见；
 * - 同一对象（微博 id / 博主 uid）只保留最近一次浏览，重复进入刷新时间并前移；
 * - 关键词检索基于 `total_text`；
 * - 容量裁剪：同账号最多保留 [maxEntriesPerAccount] 条，超出时删除最旧的。
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BrowsingHistoryRepository(
    private val dao: BrowsingHistoryDao,
    private val authPreferences: AuthPreferences,
    private val json: Json,
    private val maxEntriesPerAccount: Int = DEFAULT_MAX_ENTRIES
) {

    /** 当前登录账号 uid；未登录 / 访客返回 null（null 表示只看全局条目） */
    private fun currentUidFlow(): Flow<String?> = authPreferences.sessionFlow.map { session ->
        session.uid.takeIf { it.isNotBlank() && it != GUEST_UID }
    }

    private suspend fun currentUid(): String? = authPreferences.sessionFlow.firstOrNull()?.uid
        ?.takeIf { it.isNotBlank() && it != GUEST_UID }

    /** 分页观察历史列表；[categoryMask] 为 0 或 [BrowsingCategory.ALL] 时不过滤分类 */
    fun observe(
        keyword: String = "",
        categoryMask: Int = BrowsingCategory.ALL,
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0
    ): Flow<List<BrowsingHistoryEntity>> = currentUidFlow().flatMapLatest { uid ->
        dao.observe(
            uid = uid,
            keyword = keyword.trim(),
            categoryMask = categoryMask,
            limit = limit,
            offset = offset
        )
    }

    /** 当前筛选条件下的总条数 */
    fun observeCount(
        keyword: String = "",
        categoryMask: Int = BrowsingCategory.ALL
    ): Flow<Int> = currentUidFlow().flatMapLatest { uid ->
        dao.observeCount(uid = uid, keyword = keyword.trim(), categoryMask = categoryMask)
    }

    suspend fun count(): Int = dao.countForUid(currentUid())

    suspend fun delete(ids: List<Long>) {
        if (ids.isEmpty()) return
        dao.deleteByIds(ids)
    }

    suspend fun clear() {
        dao.clearAll()
    }

    /**
     * 记录一次「微博正文」浏览。
     *
     * 摘要与 payload 的正则清洗、整条 DTO 的 JSON 序列化都在 IO 线程做：
     * 这个方法紧跟详情请求成功后被调用，若在主线程执行会砸进页面转场动画中段。
     */
    suspend fun recordPost(post: WeiboStatusDto, category: Int = BrowsingCategory.POST) {
        val targetId = post.idStr ?: post.mid ?: post.id?.toString()
        if (targetId.isNullOrBlank()) return

        withContext(Dispatchers.IO) {
            val username = post.user?.screenName?.takeIf { it.isNotBlank() }

            upsert(
                statusId = targetId,
                username = username,
                // 摘要只存正文纯文本：昵称单独在 username 列，检索时两列都会命中，
                // 避免列表里「昵称：正文」重复显示昵称。
                totalText = WeiboTextFormatter.formatHtmlText(post.text).ifBlank { null },
                payload = encode(post),
                category = category
            )
        }
    }

    /** 记录一次「博主主页」浏览；序列化与 [recordPost] 一样放 IO 线程。 */
    suspend fun recordUser(user: WeiboUserDto, category: Int = BrowsingCategory.USER) {
        val targetId = user.idStr ?: user.id?.toString()
        if (targetId.isNullOrBlank()) return
        // getUserProfile 完全取不到资料时会返回「博主 xxx」占位对象，这种情况不写历史
        if (user.screenName.isBlank() || user.screenName.startsWith("博主 ")) return

        withContext(Dispatchers.IO) {
            val username = user.screenName.takeIf { it.isNotBlank() }

            upsert(
                statusId = targetId,
                username = username,
                totalText = user.description?.trim()?.ifBlank { null },
                payload = encode(user),
                category = category
            )
        }
    }

    private suspend fun upsert(
        statusId: String,
        username: String?,
        totalText: String?,
        payload: String?,
        category: Int
    ) {
        // 会话读取放在事务外：不要在持有数据库事务时去等 DataStore / 解密
        val uid = currentUid()
        // 查重、写入与容量裁剪收在一个事务里（见 BrowsingHistoryDao.upsertAndTrim）
        dao.upsertAndTrim(
            statusId = statusId,
            uid = uid,
            username = username,
            totalText = totalText,
            json = payload,
            category = category,
            createAt = System.currentTimeMillis(),
            maxEntries = maxEntriesPerAccount
        )
    }

    private fun encode(post: WeiboStatusDto): String? =
        runCatching { json.encodeToString(WeiboStatusDto.serializer(), post) }.getOrNull()

    private fun encode(user: WeiboUserDto): String? =
        runCatching { json.encodeToString(WeiboUserDto.serializer(), user) }.getOrNull()

    companion object {
        /** 单页条数 */
        const val DEFAULT_PAGE_SIZE = 30

        /** 单账号最多保留的历史条数（自动容量裁剪） */
        const val DEFAULT_MAX_ENTRIES = 500

        private const val GUEST_UID = "guest"
    }
}
