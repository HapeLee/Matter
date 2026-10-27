package org.kazusa.matter.ui.components

import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import org.kazusa.matter.core.network.WeiboApiService
import org.kazusa.matter.data.repository.FavoriteState
import org.kazusa.matter.data.repository.LikeRepository
import org.kazusa.matter.data.repository.LikeState

/** 全局注入的接口服务，供列表卡片里的即时交互使用。 */
val LocalWeiboApiService = staticCompositionLocalOf<WeiboApiService?> { null }

/** 点赞状态与操作入口；卡片、评论区、正文页都从同一份状态读取。 */
val LocalLikeRepository = staticCompositionLocalOf<LikeRepository?> { null }

/*
 * 点赞 / 收藏这类「整张 Map 会被整体替换」的快照，下发的是 State 而不是 Map 本身。
 *
 * 直接下发 Map 时（无论 static 还是 dynamic CompositionLocal），Map 一变就让**所有**
 * 读过它的卡片 / 评论行重组：一屏十几条里只有一条真的变了，其余全是无效重组。
 * 下发 State 后，消费方在 derivedStateOf 里取自己那一项，结果相等即不失效，
 * 只有状态真正属于自己那条微博 / 评论的组件才会重组。
 */

/** 点赞状态的只读快照（导航根从 [LikeRepository.postStates] 收集一次后下发）。 */
val LocalPostLikeStates = compositionLocalOf<State<Map<String, LikeState>>> { mutableStateOf(emptyMap()) }

/** 评论点赞状态的只读快照（评论 id 为 Long），见 [LocalPostLikeStates]。 */
val LocalCommentLikeStates = compositionLocalOf<State<Map<Long, LikeState>>> { mutableStateOf(emptyMap()) }

/** 收藏状态的只读快照，见 [LocalPostLikeStates]。 */
val LocalFavoriteState = compositionLocalOf<State<FavoriteState>> { mutableStateOf(FavoriteState()) }
