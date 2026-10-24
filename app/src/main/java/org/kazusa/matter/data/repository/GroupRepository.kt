package org.kazusa.matter.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import org.kazusa.matter.core.datastore.AuthPreferences
import org.kazusa.matter.core.datastore.UserSession
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.data.model.WeiboGroupDto

class GroupRepository internal constructor(
    private val currentSession: suspend () -> UserSession,
    private val fetchGroups: suspend (cookie: String) -> ApiResult<List<WeiboGroupDto>>,
    private val createRemoteGroup: suspend (
        name: String,
        description: String,
        cookie: String
    ) -> ApiResult<WeiboGroupDto>,
    private val renameRemoteGroup: suspend (gid: String, name: String, cookie: String) -> ApiResult<Unit>,
    private val destroyRemoteGroup: suspend (gid: String, cookie: String) -> ApiResult<Unit>,
    private val pushRemoteOrder: suspend (order: List<String>, cookie: String) -> ApiResult<Unit>,
    private val hiddenGroupIdsFlow: Flow<Set<String>>,
    private val customOrderFlow: Flow<List<String>>,
    private val persistGroupVisibility: suspend (gid: String, isVisible: Boolean) -> Unit,
    private val persistCustomOrder: suspend (order: List<String>) -> Unit
) {

    constructor(authPreferences: AuthPreferences, apiService: WeiboApiService) : this(
        currentSession = { authPreferences.sessionFlow.first() },
        fetchGroups = apiService::getFollowGroups,
        createRemoteGroup = { name, description, cookie ->
            apiService.createGroup(name = name, description = description, cookie = cookie)
        },
        renameRemoteGroup = { gid, name, cookie ->
            apiService.updateGroupName(gid = gid, name = name, cookie = cookie)
        },
        destroyRemoteGroup = { gid, cookie ->
            apiService.destroyGroup(gid = gid, cookie = cookie)
        },
        pushRemoteOrder = { order, cookie -> apiService.setGroupOrder(order, cookie) },
        hiddenGroupIdsFlow = authPreferences.hiddenGroupIdsFlow,
        customOrderFlow = authPreferences.groupCustomOrderFlow,
        persistGroupVisibility = authPreferences::setGroupVisibility,
        persistCustomOrder = authPreferences::saveGroupCustomOrder
    )

    private val _rawGroupsFlow = MutableStateFlow<List<WeiboGroupDto>>(emptyList())

    /**
     * 缓存属主 uid：分组列表带账号身份，属主不符的缓存一律视为没有。
     * 换号后（包括在途请求写回前）都不能把旧账号的分组当成本账号数据。
     */
    private var cacheOwnerUid: String? = null

    /**
     * 请求代次：新的 getGroups 启动即推进。迟到的旧结果（成功或失败兜底）
     * 不再写缓存——否则会经由 groupsFlow 把过期列表再推给界面。
     */
    private var fetchGeneration = 0

    /**
     * 实时观察排序与显示状态归一化后的分组列表
     */
    val groupsFlow: Flow<List<WeiboGroupDto>> = combine(
        _rawGroupsFlow,
        hiddenGroupIdsFlow,
        customOrderFlow
    ) { rawGroups, hiddenIds, customOrder ->
        sortAndApplyVisibility(rawGroups, hiddenIds, customOrder)
    }

    /**
     * 获取关注分组列表（支持强制从远端刷新）
     */
    suspend fun getGroups(forceRefresh: Boolean = false): ApiResult<List<WeiboGroupDto>> {
        val session = currentSession()
        val cached = _rawGroupsFlow.value
        val cacheBelongsToCurrentUser = cacheOwnerUid == session.uid
        if (!forceRefresh && cached.isNotEmpty() && cacheBelongsToCurrentUser) {
            return ApiResult.Success(
                sortAndApplyVisibility(cached, hiddenGroupIdsFlow.first(), customOrderFlow.first())
            )
        }

        val cookie = session.cookieHeader
        val generation = ++fetchGeneration
        return when (val result = fetchGroups(cookie)) {
            is ApiResult.Success -> {
                // 请求在途期间可能换了账号：写缓存前再校验一次会话身份
                if (currentSession().uid != session.uid) {
                    return ApiResult.Error(message = "账号已切换，请重新加载分组")
                }
                // 已有更新的请求在跑：迟到的旧结果只交回调用方，不写缓存
                if (generation != fetchGeneration) return result
                cacheOwnerUid = session.uid
                _rawGroupsFlow.value = result.data
                ApiResult.Success(
                    sortAndApplyVisibility(result.data, hiddenGroupIdsFlow.first(), customOrderFlow.first())
                )
            }
            is ApiResult.Error -> {
                // 失败兜底同样只信属主匹配的缓存，不把旧账号分组当本账号的兜底展示
                if (cached.isNotEmpty() && cacheBelongsToCurrentUser) {
                    ApiResult.Success(
                        sortAndApplyVisibility(cached, hiddenGroupIdsFlow.first(), customOrderFlow.first())
                    )
                } else {
                    result
                }
            }
            ApiResult.Loading -> ApiResult.Loading
        }
    }

    /**
     * 获取应在动态顶部展示的分组（即 visible == 1 的非隐藏分组）
     */
    suspend fun getDisplayableGroups(): List<WeiboGroupDto> {
        val groups = groupsFlow.first()
        return if (groups.isEmpty()) {
            val fresh = getGroups()
            if (fresh is ApiResult.Success) {
                fresh.data.filter { it.visible == 1 || it.identifier == "all" }
            } else {
                listOf(WeiboGroupDto(id = 0, gid = "all", name = "全部关注", memberCount = 0, visible = 1))
            }
        } else {
            groups.filter { it.visible == 1 || it.identifier == "all" }
        }
    }

    /**
     * 创建分组
     */
    suspend fun createGroup(name: String, description: String = ""): ApiResult<WeiboGroupDto> {
        val session = currentSession()
        val cookie = session.cookieHeader
        return when (val result = createRemoteGroup(name, description, cookie)) {
            is ApiResult.Success -> {
                // 换号后只把结果交回调用方，不写入旧账号的缓存
                if (currentSession().uid != session.uid) return result
                val newGroup = result.data.copy(visible = 1)
                val current = _rawGroupsFlow.value.toMutableList()
                if (current.none { it.identifier == newGroup.identifier }) {
                    current.add(newGroup)
                    _rawGroupsFlow.value = current
                }
                ApiResult.Success(newGroup)
            }
            is ApiResult.Error -> result
            ApiResult.Loading -> ApiResult.Loading
        }
    }

    /**
     * 重命名分组
     */
    suspend fun updateGroup(gid: String, newName: String): ApiResult<Unit> {
        val session = currentSession()
        val cookie = session.cookieHeader
        val result = renameRemoteGroup(gid, newName, cookie)
        if (result is ApiResult.Success && currentSession().uid == session.uid) {
            val updated = _rawGroupsFlow.value.map { group ->
                if (group.identifier == gid) {
                    group.copy(name = newName, title = newName)
                } else {
                    group
                }
            }
            _rawGroupsFlow.value = updated
        }
        return result
    }

    /**
     * 删除分组
     */
    suspend fun deleteGroup(gid: String): ApiResult<Unit> {
        val session = currentSession()
        val cookie = session.cookieHeader
        val result = destroyRemoteGroup(gid, cookie)
        if (result is ApiResult.Success) {
            if (currentSession().uid == session.uid) {
                _rawGroupsFlow.value = _rawGroupsFlow.value.filterNot { it.identifier == gid }
            }
            // 移除自定义排序和隐藏列表中的对应 gid（分组偏好与账号无关，照常清理）
            persistGroupVisibility(gid, true)
            val currentOrder = customOrderFlow.first()
            if (currentOrder.contains(gid)) {
                persistCustomOrder(currentOrder.filterNot { it == gid })
            }
        }
        return result
    }

    /**
     * 切换分组在动态顶部的展示/隐藏状态
     */
    suspend fun setGroupVisibility(gid: String, isVisible: Boolean) {
        persistGroupVisibility(gid, isVisible)
    }

    /**
     * 移动排序
     */
    suspend fun moveGroup(fromIndex: Int, toIndex: Int) {
        val current = groupsFlow.first().toMutableList()
        if (fromIndex !in current.indices || toIndex !in current.indices || fromIndex == toIndex) return
        val item = current.removeAt(fromIndex)
        current.add(toIndex, item)

        val newOrder = current.map { it.identifier }
        persistCustomOrder(newOrder)

        val session = currentSession()
        pushRemoteOrder(newOrder, session.cookieHeader)
    }

    /**
     * 拖拽重新排序，持久化保存并同步至远端
     */
    suspend fun reorderGroups(newOrderedList: List<WeiboGroupDto>) {
        val newOrder = newOrderedList.map { it.identifier }
        persistCustomOrder(newOrder)

        val session = currentSession()
        pushRemoteOrder(newOrder, session.cookieHeader)
    }

    /**
     * 切换账号时由导航根调用：清空缓存与属主。
     * 旧账号在途请求的结果也会被 getGroups 的属主校验挡在缓存之外。
     */
    fun clear() {
        cacheOwnerUid = null
        _rawGroupsFlow.value = emptyList()
    }

    private fun sortAndApplyVisibility(
        rawGroups: List<WeiboGroupDto>,
        hiddenIds: Set<String>,
        customOrder: List<String>
    ): List<WeiboGroupDto> {
        if (rawGroups.isEmpty()) return emptyList()

        // 1. 设置 visible 标志（1: 展示在顶部, 0: 隐藏）
        val withVisibility = rawGroups.map { g ->
            val isHidden = hiddenIds.contains(g.identifier) && g.identifier != "all"
            g.copy(visible = if (isHidden) 0 else 1)
        }

        // 2. 根据用户自定义排序
        if (customOrder.isEmpty()) {
            return withVisibility
        }

        val allGroup = withVisibility.firstOrNull { it.identifier == "all" }
        val restGroups = withVisibility.filterNot { it.identifier == "all" }

        val orderMap = customOrder.withIndex().associate { it.value to it.index }
        val sortedRest = restGroups.sortedWith(
            compareBy<WeiboGroupDto> { orderMap[it.identifier] ?: Int.MAX_VALUE }
        )

        return if (allGroup != null) {
            listOf(allGroup) + sortedRest
        } else {
            sortedRest
        }
    }
}
