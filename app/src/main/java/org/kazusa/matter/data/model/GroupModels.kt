package org.kazusa.matter.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 关注分组类型。
 *
 * 网页端分组 id 的前 5 位就是类型（`weibo.com/ajax/feed/allGroups` 下发，分组流也按同一规则
 * 选接口），所以客户端按 gid 前缀识别，不再自己造 `friends` / `original` 这类字符串 id。
 * 只有「特别关注」的 gid 前缀尚未核对，仍按名称兜底。
 */
enum class WeiboGroupKind {
    /** 全部关注（客户端固定用 `all`，服务端 gid 是 `10001` + uid）。 */
    ALL,
    SPECIAL,
    MUTUAL,
    QUIET,
    LATEST,
    ORIGINAL,
    VIDEO,
    GROUP_POST,
    V_PLUS,
    CUSTOM
}

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

    val kind: WeiboGroupKind
        get() {
            if (identifier == "all") return WeiboGroupKind.ALL
            when (identifier.take(5)) {
                "10001" -> return WeiboGroupKind.ALL
                "10005" -> return WeiboGroupKind.QUIET
                "10009" -> return WeiboGroupKind.MUTUAL
                "10010" -> return WeiboGroupKind.GROUP_POST
                "10013" -> return WeiboGroupKind.V_PLUS
                "11000" -> return WeiboGroupKind.LATEST
                "11001" -> return WeiboGroupKind.ORIGINAL
                "11002" -> return WeiboGroupKind.VIDEO
            }
            // 自建分组的 gid 是独立的随机 id，落在这里即 CUSTOM
            return if (displayName.contains("特别关注")) WeiboGroupKind.SPECIAL else WeiboGroupKind.CUSTOM
        }

    /** 系统分组：不可重命名 / 删除（服务端也不接受）。 */
    val isSystem: Boolean
        get() = kind != WeiboGroupKind.CUSTOM
}
