package com.nexusflow.app.app

import com.nexusflow.app.core.config.RuntimeConfig
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.dsl.module

/**
 * Application-wide dependencies only. Feature modules are introduced together
 * with the first real feature, rather than anticipated here.
 */
fun appModule(runtimeConfig: RuntimeConfig) =
    module {
        single { runtimeConfig }
    }

private var koinApplication: KoinApplication? = null

/**
 * Starts one process-wide Koin instance. Android calls this from Application;
 * the current iOS entry calls it before constructing its root controller.
 */
fun initKoin(runtimeConfig: RuntimeConfig): KoinApplication =
    koinApplication ?: startKoin {
        modules(appModule(runtimeConfig))
    }.also { koinApplication = it }
