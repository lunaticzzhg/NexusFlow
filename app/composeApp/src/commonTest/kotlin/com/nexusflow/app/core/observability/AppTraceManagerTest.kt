package com.nexusflow.app.core.observability

import com.nexusflow.observability.TraceId
import com.nexusflow.observability.TraceIdGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AppTraceManagerTest {
    @Test
    fun `nested traces restore the parent trace`() =
        runTest {
            val logger = RecordingLogger()
            val manager = manager(logger, TRACE_A, TRACE_B)
            val observed = mutableListOf<String?>()

            manager.withNewTrace(operation = "outer", trigger = "user") {
                observed += manager.currentTraceId()?.value
                manager.withNewTrace(operation = "inner", trigger = "user") {
                    observed += manager.currentTraceId()?.value
                }
                observed += manager.currentTraceId()?.value
            }
            observed += manager.currentTraceId()?.value

            assertEquals(listOf(TRACE_A, TRACE_B, TRACE_A, null), observed)
            assertEquals(
                listOf("operation_started", "operation_started", "operation_finished", "operation_finished"),
                logger.entries.map { it.event },
            )
        }

    @Test
    fun `concurrent traces do not leak between coroutines`() =
        runTest {
            val manager = manager(RecordingLogger(), TRACE_A, TRACE_B)

            val left =
                async {
                    manager.withNewTrace(operation = "left") {
                        manager.currentTraceId()?.value
                    }
                }
            val right =
                async {
                    manager.withNewTrace(operation = "right") {
                        manager.currentTraceId()?.value
                    }
                }

            assertEquals(setOf(TRACE_A, TRACE_B), setOf(left.await(), right.await()))
            assertEquals(null, manager.currentTraceId())
        }

    @Test
    fun `exceptions and cancellation record failed operation and restore trace`() =
        runTest {
            val logger = RecordingLogger()
            val manager = manager(logger, TRACE_A, TRACE_B)

            assertFailsWith<IllegalStateException> {
                manager.withNewTrace(operation = "throws") {
                    error("boom")
                }
            }
            assertFailsWith<CancellationException> {
                manager.withNewTrace(operation = "cancelled") {
                    throw CancellationException("stop")
                }
            }

            assertEquals(null, manager.currentTraceId())
            assertEquals(
                listOf("operation_started", "operation_failed", "operation_started", "operation_failed"),
                logger.entries.map { it.event },
            )
            assertEquals("IllegalStateException", logger.entries[1].errorType)
            assertEquals("CancellationException", logger.entries[3].errorType)
        }

    @Test
    fun `result failure records failed operation without throwing`() =
        runTest {
            val logger = RecordingLogger()
            val manager = manager(logger, TRACE_A)

            val result =
                manager.withNewResultTrace(operation = "task_message_send", trigger = "retry") {
                    Result.failure<Unit>(IllegalArgumentException("safe type only"))
                }

            assertEquals(true, result.isFailure)
            assertEquals(listOf("operation_started", "operation_failed"), logger.entries.map { it.event })
            assertEquals("retry", logger.entries.first().fields["trigger"])
            assertEquals("IllegalArgumentException", logger.entries.last().errorType)
        }

    private fun manager(
        logger: RecordingLogger,
        vararg traceIds: String,
    ): AppTraceManager =
        DefaultAppTraceManager(
            logger = logger,
            traceIdGenerator = SequenceTraceIdGenerator(traceIds.toList()),
        )

    private class SequenceTraceIdGenerator(traceIds: List<String>) : TraceIdGenerator {
        private val traceIds = ArrayDeque(traceIds.map(TraceId::requireValid))

        override fun newTraceId(): TraceId = traceIds.removeFirst()
    }

    private class RecordingLogger : AppLogger {
        val entries = mutableListOf<Entry>()

        override fun log(
            level: LogLevel,
            tag: LogTag,
            event: String,
            fields: LogFields,
            cause: Throwable?,
        ) {
            entries += Entry(event, fields.values, cause?.let { it::class.simpleName })
        }
    }

    private data class Entry(
        val event: String,
        val fields: Map<String, String>,
        val errorType: String?,
    )

    private companion object {
        const val TRACE_A = "4bf92f3577b34da6a3ce929d0e0e4736"
        const val TRACE_B = "6bf92f3577b34da6a3ce929d0e0e4736"
    }
}
