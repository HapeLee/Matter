package org.kazusa.matter.core.platform

import android.webkit.CookieManager
import kotlinx.coroutines.suspendCancellableCoroutine
import org.kazusa.matter.core.session.WebSessionCookieStore
import kotlin.coroutines.resume

class AndroidWebCookieStore : WebSessionCookieStore {

    override suspend fun clearAll() {
        val cookieManager = CookieManager.getInstance()
        suspendCancellableCoroutine { cont ->
            cookieManager.removeAllCookies {
                // 立刻落盘，避免进程被杀后 Cookie 库里仍残留旧账号凭据
                cookieManager.flush()
                if (cont.isActive) cont.resume(Unit)
            }
        }
    }
}
