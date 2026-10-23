package org.kazusa.matter.core.session

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 会话失效统一事件总线。
 *
 * 微博 H5 端的鉴权失效不是单一状态码（`ok:-100`、`login!=true` 等），此前由各页面
 * 自行拼文案，既不触发登出也不互相一致。现在网络层在确认「请求确实携带登录 Cookie
 * 且服务端判定未登录」时调用 [notifySessionExpired]，由导航根订阅后统一执行
 * 清会话 + 跳登录页；页面错误文案一律使用 [MESSAGE] 这一份。
 *
 * 事件本身不承载业务事实：缓冲为 1、溢出丢弃，无订阅者或重复失效时静默忽略，
 * 同一轮失效只会被消费一次。
 */
class SessionExpiredBus {

    private val _expired = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val expired: SharedFlow<Unit> = _expired.asSharedFlow()

    fun notifySessionExpired() {
        _expired.tryEmit(Unit)
    }

    companion object {
        /** 会话失效文案全应用只有这一份。 */
        const val MESSAGE = "登录已过期，请重新登录"
    }
}
