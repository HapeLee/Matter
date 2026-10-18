package org.kazusa.matter.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import org.kazusa.matter.core.datastore.AuthPreferences
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.data.model.WeiboGroupDto

class GroupRepository(
    private val authPreferences: AuthPreferences,
    private val apiService: WeiboApiService
) {
    private val _rawGroupsFlow = MutableStateFlow<List<WeiboGroupDto>>(emptyList())

    /**
     * 实时观察排序与显示状态归一化后的分组列表
     */
    val groupsFlow: Flow<List<WeiboGroupDto>> = combine(
        _rawGroupsFlow,
        authPreferences.hiddenGroupIdsFlow,
        authPreferences.groupCustomOrderFlow
    ) { rawGroups, hiddenIds, customOrder ->
        sortAndApplyVisibility(rawGroups, hiddenIds, customOrder)
    }

    /**
     * 获取关注分组列表（支持强制从远端刷新）
     */
    suspend fun getGroups(forceRefresh: Boolean = false): ApiResult<List<WeiboGroupDto>> {
        if (!forceRefresh && _rawGroupsFlow.value.isNotEmpty()) {
            val hiddenIds = authPreferences.hiddenGroupIdsFlow.first()
            val customOrder = authPreferences.groupCustomOrderFlow.first()
            return ApiResult.Success(sortAndApplyVisibility(_rawGroupsFlow.value, hiddenIds, customOrder))
        }

        val session = authPreferences.sessionFlow.first()
        val cookie = session.cookieHeader
        return when (val result = apiService.getFollowGroups(cookie)) {
            is ApiResult.Success -> {
                val groups = result.data
                _rawGroupsFlow.value = groups
                val hiddenIds = authPreferences.hiddenGroupIdsFlow.first()
                val customOrder = authPreferences.groupCustomOrderFlow.first()
                ApiResult.Success(sortAndApplyVisibility(groups, hiddenIds, customOrder))
            }
            is ApiResult.Error -> {
                if (_rawGroupsFlow.value.isNotEmpty()) {
                    val hiddenIds = authPreferences.hiddenGroupIdsFlow.first()
                    val customOrder = authPreferences.groupCustomOrderFlow.first()
                    ApiResult.Success(sortAndApplyVisibility(_rawGroupsFlow.value, hiddenIds, customOrder))
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
        val session = authPreferences.sessionFlow.first()
        val cookie = session.cookieHeader
        return when (val result = apiService.createGroup(name = name, description = description, cookie = cookie)) {
            is ApiResult.Success -> {
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
        val session = authPreferences.sessionFlow.first()
        val cookie = session.cookieHeader
        val result = apiService.updateGroupName(gid = gid, name = newName, cookie = cookie)
        if (result is ApiResult.Success) {
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
        val session = authPreferences.sessionFlow.first()
        val cookie = session.cookieHeader
        val result = apiService.destroyGroup(gid = gid, cookie = cookie)
        if (result is ApiResult.Success) {
            val updated = _rawGroupsFlow.value.filterNot { it.identifier == gid }
            _rawGroupsFlow.value = updated
            // 移除自定义排序和隐藏列表中的对应 gid
            authPreferences.setGroupVisibility(gid, true)
            val currentOrder = authPreferences.groupCustomOrderFlow.first()
            if (currentOrder.contains(gid)) {
                authPreferences.saveGroupCustomOrder(currentOrder.filterNot { it == gid })
            }
        }
        return result
    }

    /**
     * 切换分组在动态顶部的展示/隐藏状态
     */
    suspend fun setGroupVisibility(gid: String, isVisible: Boolean) {
        authPreferences.setGroupVisibility(gid, isVisible)
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
        authPreferences.saveGroupCustomOrder(newOrder)

        val session = authPreferences.sessionFlow.first()
        apiService.setGroupOrder(newOrder, session.cookieHeader)
    }

    /**
     * 拖拽重新排序，持久化保存并同步至远端
     */
    suspend fun reorderGroups(newOrderedList: List<WeiboGroupDto>) {
        val newOrder = newOrderedList.map { it.identifier }
        authPreferences.saveGroupCustomOrder(newOrder)

        val session = authPreferences.sessionFlow.first()
        apiService.setGroupOrder(newOrder, session.cookieHeader)
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
