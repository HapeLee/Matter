package org.kazusa.matter.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape


import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.kazusa.matter.data.cache.UserAvatarCache
import org.kazusa.matter.data.model.WeiboSearchCardInfo
import org.kazusa.matter.data.model.WeiboSearchChip
import org.kazusa.matter.data.model.WeiboSearchChipGroup
import org.kazusa.matter.ui.navigation.LocalSharedElementTarget
import org.kazusa.matter.ui.navigation.formatAvatarSharedKey
import org.kazusa.matter.ui.navigation.weiboAvatarSharedElement

/**
 * 搜索「用户卡」。
 *
 * 数据来自微博搜索响应里带 `user` 的卡片。
 * `desc1` / `desc2` 是**服务端预格式化的展示文案**（常见为「微博认证：…」「粉丝 1.2万」），
 * 直接渲染即可，不要由客户端重新拼。
 *
 * 注意：部分卡片（如 `card_group` 条目）的 `user` 只有 `{uid, username}`，
 * 解析后昵称为空；此时退化为渲染 [WeiboSearchCardInfo.title]。
 */
@Composable
fun SearchUserCard(
    card: WeiboSearchCardInfo,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    onUserClick: (Long?) -> Unit = {},
    /**
     * 强制覆盖「我是否已关注」的判断。
     *
     * 「关注的人 / 最新发博」这类列表天然全是已关注用户，但移动端卡片按钮文案
     * 仍可能下发「关注」。传 true 可避免依赖服务端按钮文案。
     */
    followingOverride: Boolean? = null,
    /** 点击右侧关注按钮；为空时回落到整卡点击 */
    onFollowClick: (() -> Unit)? = null,
    /** 关注 / 取关请求进行中，暂时禁用按钮 */
    followUpdating: Boolean = false
) {
    val user = card.user
    val displayName = user?.screenName?.takeIf { it.isNotBlank() }
        ?: card.title?.takeIf { it.isNotBlank() }
        ?: ""

    // 关注状态以用户对象的 following / follow_me 为准；服务端按钮文案仅作兜底。
    // 微博客户端的「已关注 / 互相关注」也是按 relationship 推导，而非直接用按钮名。
    val isFollowing = followingOverride ?: user?.following

    // 头像共享元素：与博主主页的大头像共用 uid 维度的 key
    val sharedElementTarget = LocalSharedElementTarget.current
    val avatarSharedKey = remember(user?.id, user?.idStr) {
        if (user?.id != null) {
            formatAvatarSharedKey(user.id)
        } else {
            formatAvatarSharedKey(user?.idStr)
        }
    }
    val avatarOwnerKey = remember(avatarSharedKey, card.itemid) {
        if (avatarSharedKey.isBlank()) "" else "user_card_$avatarSharedKey"
    }
    // 与信息流卡片一致：把头像地址/昵称暂存到全局缓存。
    // 博主主页首帧（接口未返回）会用它兜底，否则共享元素飞行期间目标头像会短暂显示默认图。
    SideEffect {
        UserAvatarCache.put(
            uid = user?.id,
            url = user?.bestAvatar ?: user?.profileImageUrl,
            screenName = user?.screenName
        )
        // 主页首帧据此先画出认证角标，角标才能和头像一起起飞
        val currentVerifyType = user.getVerifyType()
        if (currentVerifyType != WeiboVerifyType.NONE) {
            UserAvatarCache.putVerify(
                uid = user?.id?.toString() ?: user?.idStr,
                verifyTypeOrdinal = currentVerifyType.ordinal
            )
        }
    }
    val avatarRequest = rememberSharedAvatarRequest(
        url = user?.bestAvatar ?: user?.profileImageUrl,
        sharedKey = avatarSharedKey
    )
    val selectAvatarForTransition = {
        if (avatarSharedKey.isNotBlank()) {
            sharedElementTarget?.selectAvatar(
                ownerKey = avatarOwnerKey,
                avatarKey = avatarSharedKey
            )
        }
    }
    val handleCardClick = {
        selectAvatarForTransition()
        onClick()
    }
    val handleAvatarClick = {
        selectAvatarForTransition()
        onUserClick(user?.id)
    }

    MatterCard(
        onClick = handleCardClick,
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            UserAvatarWithBadge(
                avatarUrl = user?.bestAvatar,
                verifyType = user.getVerifyType(),
                avatarSize = 46.dp,
                badgeSize = 18.dp,
                badgeBackgroundColor = MaterialTheme.colorScheme.surfaceContainerLow,
                // 列表头像尺寸较小，垫底边框比主页收窄，避免角标显得笨重
                badgeBackgroundPadding = 1.dp,
                badgeSharedAvatarKey = avatarSharedKey,
                badgeSharedOwnerKey = avatarOwnerKey,
                contentDescription = displayName,
                avatarModel = avatarRequest,
                avatarModifier = if (avatarSharedKey.isNotBlank()) {
                    Modifier.weiboAvatarSharedElement(
                        key = avatarSharedKey,
                        ownerKey = avatarOwnerKey
                    )
                } else {
                    Modifier
                },
                onClick = handleAvatarClick
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    UserVipBadge(
                        user = user,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }

                card.desc1?.takeIf { it.isNotBlank() }?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                card.desc2?.takeIf { it.isNotBlank() }?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 卡片按钮（关注等）：客户端暂无对应写接口，点击等同于点卡片本身。
            val serverButton = card.buttons.firstOrNull { !it.name.isNullOrBlank() }
            val buttonText = when {
                isFollowing == true && user?.followMe == true -> "互相关注"
                isFollowing == true -> "已关注"
                else -> serverButton?.name?.takeIf { it.isNotBlank() }
            }
            if (buttonText != null) {
                Spacer(modifier = Modifier.width(8.dp))
                CardButtonChip(
                    name = buttonText,
                    enabled = !followUpdating,
                    onClick = onFollowClick ?: handleCardClick
                )
            }
        }
    }
}

/**
 * 搜索「话题 / 图文卡」。
 *
 * 数据来自带 `title` / `title_sub` / 封面的卡片组条目或带副标题的卡片。
 */
@Composable
fun SearchTopicCard(
    card: WeiboSearchCardInfo,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    MatterCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val pic = card.bestPicUrl
            if (!pic.isNullOrBlank()) {
                AsyncImage(
                    model = pic,
                    contentDescription = card.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                )
                Spacer(modifier = Modifier.width(10.dp))
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                card.title?.takeIf { it.isNotBlank() }?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.titleSmallEmphasized,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                card.titleSub?.takeIf { it.isNotBlank() }?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.labelMediumEmphasized,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                card.desc1?.takeIf { it.isNotBlank() }?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                card.desc2?.takeIf { it.isNotBlank() }?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            val onlineUsers = card.onlineUsers
            if (onlineUsers != null && onlineUsers > 0) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "$onlineUsers 人在线",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * 搜索「词条组」卡：相关搜索 / 热门话题 / 搜索发现这类纯文本词条集合。
 *
 * 数据来自整组只有纯文本的 `card_group`：
 * 组标题取顶层卡的 `title`（服务端没给就只渲染词条），词条文案与跳转 `scheme` 全部来自服务端。
 * 点击词条时交给 [onChipClick]，由调用方决定跳哪个搜索（词条自带 `keyword` 已从 `scheme` 的 `q` 解析）。
 */
@Composable
fun SearchChipGroupCard(
    group: WeiboSearchChipGroup,
    modifier: Modifier = Modifier,
    onChipClick: (WeiboSearchChip) -> Unit = {}
) {
    MatterCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        pressedElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            group.title?.takeIf { it.isNotBlank() }?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleSmallEmphasized,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                group.chips.forEach { chip ->
                    SearchChipPill(chip = chip, onClick = { onChipClick(chip) })
                }
            }
        }
    }
}

/** 词条组里的单个词条胶囊（背景用容器色，与卡片按钮胶囊区分开）。 */
@Composable
private fun SearchChipPill(
    chip: WeiboSearchChip,
    onClick: () -> Unit
) {
    MatterChip(onClick = onClick) {
        Text(
            text = chip.text,
            style = MaterialTheme.typography.labelMediumEmphasized,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        chip.extra?.let { extra ->
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = extra,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** 卡片按钮胶囊（卡片自带 `buttons[].name`）。 */
@Composable
private fun CardButtonChip(
    name: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    MatterChip(
        containerColor = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        onClick = if (enabled) onClick else null
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelMediumEmphasized,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
