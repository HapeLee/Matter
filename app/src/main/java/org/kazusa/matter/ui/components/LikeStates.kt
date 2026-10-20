package org.kazusa.matter.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.data.repository.LikeRepository
import org.kazusa.matter.data.repository.LikeState

/** 全局注入的接口服务，供列表卡片里的即时交互使用。 */
val LocalWeiboApiService = staticCompositionLocalOf<WeiboApiService?> { null }

/** 点赞状态与操作入口；卡片、评论区、正文页都从同一份状态读取。 */
val LocalLikeRepository = staticCompositionLocalOf<LikeRepository?> { null }

/**
 * 点赞状态的只读快照（导航根从 [LikeRepository.postStates] 收集一次后下发）。
 *
 * 仓库本身用 StateFlow 暴露状态；列表项各自订阅 Flow 会付出 N 份收集成本，
 * 这里统一下发一份，卡片按 id 取用，并在快照变化时自动重组。
 */
val LocalPostLikeStates = staticCompositionLocalOf<Map<String, LikeState>> { emptyMap() }

/** 评论点赞状态的只读快照（评论 id 为 Long），见 [LocalPostLikeStates]。 */
val LocalCommentLikeStates = staticCompositionLocalOf<Map<Long, LikeState>> { emptyMap() }
