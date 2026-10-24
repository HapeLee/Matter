package org.kazusa.matter.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import org.kazusa.matter.BuildConfig
import java.util.concurrent.TimeUnit

object KtorClientFactory {

    val jsonConfig = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
        coerceInputValues = true
        encodeDefaults = true
    }

    /*
     * 响应体读取与 JSON 解析统一放 IO 线程。
     *
     * 微博响应（时间线 / 详情 / 评论）动辄几十上百 KB，kotlinx.serialization 在主线程
     * 解码足以掉帧——请求通常由 viewModelScope（主线程）发起，恢复后恰好砸进页面
     * 转场动画中段。网络层的所有解析入口都必须经过这里的助手，不得直接
     * `bodyAsText()` + `decodeFromString` 在调用方上下文解析。
     */

    /** IO 线程读取响应体文本（正则 / 手工解析的端点用）。 */
    suspend fun HttpResponse.bodyText(): String =
        withContext(Dispatchers.IO) { bodyAsText() }

    /** IO 线程读取响应体并反序列化为 [T]。 */
    suspend inline fun <reified T> HttpResponse.decodeBody(
        json: Json = KtorClientFactory.jsonConfig
    ): T = withContext(Dispatchers.IO) { json.decodeFromString<T>(bodyAsText()) }

    /** IO 线程读取响应体并解析为 [JsonElement]（逐字段手工解析的端点用）。 */
    suspend fun HttpResponse.jsonBody(
        json: Json = KtorClientFactory.jsonConfig
    ): JsonElement = withContext(Dispatchers.IO) { json.parseToJsonElement(bodyAsText()) }

    fun createClient(cookieFallback: CookieFallbackSource? = null): HttpClient {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()

        return HttpClient(OkHttp) {
            engine {
                preconfigured = okHttpClient
            }

            install(ContentNegotiation) {
                json(jsonConfig)
            }

            install(HttpTimeout) {
                requestTimeoutMillis = 20_000
                connectTimeoutMillis = 15_000
                socketTimeoutMillis = 20_000
            }

            // Cookie 头唯一注入点：业务方法用 cookieHeader(...) 声明凭据
            install(CookieAuth) {
                fallbackSource = cookieFallback
            }

            // 网络日志只在 debug 构建输出：INFO 级 URL 可能带 uid / 登录 ticket，release 不落 logcat
            if (BuildConfig.DEBUG) {
                install(Logging) {
                    level = LogLevel.INFO
                    logger = object : Logger {
                        override fun log(message: String) {
                            android.util.Log.d("WeiboKtorClient", message)
                        }
                    }
                }
            }

            defaultRequest {
                header("User-Agent", WeiboRequestHeaders.USER_AGENT)
                header("Accept", "application/json, text/plain, */*")
                header("X-Requested-With", "XMLHttpRequest")
            }
        }
    }
}
