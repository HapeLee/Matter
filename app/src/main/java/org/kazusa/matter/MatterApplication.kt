package org.kazusa.matter

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin
import org.kazusa.matter.core.datastore.AuthPreferences
import org.kazusa.matter.core.di.appModule
import org.kazusa.matter.core.image.MatterImageLoader
import org.kazusa.matter.core.session.SessionCookieSnapshot

class MatterApplication : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@MatterApplication)
            modules(appModule)
        }
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // 历史版本明文落盘的凭据启动时就地改写为密文；幂等，失败不影响启动
        val preferences = GlobalContext.get().get<AuthPreferences>()
        appScope.launch {
            runCatching { preferences.migrateLegacyCredentials() }
        }
        // 私信图片走 mss 附件通道，必须带会话 Cookie（不带一律 403）；
        // 图片加载只能同步取值，所以这里把会话流持续写进快照
        appScope.launch {
            preferences.sessionFlow.collect { SessionCookieSnapshot.update(it.cookieHeader) }
        }
    }

    /** 全局图片加载器：配置只有一份，见 [MatterImageLoader]。 */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        MatterImageLoader.create(context, SessionCookieSnapshot::current)
}
