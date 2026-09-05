package com.nexusflow.observability

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogFormatterTest {
    @Test
    fun `pretty and json preserve the same safe fields`() {
        val record = record()

        val pretty = PrettyLogFormatter.format(record)
        val json = Json.parseToJsonElement(JsonLogFormatter.format(record)).jsonObject

        assertTrue(pretty.contains("trace_id=4bf92f3577b34da6a3ce929d0e0e4736"))
        assertTrue(pretty.contains("task_id=task-1"))
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", json["trace_id"]?.jsonPrimitive?.content)
        assertEquals("ai_request_finished", json["event"]?.jsonPrimitive?.content)
        assertEquals("task-1", json["task_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `structured logger sanitizes fields and isolates sink failure`() {
        val sink = RecordingSink(throwOnWrite = true)
        val logger =
            DefaultStructuredLogger(
                traceContext = { TraceId.requireValid("4bf92f3577b34da6a3ce929d0e0e4736") },
                formatter = PrettyLogFormatter,
                sink = sink,
                serviceName = "nexusflow-backend",
                environment = "local",
                minimumLevel = LogLevel.DEBUG,
            )

        logger.info(
            component = "AI Provider",
            event = "Bad event text",
            fields =
                logFields {
                    "authorization" value "Bearer secret"
                    "prompt_body" value "secret prompt"
                    "safe_stage" value "decode\nresult"
                },
        )

        assertEquals(1, sink.attempts)
        assertFalse(sink.messages.joinToString().contains("secret"))
        assertTrue(sink.messages.single().contains("service=nexusflow-backend"))
        assertTrue(sink.messages.single().contains("component=ai_provider"))
    }

    private fun record(): LogRecord =
        LogRecord(
            timestamp = "2026-09-03T00:00:00Z",
            level = LogLevel.INFO,
            traceId = "4bf92f3577b34da6a3ce929d0e0e4736",
            service = "nexusflow-backend",
            component = "ai",
            environment = "local",
            event = "ai_request_finished",
            fields =
                logFields {
                    "task_id" value "task-1"
                    "duration_ms" value 42
                },
        )

    private class RecordingSink(
        private val throwOnWrite: Boolean,
    ) : LogSink {
        var attempts = 0
        val messages = mutableListOf<String>()

        override fun write(
            level: LogLevel,
            component: String,
            formatted: String,
        ) {
            attempts += 1
            messages += formatted
            if (throwOnWrite) error("sink failed")
        }
    }
}
