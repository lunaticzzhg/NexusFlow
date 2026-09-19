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

        assertTrue(pretty.contains("INFO component=ai event=ai_request_finished"))
        assertFalse(pretty.contains("action=ai_request_finished"))
        assertTrue(pretty.contains("trace=4bf92f3577b3"))
        assertTrue(pretty.contains("operation_type=conversation_turn"))
        assertTrue(pretty.contains("step=answer_finished"))
        assertTrue(pretty.contains("operation_id=response-run"))
        assertTrue(pretty.contains("branch=chat_answer"))
        assertTrue(pretty.contains("stage=turn"))
        assertTrue(pretty.contains("outcome=finished"))
        assertTrue(pretty.contains("duration_ms=42"))
        assertFalse(pretty.contains("response_run_id=response-run"))
        assertFalse(pretty.contains("task_id=task-1"))
        assertFalse(pretty.contains("provider_request_id=provider-request-1"))
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", json["trace_id"]?.jsonPrimitive?.content)
        assertEquals("ai_request_finished", json["event"]?.jsonPrimitive?.content)
        assertEquals("task-1", json["task_id"]?.jsonPrimitive?.content)
        assertEquals("response-run", json["response_run_id"]?.jsonPrimitive?.content)
        assertEquals("conversation_turn", json["operation_type"]?.jsonPrimitive?.content)
        assertEquals("chat_answer", json["branch"]?.jsonPrimitive?.content)
        assertEquals("provider-request-1", json["provider_request_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `debug pretty includes detail fields that info omits`() {
        val pretty = PrettyLogFormatter.format(record(level = LogLevel.DEBUG))

        assertTrue(pretty.contains("DEBUG component=ai event=ai_request_finished"))
        assertTrue(pretty.contains("detail=\""))
        assertTrue(pretty.contains("response_run_id=response-run"))
        assertTrue(pretty.contains("task_id=task-1"))
        assertTrue(pretty.contains("provider_request_id=provider-request-1"))
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
        assertTrue(sink.messages.single().contains("INFO component=ai_provider event=invalid_event"))
        assertTrue(sink.messages.single().contains("component=ai_provider"))
    }

    private fun record(level: LogLevel = LogLevel.INFO): LogRecord =
        LogRecord(
            timestamp = "2026-09-03T00:00:00Z",
            level = level,
            traceId = "4bf92f3577b34da6a3ce929d0e0e4736",
            service = "nexusflow-backend",
            component = "ai",
            environment = "local",
            event = "ai_request_finished",
            fields =
                logFields {
                    "task_id" value "task-1"
                    "response_run_id" value "response-run"
                    "operation_type" value "conversation_turn"
                    "operation_id" value "response-run"
                    "branch" value "chat_answer"
                    "stage" value "turn"
                    "step" value "answer_finished"
                    "conversation_id" value "conversation"
                    "ai_request_id" value "ai-request"
                    "operation" value "understanding"
                    "finish_category" value "complete"
                    "outcome" value "finished"
                    "duration_ms" value 42
                    "provider_request_id" value "provider-request-1"
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
