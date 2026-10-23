package org.kazusa.matter.ui.profile

import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.shape.CircleShape


import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import org.kazusa.matter.core.datastore.AuthPreferences
import org.kazusa.matter.data.repository.AuthRepository
import org.kazusa.matter.ui.components.ImmersiveTopBar
import org.kazusa.matter.ui.components.MatterCard
import org.kazusa.matter.ui.components.TopBarTitleText
import org.kazusa.matter.ui.theme.ThemePreferences
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    authPreferences: AuthPreferences,
    authRepository: AuthRepository,
    themePreferences: ThemePreferences,
    onNavigateToGroups: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToAppearance: () -> Unit = {},
    onNavigateToHistory: () -> Unit = {},
    onNavigateToSavedPosts: () -> Unit = {},
    onNavigateToMyPosts: () -> Unit = {},
    onNavigateToFollowing: () -> Unit = {},
    onNavigateToFollowers: () -> Unit = {},
    onNavigateToMessages: () -> Unit = {},
    onLogout: () -> Unit,
    backdrop: LayerBackdrop? = null,
    bottomBarPadding: Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    val session by authPreferences.sessionFlow.collectAsStateWithLifecycle(initialValue = null)
    val coroutineScope = rememberCoroutineScope()
    var showLogoutDialog by remember { mutableStateOf(false) }

    // 微博 config 接口不返回用户资料，进入个人中心时补全/刷新昵称、头像与个性签名
    LaunchedEffect(session?.uid) {
        val uid = session?.uid.orEmpty()
        if (uid.isNotBlank() && uid != "guest") {
            authRepository.refreshProfile()
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("确认退出登录？") },
            text = { Text("退出登录后将清除本地会话缓存。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        coroutineScope.launch {
                            authRepository.logout()
                            onLogout()
                        }
                    }
                ) {
                    Text("退出", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            ImmersiveTopBar(
                scrollState = scrollState,
                title = { scroll ->
                    TopBarTitleText(
                        text = "个人中心",
                        scroll = scroll,
                        backdrop = backdrop
                    )
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .background(MaterialTheme.colorScheme.surface)
                // 内边距放进滚动内容中，内容才能滚动穿行到顶栏之下
                .verticalScroll(scrollState)
                .padding(
                    start = 16.dp,
                    top = innerPadding.calculateTopPadding() + 16.dp,
                    end = 16.dp,
                    bottom = 16.dp + bottomBarPadding
                ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 用户资料 + 数据统计合并为一张卡片
            val isGuest = session?.uid.isNullOrBlank() || session?.uid == "guest"
            MatterCard(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val avatarUrl = session?.avatarUrl?.ifBlank { null }
                            ?: "https://tvax1.sinaimg.cn/default/images/default_avatar_male_180.gif"

                        AsyncImage(
                            model = avatarUrl,
                            contentDescription = "用户头像",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(68.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = session?.screenName?.ifBlank { null } ?: "微博用户",
                                style = MaterialTheme.typography.titleLargeEmphasized,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = session?.description?.ifBlank { null } ?: "暂无个性签名",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (session?.uid.isNullOrBlank()) "访客体验模式" else "UID: ${session?.uid}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        thickness = 0.6.dp
                    )

                    // 数据统计栏：发博、关注、粉丝（登录后可点击进入对应列表）
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ProfileStatItem(
                            label = "发博",
                            count = session?.displayStatusesCount ?: "--",
                            modifier = Modifier.weight(1f),
                            onClick = if (!isGuest) onNavigateToMyPosts else null
                        )
                        StatDivider()
                        ProfileStatItem(
                            label = "关注",
                            count = session?.displayFriendsCount ?: "--",
                            modifier = Modifier.weight(1f),
                            onClick = if (!isGuest) onNavigateToFollowing else null
                        )
                        StatDivider()
                        ProfileStatItem(
                            label = "粉丝",
                            count = session?.displayFollowersCount ?: "--",
                            modifier = Modifier.weight(1f),
                            onClick = if (!isGuest) onNavigateToFollowers else null
                        )
                    }
                }
            }

            // 消息入口：私信会话列表（数据来自网页版私信接口）
            MatterCard(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    ProfileMenuRow(
                        icon = Icons.Default.Email,
                        title = "消息",
                        subtitle = "私信、@我、评论与赞",
                        onClick = onNavigateToMessages
                    )
                }
            }

            // 功能管理区
            Text(
                text = "应用管理与偏好",
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = MaterialTheme.colorScheme.primary
            )

            MatterCard(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    ProfileMenuRow(
                        icon = Icons.Default.Bookmark,
                        title = "赞过与收藏",
                        subtitle = "查看我赞过和收藏过的微博",
                        onClick = onNavigateToSavedPosts
                    )
                    ProfileMenuRow(
                        icon = Icons.Default.History,
                        title = "浏览记录",
                        subtitle = "本机保存的微博与博主浏览历史",
                        onClick = onNavigateToHistory
                    )
                    ProfileMenuRow(
                        icon = Icons.Default.Palette,
                        title = "外观与调色板",
                        subtitle = "深浅色、种子色彩与 Kolor 调色风格",
                        onClick = onNavigateToAppearance
                    )
                    ProfileMenuRow(
                        icon = Icons.Default.FilterList,
                        title = "关注分组管理",
                        subtitle = "配置动态顶部展示分组、排序与管理",
                        onClick = onNavigateToGroups
                    )
                    ProfileMenuRow(
                        icon = Icons.Default.Settings,
                        title = "更多设置",
                        subtitle = "大图清晰度、系统信息与关于",
                        onClick = onNavigateToSettings
                    )
                }
            }

            // 账号操作区
            MatterCard(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    ProfileMenuRow(
                        icon = Icons.AutoMirrored.Filled.ExitToApp,
                        title = "退出登录",
                        subtitle = "清除本地会话并返回登录界面",
                        tint = MaterialTheme.colorScheme.error,
                        onClick = { showLogoutDialog = true }
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileMenuRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(tint.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLargeEmphasized
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ProfileStatItem(
    label: String,
    count: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Column(
        // padding 放在 clip/clickable 之前：水波纹边界随之内缩，
        // 与左右竖向分隔线、卡片边框之间留出空隙，不再贴边。
        modifier = modifier
            .padding(horizontal = 8.dp)
            .then(
                if (onClick != null) {
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = count,
            style = MaterialTheme.typography.titleMediumEmphasized,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatDivider() {
    VerticalDivider(
        modifier = Modifier.height(26.dp),
        thickness = 0.8.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    )
}
