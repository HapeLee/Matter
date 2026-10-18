package org.kazusa.matter.ui.group

import androidx.compose.runtime.Immutable
import org.kazusa.matter.data.model.WeiboGroupDto

@Immutable
data class GroupUiState(
    val groups: List<WeiboGroupDto> = emptyList(),
    val isLoading: Boolean = false,
    val isOperating: Boolean = false,
    val errorMessage: String? = null,
    val userMessage: String? = null
) {
    /**
     * 在动态顶部展示的分组（visible == 1）
     */
    val displayedGroups: List<WeiboGroupDto>
        get() = groups.filter { it.visible == 1 || it.identifier == "all" }

    /**
     * 未在动态顶部展示的分组（visible == 0 且非全部关注）
     */
    val hiddenGroups: List<WeiboGroupDto>
        get() = groups.filter { it.visible == 0 && it.identifier != "all" }
}
