package com.nexusflow.app.app.startup

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class AppStartupTest {
    @Test
    fun repeatedStartsRestoreTheSessionOnlyOnce() =
        runTest {
            var restoreCount = 0
            val startup =
                AppStartup(
                    sessionRestore = { restoreCount += 1 },
                    scope = this,
                )

            startup.start()
            advanceUntilIdle()
            startup.start()
            advanceUntilIdle()

            assertEquals(1, restoreCount)
        }

    @Test
    fun startsQueuedBeforeTheFirstRestoreRunsRestoreTheSessionOnlyOnce() =
        runTest {
            var restoreCount = 0
            val startup =
                AppStartup(
                    sessionRestore = { restoreCount += 1 },
                    scope = this,
                )

            startup.start()
            startup.start()
            runCurrent()

            assertEquals(1, restoreCount)
        }
}
