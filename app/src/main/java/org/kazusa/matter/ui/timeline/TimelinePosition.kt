package org.kazusa.matter.ui.timeline

import org.kazusa.matter.data.model.WeiboStatusDto

internal fun resolveTimelineRestoreIndex(
    posts: List<WeiboStatusDto>,
    lastReadPostId: String?,
    savedPostId: String?,
    savedIndex: Int
): Int {
    if (posts.isEmpty()) return 0
    val dividerIndex = lastReadPostId?.let { id -> posts.indexOfFirst { (it.idStr ?: it.id?.toString()) == id } } ?: -1
    val postIndex = savedPostId?.let { id -> posts.indexOfFirst { (it.idStr ?: it.id?.toString()) == id } } ?: -1
    return if (postIndex >= 0) {
        postIndex + if (dividerIndex in 0..postIndex) 1 else 0
    } else {
        savedIndex.coerceIn(0, posts.lastIndex + if (dividerIndex >= 0) 1 else 0)
    }
}
