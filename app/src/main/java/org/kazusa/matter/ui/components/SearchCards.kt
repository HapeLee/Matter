package org.kazusa.matter.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.kazusa.matter.data.model.WeiboSearchCardInfo
import org.kazusa.matter.data.model.WeiboSearchChip
import org.kazusa.matter.data.model.WeiboSearchChipGroup

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
    onUserClick: (Long?) -> Unit = {}
) {
    val user = card.user
    val displayName = user?.screenName?.takeIf { it.isNotBlank() }
        ?: card.title?.takeIf { it.isNotBlank() }
        ?: ""

    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp,
            pressedElevation = 2.dp
        )
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
                badgeSize = 16.dp,
                contentDescription = displayName,
                onClick = { onUserClick(user?.id) }
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
            val button = card.buttons.firstOrNull { !it.name.isNullOrBlank() }
            if (button != null) {
                Spacer(modifier = Modifier.width(8.dp))
                CardButtonChip(name = button.name.orEmpty(), onClick = onClick)
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
    Card(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp,
            pressedElevation = 2.dp
        )
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
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp
        )
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
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = chip.text,
            style = MaterialTheme.typography.labelMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
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
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelMediumEmphasized,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
