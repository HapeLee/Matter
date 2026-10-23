package org.kazusa.matter.data.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class WeiboUserDto(
    @Serializable(with = FlexibleLongSerializer::class) val id: Long? = null,
    @SerialName("idstr") val idStr: String? = null,
    @SerialName("screen_name") val screenName: String = "",
    val name: String? = null,
    val description: String? = null,
    @SerialName("profile_image_url") val profileImageUrl: String? = null,
    @SerialName("avatar_large") val avatarLarge: String? = null,
    @SerialName("avatar_hd") val avatarHd: String? = null,
    val verified: Boolean = false,
    @Serializable(with = FlexibleIntSerializer::class) @SerialName("verified_type") val verifiedType: Int? = null,
    @Serializable(with = FlexibleIntSerializer::class) @SerialName("verified_type_ext") val verifiedTypeExt: Int? = null,
    @Serializable(with = FlexibleIntSerializer::class) @SerialName("v_gold") val vGold: Int? = null,
    @SerialName("verified_reason") val verifiedReason: String? = null,
    @Serializable(with = FlexibleIntSerializer::class) val mbrank: Int? = null,
    @Serializable(with = FlexibleIntSerializer::class) val mbtype: Int? = null,
    @Serializable(with = FlexibleIntSerializer::class) val svip: Int? = null,
    @Serializable(with = FlexibleStringSerializer::class) @SerialName("followers_count") val followersCount: String = "",
    @Serializable(with = FlexibleStringSerializer::class) @SerialName("friends_count") val friendsCount: String = "",
    @Serializable(with = FlexibleStringSerializer::class) @SerialName("follow_count") val followCount: String = "",
    @Serializable(with = FlexibleStringSerializer::class) @SerialName("statuses_count") val statusesCount: String = "",
    /** 我是否关注了该用户；不同接口可能不下发，故用可空 + 弹性解析 */
    @Serializable(with = FlexibleBooleanSerializer::class) val following: Boolean? = null,
    /** 该用户是否关注了我 */
    @Serializable(with = FlexibleBooleanSerializer::class) @SerialName("follow_me") val followMe: Boolean? = null,
    @SerialName("cover_image_phone") val coverImagePhone: String? = null,
    // 以下字段移动端 userInfo 可能不下发，PC 端 profile/info 会补齐
    val location: String? = null,
    val gender: String? = null,
    val domain: String? = null,
    val url: String? = null,
    @SerialName("created_at") val createdAt: String? = null
) {
    val bestFollowersCount: String
        get() = followersCount.ifBlank { "0" }

    val bestFriendsCount: String
        get() = friendsCount.ifBlank { followCount.ifBlank { "0" } }

    val bestStatusesCount: String
        get() = statusesCount.ifBlank { "0" }

    val bestAvatar: String
        get() = avatarHd ?: avatarLarge ?: profileImageUrl ?: "https://tvax1.sinaimg.cn/default/images/default_avatar_male_180.gif"
}

@Serializable
data class WeiboConfigResponse(
    val ok: Int = 0,
    val data: WeiboConfigData? = null
)

@Serializable
data class WeiboConfigData(
    val login: Boolean = false,
    val uid: String? = null,
    val user: WeiboUserDto? = null
)

