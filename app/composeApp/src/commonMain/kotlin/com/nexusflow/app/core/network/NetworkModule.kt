package com.nexusflow.app.core.network

import com.nexusflow.app.core.config.RuntimeConfig
import com.nexusflow.app.core.network.realtime.RealtimeSseSessionFactory
import com.nexusflow.app.core.observability.AppLogger
import de.jensklingenberg.ktorfit.Ktorfit
import de.jensklingenberg.ktorfit.ktorfit
import io.ktor.client.HttpClient
import org.koin.core.module.Module
import org.koin.dsl.module

/** Installs the shared first-party transport policy and generated API factory once per app process. */
fun networkModule(httpClient: HttpClient): Module =
    module {
        single<HttpClient> {
            httpClient.also {
                val koin = getKoin()
                it.installFirstPartyHttpInterceptors(
                    apiBaseUrl = get<RuntimeConfig>().apiBaseUrl,
                    logger = get<AppLogger>(),
                    traceManager = get(),
                ) {
                    koin.getOrNull<FirstPartyApiSession>()
                }
            }
        }
        single { ApiCallExecutor(get<AppLogger>()) }
        single {
            val koin = getKoin()
            RealtimeSseSessionFactory(
                httpClient = get(),
                apiBaseUrl = get<RuntimeConfig>().apiBaseUrl,
                logger = get<AppLogger>(),
            ) {
                koin.getOrNull<FirstPartyApiSession>()
            }
        }
        single<Ktorfit> {
            ktorfit {
                baseUrl(get<RuntimeConfig>().apiBaseUrl.trimEnd('/') + "/")
                httpClient(get<HttpClient>())
            }
        }
    }
