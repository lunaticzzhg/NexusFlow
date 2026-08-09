package com.nexusflow.app.core.observability

import com.nexusflow.app.core.config.BuildMode
import com.nexusflow.app.core.config.RuntimeConfig
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertIs

class ObservabilityModuleTest {
    @Test
    fun resolvesTheSharedLogger() {
        val application =
            koinApplication {
                modules(
                    module { single { RuntimeConfig("https://api.example", "client-id", BuildMode.DEBUG) } },
                    observabilityModule,
                )
            }

        try {
            assertIs<AppLogger>(application.koin.get<AppLogger>())
        } finally {
            application.close()
        }
    }
}
