package org.kazusa.matter.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import org.kazusa.matter.data.model.WeiboStatusDto
import org.kazusa.matter.data.repository.RepostState

/**
 * 转发计数的只读快照（导航根从 `RepostRepository.postStates` 收集一次后下发）。
 *
 * 与 [LocalPostLikeStates] 同理：列表项各自订阅 Flow 会付出 N 份收集成本。
 */
val LocalRepostStates = staticCompositionLocalOf<Map<String, RepostState>> { emptyMap() }

/**
 * 打开转发面板。
 *
 * 卡片与正文页只发出「要转发这条微博」的事件，面板本身由导航根挂载一份：
 * 列表项会被回收，不能在自己的组合里持有 sheet。
 */
val LocalRepostRequest = staticCompositionLocalOf<(WeiboStatusDto) -> Unit> { {} }
