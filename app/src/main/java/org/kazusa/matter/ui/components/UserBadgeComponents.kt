package org.kazusa.matter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.kazusa.matter.data.model.WeiboUserDto
import org.kazusa.matter.ui.navigation.weiboAvatarBadgeSharedElement
import coil3.compose.AsyncImage

/**
 * 微博认证等级与类型
 */
enum class WeiboVerifyType {
    NONE,
    GOLD_V,    // 金V：头部大V个人创作者（月阅读超千万）
    YELLOW_V,  // 黄V：个人认证（知名博主、娱乐明星、专家）
    BLUE_V,    // 蓝V：机构/企业/媒体/政务/校园官方认证
    RED_STAR   // 微博达人 / 红人 (verified_type == 220)
}

/**
 * 判断用户的微博认证类型
 */
fun WeiboUserDto?.getVerifyType(): WeiboVerifyType {
    if (this == null) return WeiboVerifyType.NONE
    val vType = verifiedType ?: if (verified) 0 else -1
    if (vType == -1 && !verified) return WeiboVerifyType.NONE

    // 1. 金V：verified_type == 0 且 (v_gold == 1 或 verified_type_ext in 1..2)
    if (vType == 0 && (vGold == 1 || verifiedTypeExt == 1 || verifiedTypeExt == 2)) {
        return WeiboVerifyType.GOLD_V
    }
    // 2. 黄V：个人认证 (verified_type == 0)
    if (vType == 0) {
        return WeiboVerifyType.YELLOW_V
    }
    // 3. 蓝V：机构/官方/企业/政务/媒体 (verified_type in 1..7)
    if (vType in 1..7) {
        return WeiboVerifyType.BLUE_V
    }
    // 4. 微博达人 (verified_type == 220)
    if (vType == 220) {
        return WeiboVerifyType.RED_STAR
    }
    // 5. 兜底已认证
    if (verified) {
        return WeiboVerifyType.YELLOW_V
    }
    return WeiboVerifyType.NONE
}

/**
 * 大V认证徽标组件 (Material 3 原生矢量图标：Verified / Stars)
 * 采用 M3 标准 Verified 徽标图标，完美对齐昵称文本基线。
 *
 * [backgroundColor] 非空时会在图标背后铺一层实心圆形垫底。
 * Verified 徽标本身是「花瓣形/六角螺丝」的不规则轮廓，直接用 `border(CircleShape)`
 * 只画一圈描边，圆与花瓣之间的空隙会透出头像或封面；改用实心垫底后，
 * 徽标边缘到圆形轮廓之间是完整的底色，不再漏背景。
 */
@Composable
fun UserVerifyBadge(
    verifyType: WeiboVerifyType,
    modifier: Modifier = Modifier,
    size: Dp = 15.dp,
    /** 徽标垫底色；为空时保持透明（用于昵称旁的纯图标场景） */
    backgroundColor: Color? = null,
    /** 垫底色在图标四周留出的边框宽度 */
    backgroundPadding: Dp = 2.dp
) {
    val icon = when (verifyType) {
        WeiboVerifyType.GOLD_V -> Triple(Icons.Default.Verified, Color(0xFFFFB300), "金V认证") // 琥珀金
        WeiboVerifyType.YELLOW_V -> Triple(Icons.Default.Verified, Color(0xFFFFA000), "黄V认证") // 经典明黄
        WeiboVerifyType.BLUE_V -> Triple(Icons.Default.Verified, Color(0xFF1E88E5), "蓝V官方认证") // 官方蓝
        WeiboVerifyType.RED_STAR -> Triple(Icons.Default.Stars, Color(0xFFE53935), "微博达人") // 达人红
        WeiboVerifyType.NONE -> return
    }

    Box(
        modifier = if (backgroundColor != null) {
            modifier
                .size(size + backgroundPadding * 2)
                .background(backgroundColor, CircleShape)
        } else {
            modifier.size(size)
        },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon.first,
            contentDescription = icon.third,
            tint = icon.second,
            modifier = Modifier.size(size)
        )
    }
}

/**
 * 带有大V角标覆盖的头像组件 (个人主页等场景使用)
 */
@Composable
fun UserAvatarWithBadge(
    avatarUrl: String?,
    verifyType: WeiboVerifyType,
    modifier: Modifier = Modifier,
    avatarSize: Dp = 38.dp,
    badgeSize: Dp = 14.dp,
    contentDescription: String? = null,
    /** 自定义图片模型（如带共享缓存键的 Coil ImageRequest）；为空时退回 [avatarUrl] */
    avatarModel: Any? = null,
    /** 施加在头像图片本身上的修饰符（共享元素动画等） */
    avatarModifier: Modifier = Modifier,
    /** 认证角标的垫底色：盖住头像，避免从不规则徽标边缘透出 */
    badgeBackgroundColor: Color? = null,
    /** 认证角标垫底色的边框宽度 */
    badgeBackgroundPadding: Dp = 2.dp,
    /** 头像维度共享 key：传入后认证角标也会参与头像 → 主页的共享元素动画 */
    badgeSharedAvatarKey: String? = null,
    /** 角标共享元素的来源宿主 key（列表卡片），主页目标传空 */
    badgeSharedOwnerKey: String? = null,
    onClick: () -> Unit = {}
) {
    val fallback = "https://tvax1.sinaimg.cn/default/images/default_avatar_male_180.gif"
    Box(
        modifier = modifier.size(avatarSize),
        contentAlignment = Alignment.BottomEnd
    ) {
        AsyncImage(
            model = avatarModel ?: avatarUrl ?: fallback,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(avatarSize)
                .then(avatarModifier)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick)
        )

        if (verifyType != WeiboVerifyType.NONE) {
            UserVerifyBadge(
                verifyType = verifyType,
                size = badgeSize,
                backgroundColor = badgeBackgroundColor ?: MaterialTheme.colorScheme.surface,
                backgroundPadding = badgeBackgroundPadding,
                modifier = if (badgeSharedAvatarKey.isNullOrBlank()) {
                    Modifier
                } else {
                    Modifier.weiboAvatarBadgeSharedElement(
                        avatarKey = badgeSharedAvatarKey,
                        ownerKey = badgeSharedOwnerKey
                    )
                }
            )
        }
    }
}

/**
 * 微博会员 (VIP / SVIP) 胶囊微标
 * 高度紧凑贴合文字
 */
@Composable
fun UserVipBadge(
    user: WeiboUserDto?,
    modifier: Modifier = Modifier
) {
    if (user == null) return

    val isSvip = user.svip == 1
    val isVip = (user.mbtype != null && user.mbtype > 2) || (user.mbrank != null && user.mbrank > 0)

    when {
        isSvip -> MatterTag(
            text = "SVIP",
            modifier = modifier,
            containerColor = Color(0xFFD32F2F),
            contentColor = Color(0xFFFFE082),
            style = MaterialTheme.typography.labelSmallEmphasized,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
        )

        isVip -> MatterTag(
            text = if (user.mbrank != null && user.mbrank > 0) "VIP${user.mbrank}" else "VIP",
            modifier = modifier,
            containerColor = Color(0xFFFF9800),
            contentColor = Color.White,
            style = MaterialTheme.typography.labelSmallEmphasized,
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
        )
    }
}

/**
 * 博主认证头衔标签 (卡片+文字)
 * 高度紧凑贴合文字，小巧精致
 */
@Composable
fun BloggerSuffixTag(
    text: String,
    modifier: Modifier = Modifier
) {
    if (text.isBlank()) return
    MatterTag(
        text = text,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        style = MaterialTheme.typography.labelSmallEmphasized,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
    )
}

/**
 * 评论区原作者「博主」专属身份标签
 */
@Composable
fun AuthorBadgeTag(
    modifier: Modifier = Modifier
) {
    MatterTag(
        text = "博主",
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        style = MaterialTheme.typography.labelSmallEmphasized,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
    )
}
