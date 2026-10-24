package org.kazusa.matter.ui.components

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope
import org.kazusa.matter.data.repository.FavoritesRepository

val LocalFavoritesRepository = staticCompositionLocalOf<FavoritesRepository?> { null }

/**
 * 点赞 / 收藏等会话级写操作使用的 CoroutineScope。
 *
 * 它由导航根提供，生命周期长于任何列表项：列表项划出屏幕被回收时，
 * 已经发出的请求仍会跑完，不会把状态留在「处理中」。
 */
val LocalUserActionScope = staticCompositionLocalOf<CoroutineScope?> { null }

val LocalSnackbarHostState = staticCompositionLocalOf<SnackbarHostState?> { null }
