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
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
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
