package org.kazusa.matter.ui.group

import androidx.compose.runtime.Immutable
import org.kazusa.matter.data.model.WeiboGroupDto

@Immutable
data class GroupUiState(
    val groups: List<WeiboGroupDto> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
