package org.kazusa.matter

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.kazusa.matter.core.di.appModule
import org.kazusa.matter.core.image.MatterImageLoader

class MatterApplication : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@MatterApplication)
            modules(appModule)
        }
    }

    /** 全局图片加载器：配置只有一份，见 [MatterImageLoader]。 */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        MatterImageLoader.create(context)
}
