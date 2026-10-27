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
 * 2. [execute] 串行执行所有写操作，并保证两次提交至少间隔 [currentIntervalMillis]，
 *    连点多张卡片不会再瞬时并发多个写请求；
 * 3. 识别频控后进入 [cooldownMillis] 冷却，期间直接拒绝而不是继续给服务端记账，
 *    否则用户每点一次都在延长冷却。
 *
 * 间隔是**自适应**的：平时用 [normalIntervalMillis]（连点不排队），
 * 一旦本会话踩到过频控就退避到 [throttledIntervalMillis]，
 * 之后连续成功 [recoverySuccesses] 次再恢复常规节奏——
 * 固定 800ms 的话，正常用户连点三个赞就要多等 1.6s，而限流风险其实只在被限过之后才真实存在。
 */
internal class WeiboWriteSession(
    private val fetchCredentials: suspend (cookie: String, action: String) -> WeiboWriteCredentials,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 未被限流过时的最小提交间隔。 */
    private val normalIntervalMillis: Long = DEFAULT_NORMAL_INTERVAL_MS,
    /** 踩过频控后的最小提交间隔。 */
    private val throttledIntervalMillis: Long = DEFAULT_THROTTLED_INTERVAL_MS,
    private val cooldownMillis: Long = DEFAULT_COOLDOWN_MS,
    /** 退避状态下连续成功多少次后恢复常规间隔。 */
    private val recoverySuccesses: Int = DEFAULT_RECOVERY_SUCCESSES
) {
    private val mutex = Mutex()

    @Volatile
    private var credentials: WeiboWriteCredentials? = null

    private var lastWriteAt: Long? = null

    @Volatile
    private var cooldownUntil: Long? = null

    /** 是否处于退避状态：本会话被限流过就置位。 */
    @Volatile
    private var throttled = false

    /** 退避期间连续成功的次数（只在 [mutex] 内读写）。 */
    private var successStreak = 0

    private val currentIntervalMillis: Long
        get() = if (throttled) throttledIntervalMillis else normalIntervalMillis

    fun isCoolingDown(): Boolean = (cooldownUntil ?: Long.MIN_VALUE) > clock()

    /**
     * 串行提交一次写操作。
     *
     * @param cookie 当前会话 Cookie；与缓存凭据的来源不一致时重新取 `st`。
     * @param action 中文动作名，用于拼装「登录已失效，请重新登录后{action}」这类提示。
     * @param needsXsrfToken 端点是否用网页域 `XSRF-TOKEN` 鉴权。分组管理这类只用会话
     *   Cookie 的写操作传 `false`：不为一次建组先多打一次 `weibo.com/` 换令牌，
     *   也避免令牌取不到时把「只用 Cookie 就能成功」的请求一并判死。
     * @param block 真正提交请求并解析响应；成功、失败都返回 `ApiResult`，无需抛异常。
     * @throws WriteGateException 冷却中或凭据不可用（调用方转成 `ApiResult.Error`）。
     */
    suspend fun <T : Any> execute(
        cookie: String,
        action: String,
        needsXsrfToken: Boolean = true,
        block: suspend (WeiboWriteCredentials) -> T
    ): T = mutex.withLock {
        if (isCoolingDown()) {
            throw WriteGateException(
                WeiboWriteErrors.CODE_RATE_LIMITED,
                WeiboWriteErrors.RATE_LIMITED_MESSAGE
            )
        }
        lastWriteAt?.let { last ->
            val wait = last + currentIntervalMillis - clock()
            if (wait > 0) delay(wait)
        }
        val current = if (needsXsrfToken) {
            acquire(cookie, action)
        } else {
            WeiboWriteCredentials(sourceCookie = cookie, cookieHeader = cookie, token = "")
        }
        try {
            val result = block(current)
            // 写路径用返回值表达失败：频控时 block 会调 [reportRateLimited] 后正常返回
            // `ApiResult.Error`，而不是抛异常。那种返回不能算「连续成功」，否则退避会被
            // 自己的失败次数喂回常规节奏，恰好在最需要慢下来的时候失效。
            if (!isCoolingDown()) onWriteSucceeded()
            result
        } finally {
            // 失败也算一次提交：服务端已经记账，不能立刻再打一次
            lastWriteAt = clock()
        }
    }

    /** 退避状态下连续成功足够多次后，放宽回常规间隔。 */
    private fun onWriteSucceeded() {
        if (!throttled) return
        successStreak += 1
        if (successStreak >= recoverySuccesses) {
            throttled = false
            successStreak = 0
        }
    }

    /** 丢弃缓存的 `st`：服务端表示令牌无效、或会话变化后调用。 */
    fun invalidate() {
        credentials = null
    }

    /** 服务端返回频控（`ok=0` + 频繁文案，或 HTTP 429）时调用。 */
    fun reportRateLimited() {
        // 被限流过就退避：之后即使冷却结束，也按保守间隔排队
        throttled = true
        successStreak = 0
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
        /** 常规节奏：明显低于「连点」判定，同时不至于让连续操作排队太久。 */
        const val DEFAULT_NORMAL_INTERVAL_MS = 250L

        /** 被限流过之后的保守间隔。 */
        const val DEFAULT_THROTTLED_INTERVAL_MS = 800L

        /** 触发频控后的本地冷却时长，避免用户在服务端冷却期内反复试探。 */
        const val DEFAULT_COOLDOWN_MS = 60_000L

        /** 退避状态下连续成功多少次后恢复常规间隔。 */
        const val DEFAULT_RECOVERY_SUCCESSES = 5
    }
}
