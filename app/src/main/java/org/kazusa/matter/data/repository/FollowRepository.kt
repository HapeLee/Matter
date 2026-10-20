package org.kazusa.matter.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.kazusa.matter.core.network.ApiResult

/** 单个用户的关注状态。 */
data class FollowState(
    /** 我是否关注了该用户 */
    val following: Boolean,
    /** 关注 / 取关请求进行中，界面据此禁用按钮 */
    val updating: Boolean = false
)

/** 关注 / 取关的结果：成功、被忽略（同一个人已有请求在跑）、失败（带可直接展示的提示）。 */
sealed interface FollowResult {
    data object Success : FollowResult

    data object Ignored : FollowResult

    data class Failure(val message: String) : FollowResult
}

/**
 * 关注关系的唯一事实来源（会话级单例，属于数据层）。
 *
 * - 状态以只读 [StateFlow] 暴露，用户列表与博主主页读同一份结果：
 *   在主页取消关注后返回列表，卡片不会还停在「已关注」；
 * - 请求、乐观更新与失败回滚都在这里，调用方只需在生命周期够长的 scope 里调用；
 * - 服务端下发的 following 只用来「打底」（[seed]），不覆盖本会话里用户已经点过的结果；
 * - 切换账号由导航根调用 [clear]，并推进会话代次，挡掉旧账号晚到的响应。
 */
class FollowRepository internal constructor(
    private val requestFollow: suspend (uid: String) -> ApiResult<Unit>,
    private val requestUnfollow: suspend (uid: String) -> ApiResult<Unit>
) {
    constructor(userRepository: UserRepository) : this(
        requestFollow = userRepository::follow,
        requestUnfollow = userRepository::unfollow
    )

    private val _states = MutableStateFlow<Map<String, FollowState>>(emptyMap())

    /** uid → 关注状态覆盖。只读快照，写入口只有 [seed] / [setFollowing] / [clear]。 */
    val states: StateFlow<Map<String, FollowState>> = _states.asStateFlow()

    /** 会话代次：切换账号时自增，旧账号的在途请求不能写回新会话。 */
    private var sessionGeneration = 0

    /** 用服务端下发的 following 打底；已有覆盖值（用户点过）时不覆盖。 */
    fun seed(uid: String?, following: Boolean?) {
        if (uid.isNullOrBlank() || following == null) return
        if (_states.value.containsKey(uid)) return
        _states.update { it + (uid to FollowState(following = following)) }
    }

    /** 关注状态覆盖；没有覆盖值时返回 null，调用方回落到服务端字段。 */
    fun followingOf(uid: String?): Boolean? = uid?.let { _states.value[it]?.following }

    suspend fun setFollowing(uid: String, follow: Boolean): FollowResult {
        if (uid.isBlank()) return FollowResult.Failure(UNSUPPORTED)
        val previous = _states.value[uid]
        if (previous?.updating == true) return FollowResult.Ignored

        val session = sessionGeneration
        // 乐观切换：按钮立刻变成目标状态并禁用，请求失败再退回原值
        _states.update { it + (uid to FollowState(following = follow, updating = true)) }
        return try {
            when (val result = if (follow) requestFollow(uid) else requestUnfollow(uid)) {
                is ApiResult.Success -> {
                    if (session == sessionGeneration) {
                        _states.update { it + (uid to FollowState(following = follow)) }
                    }
                    FollowResult.Success
                }

                is ApiResult.Error -> {
                    restore(uid, previous, session)
                    FollowResult.Failure(
                        result.message.ifBlank {
                            if (follow) "关注失败，请稍后重试" else "取消关注失败，请稍后重试"
                        }
                    )
                }

                ApiResult.Loading -> {
                    restore(uid, previous, session)
                    FollowResult.Failure(DEFAULT_ERROR)
                }
            }
        } catch (cancelled: CancellationException) {
            // 页面销毁导致取消时没有结果可用，必须回滚，否则按钮会永久停在进行中
            restore(uid, previous, session)
            throw cancelled
        }
    }

    /**
     * 切换账号时清空。
     *
     * 同时推进代次：旧账号的在途请求（成功 / 失败 / 取消回滚）都不会写进新会话。
     */
    fun clear() {
        sessionGeneration += 1
        _states.value = emptyMap()
    }

    private fun restore(uid: String, previous: FollowState?, session: Int) {
        if (session != sessionGeneration) return
        _states.update { current ->
            if (previous == null) {
                current - uid
            } else {
                current + (uid to previous.copy(updating = false))
            }
        }
    }

    private companion object {
        const val DEFAULT_ERROR = "操作失败，请重试"
        const val UNSUPPORTED = "该用户暂不支持关注"
    }
}
