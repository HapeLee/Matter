package org.kazusa.matter.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler

/**
 * 业务代码不再手写 Cookie 头（统一由 CookieAuth 插件写入），断言请求里
 * Cookie 的测试必须用装了插件的客户端，否则凭据不会出现在请求上。
 */
fun cookieAwareClient(handler: MockRequestHandler): HttpClient =
    HttpClient(MockEngine(handler)) {
        install(CookieAuth)
    }
