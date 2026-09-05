package com.nexusflow.app.core.observability

import com.nexusflow.app.core.config.BuildMode
import com.nexusflow.app.core.config.RuntimeConfig
import com.nexusflow.observability.TraceId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StructuredAppLoggerTest {
    @Test
    fun debugBuildWritesDebugEventsWithSortedFields() {
        val sink = RecordingLogSink()
        val logger = loggerFor(BuildMode.DEBUG, sink)

        logger.debug(
            tag = TestLogTag,
            event = "app_started",
            fields =
                logFields {
                    "retry_count" value 1
                    "source" value "cold_start"
                },
        )

        val message = sink.messages.single()
        assertTrue(message.contains("level=DEBUG"))
        assertTrue(message.contains("service=nexusflow-app"))
        assertTrue(message.contains("component=observabilitytest"))
        assertTrue(message.contains("environment=debug"))
        assertTrue(message.contains("event=app_started"))
        assertTrue(message.contains("retry_count=1"))
        assertTrue(message.contains("source=cold_start"))
        assertEquals(LogLevel.DEBUG, sink.levels.single())
        assertEquals(TestLogTag.value, sink.tags.single().value)
    }

    @Test
    fun releaseBuildFiltersDebugEvents() {
        val sink = RecordingLogSink()
        val logger = loggerFor(BuildMode.RELEASE, sink)

        logger.debug(tag = TestLogTag, event = "verbose_diagnostic")
        logger.info(tag = TestLogTag, event = "app_started")

        assertEquals(1, sink.messages.size)
        assertTrue(sink.messages.single().contains("level=INFO"))
        assertTrue(sink.messages.single().contains("event=app_started"))
    }

    @Test
    fun removesSensitiveAndInvalidFieldsAndEscapesControlCharacters() {
        val sink = RecordingLogSink()
        val logger = loggerFor(BuildMode.DEBUG, sink)

        logger.info(
            tag = TestLogTag,
            event = "network_failed",
            fields =
                logFields {
                    "authorization" value "Bearer secret"
                    "user_id" value "must-not-appear"
                    "invalid-key" value "ignored"
                    "step" value "restore\nsession\t1"
                },
        )

        val message = sink.messages.single()
        assertTrue(message.contains("level=INFO"))
        assertTrue(message.contains("event=network_failed"))
        assertTrue(message.contains("step=restore\\nsession\\t1"))
        assertFalse(message.contains("Bearer secret"))
        assertFalse(message.contains("must-not-appear"))
    }

    @Test
    fun keepsOnlyThrowableTypeAndUsesSafeFallbackForInvalidEvent() {
        val sink = RecordingLogSink()
        val logger = loggerFor(BuildMode.DEBUG, sink)

        logger.error(
            tag = TestLogTag,
            event = "User input must not be an event",
            cause = IllegalStateException("contains user content"),
        )

        val message = sink.messages.single()
        assertTrue(message.contains("level=ERROR"))
        assertTrue(message.contains("event=invalid_event"))
        assertTrue(message.contains("error_type=IllegalStateException"))
        assertFalse(message.contains("contains user content"))
    }

    @Test
    fun includesCurrentCoroutineTraceId() =
        runBlocking {
            val sink = RecordingLogSink()
            val logger = loggerFor(BuildMode.DEBUG, sink)
            val traceManager = DefaultAppTraceManager(logger)

            traceManager.withTrace(TraceId.requireValid("4bf92f3577b34da6a3ce929d0e0e4736")) {
                logger.info(tag = TestLogTag, event = "operation_started")
            }

            assertTrue(sink.messages.single().contains("trace_id=4bf92f3577b34da6a3ce929d0e0e4736"))
        }

    @Test
    fun logFieldsSupportOnlyTheExpectedScalarTypesAndOmitNullOrEmptyStrings() {
        val sink = RecordingLogSink()
        val logger = loggerFor(BuildMode.DEBUG, sink)

        logger.info(
            tag = TestLogTag,
            event = "state_observed",
            fields =
                logFields {
                    "enabled" value false
                    "count" value 0L
                    "mode" value ExampleMode.ACTIVE
                    "blank" value " "
                    "unused_null" value (null as String?)
                    "unused_empty" value ""
                },
        )

        val message = sink.messages.single()
        assertTrue(message.contains("enabled=false"))
        assertTrue(message.contains("count=0"))
        assertTrue(message.contains("mode=ACTIVE"))
        assertTrue(message.contains("blank= "))
        assertFalse(message.contains("unused_null="))
        assertFalse(message.contains("unused_empty="))
    }

    @Test
    fun boundsFieldValuesBeforeWriting() {
        val sink = RecordingLogSink()
        val logger = loggerFor(BuildMode.DEBUG, sink)

        logger.info(
            tag = TestLogTag,
            event = "payload_received",
            fields =
                logFields {
                    "payload" value "x".repeat(300)
                },
        )

        assertEquals(
            256,
            sink.messages
                .single()
                .substringAfter("payload=")
                .length,
        )
    }

    @Test
    fun sinkFailureIsIsolated() {
        val logger = loggerFor(BuildMode.DEBUG, ThrowingLogSink)

        logger.info(tag = TestLogTag, event = "app_started")
    }

    private fun loggerFor(
        buildMode: BuildMode,
        sink: PlatformLogSink,
    ): AppLogger =
        StructuredAppLogger(
            runtimeConfig = RuntimeConfig("https://api.example", "client-id", buildMode),
            sink = sink,
        )

    private class RecordingLogSink : PlatformLogSink {
        val messages = mutableListOf<String>()
        val levels = mutableListOf<LogLevel>()
        val tags = mutableListOf<LogTag>()

        override fun write(
            level: LogLevel,
            tag: LogTag,
            message: String,
        ) {
            levels += level
            tags += tag
            messages += message
        }
    }

    private object ThrowingLogSink : PlatformLogSink {
        override fun write(
            level: LogLevel,
            tag: LogTag,
            message: String,
        ) = error("sink failed")
    }

    private enum class ExampleMode {
        ACTIVE,
    }

    private companion object {
        val TestLogTag = LogTag.of("ObservabilityTest")
    }
}
