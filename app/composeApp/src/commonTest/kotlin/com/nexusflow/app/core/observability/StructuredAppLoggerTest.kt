package com.nexusflow.app.core.observability

import com.nexusflow.app.core.config.BuildMode
import com.nexusflow.app.core.config.RuntimeConfig
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

        assertEquals(
            "level=DEBUG event=app_started retry_count=1 source=cold_start",
            sink.messages.single(),
        )
        assertEquals(LogLevel.DEBUG, sink.levels.single())
        assertEquals(TestLogTag.value, sink.tags.single().value)
    }

    @Test
    fun releaseBuildFiltersDebugEvents() {
        val sink = RecordingLogSink()
        val logger = loggerFor(BuildMode.RELEASE, sink)

        logger.debug(tag = TestLogTag, event = "verbose_diagnostic")
        logger.info(tag = TestLogTag, event = "app_started")

        assertEquals(listOf("level=INFO event=app_started"), sink.messages)
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

        assertEquals(
            "level=INFO event=network_failed step=restore\\nsession\\t1",
            sink.messages.single(),
        )
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

        assertEquals(
            "level=ERROR event=invalid_event error_type=IllegalStateException",
            sink.messages.single(),
        )
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
