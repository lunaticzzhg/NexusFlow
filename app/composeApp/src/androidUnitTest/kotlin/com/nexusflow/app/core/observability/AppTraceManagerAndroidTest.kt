package com.nexusflow.app.core.observability

import com.nexusflow.observability.LogLevel
import com.nexusflow.observability.TraceId
import com.nexusflow.observability.TraceIdGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

class AppTraceManagerAndroidTest {
    @Test
    fun `trace follows coroutine dispatcher switches`() =
        runTest {
            val manager =
                DefaultAppTraceManager(
                    logger = RecordingLogger(),
                    traceIdGenerator = FixedTraceIdGenerator(TRACE_A),
                )

            val observed =
                manager.withNewTrace(operation = "switch") {
                    withContext(Dispatchers.Default) {
                        manager.currentTraceId()?.value
                    }
                }

            assertEquals(TRACE_A, observed)
            assertEquals(null, manager.currentTraceId())
        }

    private class FixedTraceIdGenerator(
        private val traceId: String,
    ) : TraceIdGenerator {
        override fun newTraceId(): TraceId = TraceId.requireValid(traceId)
    }

    private class RecordingLogger : AppLogger {
        override fun log(
            level: LogLevel,
            tag: LogTag,
            event: String,
            fields: LogFields,
            cause: Throwable?,
        ) = Unit
    }
}

private const val TRACE_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
