package org.kazusa.matter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.kazusa.matter.data.model.WeiboUserDto
import coil.compose.AsyncImage

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
 * 采用 M3 标准 Verified 徽标图标，完美对齐昵称文本基线
 */
@Composable
fun UserVerifyBadge(
    verifyType: WeiboVerifyType,
    modifier: Modifier = Modifier,
    size: Dp = 15.dp
) {
    when (verifyType) {
        WeiboVerifyType.GOLD_V -> {
            Icon(
                imageVector = Icons.Default.Verified,
                contentDescription = "金V认证",
                tint = Color(0xFFFFB300), // 琥珀金
                modifier = modifier.size(size)
            )
        }

        WeiboVerifyType.YELLOW_V -> {
            Icon(
                imageVector = Icons.Default.Verified,
                contentDescription = "黄V认证",
                tint = Color(0xFFFFA000), // 经典明黄
                modifier = modifier.size(size)
            )
        }

        WeiboVerifyType.BLUE_V -> {
            Icon(
                imageVector = Icons.Default.Verified,
                contentDescription = "蓝V官方认证",
                tint = Color(0xFF1E88E5), // 官方蓝
                modifier = modifier.size(size)
            )
        }

        WeiboVerifyType.RED_STAR -> {
            Icon(
                imageVector = Icons.Default.Stars,
                contentDescription = "微博达人",
                tint = Color(0xFFE53935), // 达人红
                modifier = modifier.size(size)
            )
        }

        WeiboVerifyType.NONE -> Unit
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
    onClick: () -> Unit = {}
) {
    val fallback = "https://tvax1.sinaimg.cn/default/images/default_avatar_male_180.gif"
    Box(
        modifier = modifier.size(avatarSize),
        contentAlignment = Alignment.BottomEnd
    ) {
        AsyncImage(
            model = avatarUrl ?: fallback,
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(avatarSize)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick)
        )

        if (verifyType != WeiboVerifyType.NONE) {
            UserVerifyBadge(
                verifyType = verifyType,
                size = badgeSize
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

    if (isSvip) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFFD32F2F))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "SVIP",
                color = Color(0xFFFFE082),
                style = MaterialTheme.typography.labelSmallEmphasized,
            )
        }
    } else if (isVip) {
        val rankText = if (user.mbrank != null && user.mbrank > 0) "VIP${user.mbrank}" else "VIP"
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFFFF9800))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = rankText,
                color = Color.White,
                style = MaterialTheme.typography.labelSmallEmphasized,
            )
        }
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
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(3.5.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.labelSmallEmphasized,
        )
    }
}

/**
 * 评论区原作者「博主」专属身份标签
 */
@Composable
fun AuthorBadgeTag(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(3.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "博主",
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.labelSmallEmphasized,
        )
    }
}
