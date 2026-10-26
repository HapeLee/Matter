package org.kazusa.matter.core.session

/**
 * 当前会话 Cookie 的同步快照。
 *
 * 存在的唯一理由：有些调用方只能同步取值——Coil 的 OkHttp 拦截器就是典型，
 * 而会话本身存在 DataStore 里、只能异步读。应用启动时订阅 `sessionFlow` 写入快照，
 * 拦截器直接读这里。[current] 在没有会话时返回空串，调用方按匿名请求处理。
 *
 * 写入点是唯一的（应用生命周期内的 Collect），因此不需要额外同步；
 * `@Volatile` 只保证跨线程可见性。
 */
object SessionCookieSnapshot {

    @Volatile
    private var cookie: String = ""

    fun update(cookieHeader: String) {
        cookie = cookieHeader
    }

    fun current(): String = cookie
}
