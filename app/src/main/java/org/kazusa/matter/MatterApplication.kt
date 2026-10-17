package org.kazusa.matter

import android.app.Application
import org.kazusa.matter.core.di.AppContainer
import org.kazusa.matter.core.di.DefaultAppContainer

class MatterApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = DefaultAppContainer(this)
    }
}
