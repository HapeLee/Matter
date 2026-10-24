package org.kazusa.matter.ui.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.repository.GroupRepository

class GroupViewModel(
    private val groupRepository: GroupRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(GroupUiState(isLoading = true))
    val uiState: StateFlow<GroupUiState> = _uiState.asStateFlow()

    /**
     * loadGroups 的在途任务与请求代次：新请求取消旧请求，
     * 晚到的旧结果还要过代次校验，不允许覆盖新结果。
     */
    private var loadJob: Job? = null
    private var loadGeneration = 0

    init {
        // 持续观察分组数据流（响应排序与隐藏/显示变更）
        viewModelScope.launch {
            groupRepository.groupsFlow.collect { list ->
                _uiState.update { current ->
                    current.copy(
                        groups = list,
                        isLoading = if (list.isNotEmpty()) false else current.isLoading
                    )
                }
            }
        }
        loadGroups(forceRefresh = false)
    }

    fun loadGroups(forceRefresh: Boolean = false) {
        loadJob?.cancel()
        val generation = ++loadGeneration
        loadJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            when (val result = groupRepository.getGroups(forceRefresh)) {
                is ApiResult.Success -> {
                    // 取消之外再校验代次：慢响应晚到时不覆盖新请求的结果
                    if (generation != loadGeneration) return@launch
                    _uiState.update {
                        it.copy(
                            groups = result.data,
                            isLoading = false,
                            errorMessage = null
                        )
                    }
                }
                is ApiResult.Error -> {
                    if (generation != loadGeneration) return@launch
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = if (it.groups.isEmpty()) result.message else null,
                            userMessage = if (it.groups.isNotEmpty()) "加载分组失败: ${result.message}" else null
                        )
                    }
                }
                ApiResult.Loading -> {}
            }
        }
    }

    /**
     * 新建分组
     */
    fun createGroup(name: String, onFinished: (Boolean) -> Unit = {}) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isOperating = true) }
            when (val result = groupRepository.createGroup(trimmed)) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isOperating = false,
                            userMessage = "分组「${result.data.displayName}」创建成功"
                        )
                    }
                    onFinished(true)
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isOperating = false,
                            userMessage = "创建失败: ${result.message}"
                        )
                    }
                    onFinished(false)
                }
                ApiResult.Loading -> {}
            }
        }
    }

    /**
     * 重命名分组
     */
    fun renameGroup(gid: String, newName: String, onFinished: (Boolean) -> Unit = {}) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isOperating = true) }
            when (val result = groupRepository.updateGroup(gid, trimmed)) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isOperating = false,
                            userMessage = "已重命名为「$trimmed」"
                        )
                    }
                    onFinished(true)
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isOperating = false,
                            userMessage = "修改失败: ${result.message}"
                        )
                    }
                    onFinished(false)
                }
                ApiResult.Loading -> {}
            }
        }
    }

    /**
     * 删除分组
     */
    fun deleteGroup(gid: String, groupName: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isOperating = true) }
            when (val result = groupRepository.deleteGroup(gid)) {
                is ApiResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isOperating = false,
                            userMessage = "已删除分组「$groupName」"
                        )
                    }
                }
                is ApiResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isOperating = false,
                            userMessage = "删除失败: ${result.message}"
                        )
                    }
                }
                ApiResult.Loading -> {}
            }
        }
    }

    /**
     * 切换在动态顶部的展示/隐藏
     */
    fun toggleGroupVisibility(gid: String, isVisible: Boolean) {
        viewModelScope.launch {
            groupRepository.setGroupVisibility(gid, isVisible)
            _uiState.update {
                it.copy(
                    userMessage = if (isVisible) "已添加到动态顶部展示" else "已从动态顶部隐藏"
                )
            }
        }
    }

    /**
     * 上移分组排序
     */
    fun moveGroupUp(gid: String) {
        val current = _uiState.value.groups
        val index = current.indexOfFirst { it.identifier == gid }
        if (index > 1) { // 索引 0 是全部关注，保持置顶
            viewModelScope.launch {
                groupRepository.moveGroup(index, index - 1)
            }
        }
    }

    /**
     * 下移分组排序
     */
    fun moveGroupDown(gid: String) {
        val current = _uiState.value.groups
        val index = current.indexOfFirst { it.identifier == gid }
        if (index in 1 until current.lastIndex) {
            viewModelScope.launch {
                groupRepository.moveGroup(index, index + 1)
            }
        }
    }

    /**
     * 实时响应手势拖动中的顺序交换（内存瞬时反馈）
     */
     fun onGroupDragged(fromIndexInDisplayed: Int, toIndexInDisplayed: Int) {
         if (fromIndexInDisplayed == toIndexInDisplayed) return
         val displayed = _uiState.value.displayedGroups.toMutableList()
         if (fromIndexInDisplayed !in displayed.indices || toIndexInDisplayed !in displayed.indices) return

         // 禁止移动「全部关注」（索引 0）或移动到第 0 位（替换全部关注）
         if (fromIndexInDisplayed == 0 || toIndexInDisplayed == 0) return

         val item = displayed.removeAt(fromIndexInDisplayed)
         displayed.add(toIndexInDisplayed, item)

         // 更新全部分组列表以保持当前最新顺序（前段为 displayed，后段为 hidden）
         val hidden = _uiState.value.hiddenGroups
         val merged = displayed + hidden
         _uiState.update { it.copy(groups = merged) }
     }

    /**
     * 拖拽手势结束时，持久化顺序并同步微博远端
     */
    fun onDragFinished() {
        val currentGroups = _uiState.value.groups
        viewModelScope.launch {
            groupRepository.reorderGroups(currentGroups)
        }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }
}
