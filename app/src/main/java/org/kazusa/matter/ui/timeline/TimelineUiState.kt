package org.kazusa.matter.ui.timeline

import androidx.compose.runtime.Immutable
import org.kazusa.matter.data.model.WeiboStatusDto

@Immutable
data class TimelineUiState(
    val posts: List<WeiboStatusDto> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null,
    val currentGid: String? = null,
    val currentGroupName: String = "全部微博",
    val page: Int = 1,
    val hasMore: Boolean = true,
    val unreadCount: Int = 0,
    val refreshTip: String? = null,
    val lastReadPostId: String? = null,
    val pendingRestoreIndex: Int? = null,
    val pendingRestoreOffset: Int = 0,
    val scrollToTopTrigger: Long = 0L
)
