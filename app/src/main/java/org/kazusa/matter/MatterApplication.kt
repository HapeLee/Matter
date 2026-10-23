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

class MatterApplication : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@MatterApplication)
            modules(appModule)
        }
        // 历史版本明文落盘的凭据启动时就地改写为密文；幂等，失败不影响启动
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                GlobalContext.get().get<AuthPreferences>().migrateLegacyCredentials()
            }
        }
    }

    /** 全局图片加载器：配置只有一份，见 [MatterImageLoader]。 */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        MatterImageLoader.create(context)
}
