package com.nexusflow.app

import android.app.Application
import com.nexusflow.app.app.initKoin
import com.nexusflow.app.app.startup.startAppStartup
import com.nexusflow.app.core.config.platformRuntimeConfig
import com.nexusflow.app.core.network.platformHttpClient
import com.nexusflow.app.core.security.AndroidSecureStore
import com.nexusflow.app.core.time.platformAppClock
import org.koin.core.KoinApplication

class App : Application() {
    lateinit var koinApplication: KoinApplication
        private set

    override fun onCreate() {
        super.onCreate()
        koinApplication =
            initKoin(
                runtimeConfig = platformRuntimeConfig(),
                secureStore = AndroidSecureStore(applicationContext),
                appClock = platformAppClock(),
                httpClient = platformHttpClient(),
            )
        startAppStartup(koinApplication)
    }
}
