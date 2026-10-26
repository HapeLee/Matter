package org.kazusa.matter.core.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 写操作（点赞 / 收藏 / 关注 / 评论 / 删除 / 私信）所需的凭据。
 *
 * 写路径已全部收敛到网页域：`token` 是 cookie 里的 `XSRF-TOKEN`（发请求时作
 * `X-XSRF-TOKEN` 头），`cookieHeader` 是与之配套的合并 Cookie（含轮换后的令牌）。
 * 两者必须成对缓存、成对使用。
 */
internal data class WeiboWriteCredentials(
    /** 生成 [token] 时使用的会话 Cookie，用于判断缓存是否仍然适用。 */
    val sourceCookie: String,
    /** 合并过网页域 `Set-Cookie` 的 Cookie 头。 */
    val cookieHeader: String,
    val token: String
)

/** 写操作未提交：本地冷却中（429）或凭据不可用（401）。 */
internal class WriteGateException(val code: Int?, message: String) : Exception(message)

internal object WeiboWriteErrors {
    const val CODE_RATE_LIMITED = 429
    const val RATE_LIMITED_MESSAGE = "操作频繁，请稍后再试"

    /** 微博风控文案（`操作频繁，请稍后再试` / `请求过于频繁` 等）。 */
    fun isRateLimited(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        return message.contains("频繁") || message.contains("太快")
    }
}

/**
 * 写操作的串行闸门与网页凭据缓存。
 *
 * 所有写路径都已走 `weibo.com`（凭据是 `XSRF-TOKEN`，见 [WeiboWriteCredentials]），
 * 但服务端按账号对写操作限流：一旦触发，冷却期内**所有**写操作都返回「操作频繁，请稍后再试」。
 * 因此这里统一做三件事：
 *
 * 1. 缓存凭据与配套 Cookie：一次写操作只发目标请求，令牌按来源 Cookie 复用，
 *    鉴权失败或换账号时由调用方 [invalidate]；
 * 2. [execute] 串行执行所有写操作，并保证两次提交至少间隔 [minWriteIntervalMillis]，
 *    连点多张卡片不会再瞬时并发多个写请求；
 * 3. 识别频控后进入 [cooldownMillis] 冷却，期间直接拒绝而不是继续给服务端记账，
 *    否则用户每点一次都在延长冷却。
 */
internal class WeiboWriteSession(
    private val fetchCredentials: suspend (cookie: String, action: String) -> WeiboWriteCredentials,
    private val clock: () -> Long = System::currentTimeMillis,
    private val minWriteIntervalMillis: Long = DEFAULT_MIN_WRITE_INTERVAL_MS,
    private val cooldownMillis: Long = DEFAULT_COOLDOWN_MS
) {
    private val mutex = Mutex()

    @Volatile
    private var credentials: WeiboWriteCredentials? = null

    private var lastWriteAt: Long? = null

    @Volatile
    private var cooldownUntil: Long? = null

    fun isCoolingDown(): Boolean = (cooldownUntil ?: Long.MIN_VALUE) > clock()

    /**
     * 串行提交一次写操作。
     *
     * @param cookie 当前会话 Cookie；与缓存凭据的来源不一致时重新取 `st`。
     * @param action 中文动作名，用于拼装「登录已失效，请重新登录后{action}」这类提示。
     * @param block 真正提交请求并解析响应；成功、失败都返回 `ApiResult`，无需抛异常。
     * @throws WriteGateException 冷却中或凭据不可用（调用方转成 `ApiResult.Error`）。
     */
    suspend fun <T : Any> execute(
        cookie: String,
        action: String,
        block: suspend (WeiboWriteCredentials) -> T
    ): T = mutex.withLock {
        if (isCoolingDown()) {
            throw WriteGateException(
                WeiboWriteErrors.CODE_RATE_LIMITED,
                WeiboWriteErrors.RATE_LIMITED_MESSAGE
            )
        }
        lastWriteAt?.let { last ->
            val wait = last + minWriteIntervalMillis - clock()
            if (wait > 0) delay(wait)
        }
        val current = acquire(cookie, action)
        try {
            block(current)
        } finally {
            // 失败也算一次提交：服务端已经记账，不能立刻再打一次
            lastWriteAt = clock()
        }
    }

    /** 丢弃缓存的 `st`：服务端表示令牌无效、或会话变化后调用。 */
    fun invalidate() {
        credentials = null
    }

    /** 服务端返回频控（`ok=0` + 频繁文案，或 HTTP 429）时调用。 */
    fun reportRateLimited() {
        cooldownUntil = clock() + cooldownMillis
    }

    private suspend fun acquire(cookie: String, action: String): WeiboWriteCredentials {
        credentials?.let { if (it.sourceCookie == cookie) return it }
        return try {
            fetchCredentials(cookie, action).also { credentials = it }
        } catch (e: WriteGateException) {
            if (e.code == WeiboWriteErrors.CODE_RATE_LIMITED) reportRateLimited()
            throw e
        }
    }

    private companion object {
        /** 两次写操作的最小间隔：低于该值容易直接被微博判为连点。 */
        const val DEFAULT_MIN_WRITE_INTERVAL_MS = 800L

        /** 触发频控后的本地冷却时长，避免用户在服务端冷却期内反复试探。 */
        const val DEFAULT_COOLDOWN_MS = 60_000L
    }
}
