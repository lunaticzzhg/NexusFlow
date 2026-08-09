package com.nexusflow.app.core.config

import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertSame

class RuntimeConfigModuleTest {
    @Test
    fun resolvesTheRuntimeConfigProvidedByTheCompositionRoot() {
        val runtimeConfig =
            RuntimeConfig(
                apiBaseUrl = "https://api.example",
                googleServerClientId = "client-id",
                buildMode = BuildMode.DEBUG,
            )
        val application = koinApplication { modules(runtimeConfigModule(runtimeConfig)) }

        try {
            assertSame(runtimeConfig, application.koin.get<RuntimeConfig>())
        } finally {
            application.close()
        }
    }
}
