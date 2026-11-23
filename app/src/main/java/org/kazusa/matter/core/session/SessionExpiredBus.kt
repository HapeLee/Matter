package org.kazusa.matter.core.session

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 会话被判失效的原因。
 *
 * 区分它们不是洁癖：三条路径的修法完全不同，而日志里都会显示同一句「登录已过期」。
 * 实测（2026-11-23）服务端给登录态 30 天有效期（`ALF`）且不续期，所以「一周就掉」
 * 必然不是到期，只能是这三条之一——把原因留在本地，用户下次进登录页就能看到。
 */
enum class SessionExpiryReason(val description: String) {
    /** 请求带着登录凭据，服务端仍返回 `-100`。 */
    API_REJECTED("微博判定登录已失效"),

    /** 启动 / 回前台探活连续两次判未登录。 */
    PROBE_FAILED("启动校验时发现已退出"),

    /** 本地标记已登录，但读不出凭据（解密失败或字段缺失）。 */
    LOCAL_CREDENTIAL_MISSING("本地凭据读取失败")
}

/**
 * 会话失效统一事件总线。
 *
 * 微博 H5 端的鉴权失效不是单一状态码（`ok:-100`、`login!=true` 等），此前由各页面
 * 自行拼文案，既不触发登出也不互相一致。现在网络层在确认「请求确实携带登录 Cookie
 * 且服务端判定未登录」时调用 [notifySessionExpired]，由导航根订阅后统一执行
 * 标记会话失效 + 跳登录页；页面错误文案一律使用 [MESSAGE] 这一份。
 *
 * 事件本身不承载业务事实：缓冲为 1、溢出丢弃，无订阅者或重复失效时静默忽略，
 * 同一轮失效只会被消费一次。
 */
class SessionExpiredBus {

    private val _expired = MutableSharedFlow<SessionExpiryReason>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val expired: SharedFlow<SessionExpiryReason> = _expired.asSharedFlow()

    fun notifySessionExpired(reason: SessionExpiryReason = SessionExpiryReason.API_REJECTED) {
        _expired.tryEmit(reason)
    }

    companion object {
        /** 会话失效文案全应用只有这一份。 */
        const val MESSAGE = "登录已过期，请重新登录"
    }
}
