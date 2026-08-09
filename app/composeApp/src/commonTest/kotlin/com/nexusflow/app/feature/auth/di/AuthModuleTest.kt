package com.nexusflow.app.feature.auth.di

import com.nexusflow.app.core.config.BuildMode
import com.nexusflow.app.core.config.RuntimeConfig
import com.nexusflow.app.core.network.ApiCallExecutor
import com.nexusflow.app.core.observability.AppLogger
import com.nexusflow.app.core.observability.LogFields
import com.nexusflow.app.core.observability.LogLevel
import com.nexusflow.app.core.observability.LogTag
import com.nexusflow.app.core.security.SecureStore
import com.nexusflow.app.core.time.AppClock
import com.nexusflow.app.feature.auth.data.AuthApi
import com.nexusflow.app.feature.auth.data.AuthSessionStore
import com.nexusflow.app.feature.auth.domain.AuthRepository
import com.nexusflow.app.feature.auth.presentation.AuthSessionController
import de.jensklingenberg.ktorfit.ktorfit
import io.ktor.client.HttpClient
import org.koin.core.error.NoDefinitionFoundException
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class AuthModuleTest {
    @Test
    fun resolvesFeatureOwnersWithoutExposingTheFeatureApi() {
        val httpClient = HttpClient()
        val application =
            koinApplication {
                modules(
                    module {
                        single {
                            RuntimeConfig(
                                apiBaseUrl = "https://api.example",
                                googleServerClientId = "client-id",
                                buildMode = BuildMode.DEBUG,
                            )
                        }
                        single<SecureStore> { InMemorySecureStore }
                        single<AppClock> { FixedClock }
                        single<AppLogger> { NoOpLogger }
                        single { ApiCallExecutor(get()) }
                        single {
                            ktorfit {
                                baseUrl("https://api.example/")
                                httpClient(httpClient)
                            }
                        }
                    },
                    authModule,
                )
            }

        try {
            assertIs<AuthSessionController>(application.koin.get<AuthSessionController>())
            assertIs<AuthRepository>(application.koin.get<AuthRepository>())
            assertIs<AuthSessionStore>(application.koin.get<AuthSessionStore>())
            assertFailsWith<NoDefinitionFoundException> {
                application.koin.get<AuthApi>()
            }
        } finally {
            application.close()
            httpClient.close()
        }
    }
}

private object InMemorySecureStore : SecureStore {
    override fun read(key: String): String? = null

    override fun write(
        key: String,
        value: String,
    ) = Unit

    override fun remove(key: String) = Unit
}

private object FixedClock : AppClock {
    override fun currentTimeMillis(): Long = 0L
}

private object NoOpLogger : AppLogger {
    override fun log(
        level: LogLevel,
        tag: LogTag,
        event: String,
        fields: LogFields,
        cause: Throwable?,
    ) = Unit
}
