package com.nexusflow.app

import android.app.Application
import com.nexusflow.app.app.initKoin
import com.nexusflow.app.core.config.platformRuntimeConfig

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        initKoin(platformRuntimeConfig())
    }
}
