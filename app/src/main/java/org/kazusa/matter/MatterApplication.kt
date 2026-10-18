package org.kazusa.matter

import android.app.Application
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.kazusa.matter.core.di.appModule

class MatterApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@MatterApplication)
            modules(appModule)
        }
    }
}
