package com.nexusflow.app.core.time

import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertSame

class TimeModuleTest {
    @Test
    fun resolvesTheClockProvidedByTheCompositionRoot() {
        val appClock = FixedClock()
        val application = koinApplication { modules(timeModule(appClock)) }

        try {
            assertSame(appClock, application.koin.get<AppClock>())
        } finally {
            application.close()
        }
    }
}

private class FixedClock : AppClock {
    override fun currentTimeMillis(): Long = 0L
}
