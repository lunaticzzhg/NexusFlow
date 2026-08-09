package com.nexusflow.app.app

import com.nexusflow.app.app.startup.appStartupModule
import com.nexusflow.app.core.config.RuntimeConfig
import com.nexusflow.app.core.config.runtimeConfigModule
import com.nexusflow.app.core.network.networkModule
import com.nexusflow.app.core.observability.observabilityModule
import com.nexusflow.app.core.security.SecureStore
import com.nexusflow.app.core.security.secureStoreModule
import com.nexusflow.app.core.time.AppClock
import com.nexusflow.app.core.time.timeModule
import com.nexusflow.app.feature.auth.di.authModule
import com.nexusflow.app.feature.task.di.taskModule
import io.ktor.client.HttpClient
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.module.Module

/**
 * Application composition expressed only as core, app, and feature modules.
 */
fun appModules(
    runtimeConfig: RuntimeConfig,
    secureStore: SecureStore,
    appClock: AppClock,
    httpClient: HttpClient,
): List<Module> =
    listOf(
        runtimeConfigModule(runtimeConfig),
        secureStoreModule(secureStore),
        timeModule(appClock),
        observabilityModule,
        networkModule(httpClient),
        authModule,
        taskModule,
        appStartupModule,
    )

private var koinApplication: KoinApplication? = null

/**
 * Starts one process-wide Koin instance. Android calls this from Application;
 * the current iOS entry calls it before constructing its root controller.
 */
fun initKoin(
    runtimeConfig: RuntimeConfig,
    secureStore: SecureStore,
    appClock: AppClock,
    httpClient: HttpClient,
): KoinApplication =
    koinApplication ?: startKoin {
        modules(appModules(runtimeConfig, secureStore, appClock, httpClient))
    }.also { koinApplication = it }
