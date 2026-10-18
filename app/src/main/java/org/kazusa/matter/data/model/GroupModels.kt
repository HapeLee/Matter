package org.kazusa.matter.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GroupListResponse(
    val ok: Int = 1,
    val lists: List<WeiboGroupDto> = emptyList(),
    @SerialName("total_number") val totalNumber: Int? = null,
    val data: List<WeiboGroupDto>? = null
)

@Serializable
data class WeiboGroupDto(
    val id: Long? = null,
    @SerialName("idstr") val idStr: String? = null,
    val gid: String? = null,
    val name: String = "",
    val title: String? = null,
    val description: String? = null,
    @SerialName("member_count") val memberCount: Int = 0,
    @SerialName("profile_image_url") val profileImageUrl: String? = null,
    val visible: Int = 0
) {
    val identifier: String
        get() = gid ?: idStr ?: id?.toString() ?: ""

    val displayName: String
        get() = if (name.isNotBlank()) name else title ?: "未命名分组"

    val isSystem: Boolean
        get() = identifier == "all" || identifier == "special" || identifier == "friends" ||
                identifier == "quiet" || identifier == "original" ||
                displayName == "全部关注" || displayName.contains("特别关注") ||
                displayName.contains("互相关注") || displayName.contains("悄悄关注") ||
                displayName.contains("原创") || displayName.contains("最新微博")
}
