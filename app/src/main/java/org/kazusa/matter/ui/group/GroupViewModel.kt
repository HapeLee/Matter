package org.kazusa.matter.ui.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import org.kazusa.matter.core.network.ApiResult
import org.kazusa.matter.data.repository.GroupRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class GroupViewModel(
    private val groupRepository: GroupRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(GroupUiState())
    val uiState: StateFlow<GroupUiState> = _uiState.asStateFlow()

    init {
        loadGroups()
    }

    fun loadGroups() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            when (val result = groupRepository.getGroups()) {
                is ApiResult.Success -> {
                    _uiState.value = GroupUiState(
                        groups = result.data,
                        isLoading = false
                    )
                }
                is ApiResult.Error -> {
                    _uiState.value = GroupUiState(
                        isLoading = false,
                        errorMessage = result.message
                    )
                }
                ApiResult.Loading -> {}
            }
        }
    }

    companion object {
        fun provideFactory(groupRepository: GroupRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return GroupViewModel(groupRepository) as T
                }
            }
    }
}
