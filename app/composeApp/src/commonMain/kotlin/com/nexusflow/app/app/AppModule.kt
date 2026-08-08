package com.nexusflow.app.app

import com.nexusflow.app.core.config.RuntimeConfig
import com.nexusflow.app.core.security.SecureStore
import com.nexusflow.app.core.time.AppClock
import com.nexusflow.app.feature.auth.data.AuthApi
import com.nexusflow.app.feature.auth.data.AuthSessionStore
import com.nexusflow.app.feature.auth.data.DefaultAuthRepository
import com.nexusflow.app.feature.auth.domain.AuthRepository
import com.nexusflow.app.feature.auth.presentation.AuthSessionController
import com.nexusflow.app.feature.task.di.taskModule
import io.ktor.client.HttpClient
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.dsl.module

/**
 * Application-wide dependencies only. Feature modules are introduced together
 * with the first real feature, rather than anticipated here.
 */
fun appModule(
    runtimeConfig: RuntimeConfig,
    secureStore: SecureStore,
    appClock: AppClock,
    httpClient: HttpClient,
) = module {
    single { runtimeConfig }
    single<SecureStore> { secureStore }
    single<AppClock> { appClock }
    single { httpClient }
    single<AuthRepository> {
        DefaultAuthRepository(
            authApi = AuthApi(get(), get()),
            clock = get(),
        )
    }
    single { AuthSessionStore(get()) }
    single { AuthSessionController(get(), get(), get(), get()) }
    includes(taskModule)
}

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
        modules(appModule(runtimeConfig, secureStore, appClock, httpClient))
    }.also { koinApplication = it }
