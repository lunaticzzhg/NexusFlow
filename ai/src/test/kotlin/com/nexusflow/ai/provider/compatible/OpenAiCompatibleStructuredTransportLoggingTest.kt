package com.nexusflow.ai.provider.compatible

import com.nexusflow.ai.provider.ProviderUnauthorizedException
import com.nexusflow.ai.provider.StructuredModelCapability
import com.nexusflow.ai.provider.StructuredModelRequest
import com.nexusflow.ai.provider.StructuredModelRequestDiagnostics
import com.nexusflow.ai.provider.StructuredModelRequestMetadata
import com.nexusflow.ai.provider.StructuredOutputSchema
import com.nexusflow.observability.LogFields
import com.nexusflow.observability.LogLevel
import com.nexusflow.observability.StructuredLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiCompatibleStructuredTransportLoggingTest {
    @Test
    fun `logs safe provider start and finish fields`() =
        runBlocking {
            val logger = RecordingLogger()
            val transport =
                transport(
                    logger = logger,
                    engine =
                        MockEngine {
                            respond(
                                content = SUCCESS_BODY,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                )

            transport.generate(request())

            assertEquals(listOf("ai_request_started", "ai_request_finished"), logger.entries.map { it.event })
            val finish = logger.entries.last()
            assertEquals(LogLevel.INFO, finish.level)
            assertEquals("ai", finish.component)
            assertEquals("understanding", finish.fields["operation"])
            assertEquals("openai", finish.fields["provider"])
            assertEquals("test-model", finish.fields["model"])
            assertEquals("1", finish.fields["attempt"])
            assertEquals("10", finish.fields["input_tokens"])
            assertEquals("5", finish.fields["output_tokens"])
            assertEquals("complete", finish.fields["finish_category"])
            assertEquals("provider-request-1", finish.fields["provider_request_id"])
            assertNoSensitiveLogContent(logger)
        }

    @Test
    fun `logs safe provider failure fields`() =
        runBlocking {
            val logger = RecordingLogger()
            val transport =
                transport(
                    logger = logger,
                    engine =
                        MockEngine {
                            respond(
                                content = """{"error":"raw secret body"}""",
                                status = HttpStatusCode.Unauthorized,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                )

            assertFailsWith<ProviderUnauthorizedException> {
                transport.generate(request())
            }

            assertEquals(listOf("ai_request_started", "ai_request_failed"), logger.entries.map { it.event })
            val failure = logger.entries.last()
            assertEquals(LogLevel.ERROR, failure.level)
            assertEquals("ProviderUnauthorizedException", failure.errorType)
            assertEquals("openai", failure.fields["provider"])
            assertTrue(failure.fields.containsKey("duration_ms"))
            assertNoSensitiveLogContent(logger)
        }

    @Test
    fun `does not infer retry events from attempt numbers`() =
        runBlocking {
            val logger = RecordingLogger()
            val transport =
                transport(
                    logger = logger,
                    engine =
                        MockEngine {
                            respond(
                                content = SUCCESS_BODY,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                )

            transport.generate(request(attemptNumber = 2))

            assertEquals(listOf("ai_request_started", "ai_request_finished"), logger.entries.map { it.event })
            assertNoSensitiveLogContent(logger)
        }

    private fun transport(
        logger: StructuredLogger,
        engine: MockEngine,
    ): OpenAiCompatibleStructuredTransport =
        OpenAiCompatibleStructuredTransport(
            client =
                HttpClient(engine) {
                    install(ContentNegotiation) {
                        json()
                    }
                },
            provider = "openai",
            apiKey = "api-key-secret",
            model = "test-model",
            baseUrl = "https://api.example/v1",
            mode = OpenAiCompatibleMode.ChatJsonSchema,
            logger = logger,
        )

    private fun request(attemptNumber: Int = 1): StructuredModelRequest =
        StructuredModelRequest(
            systemPrompt = "system prompt with secret",
            userPayload = JsonObject(mapOf("message" to JsonPrimitive("raw user secret"))),
            outputSchema = StructuredOutputSchema("test_schema", JsonObject(emptyMap())),
            metadata =
                StructuredModelRequestMetadata(
                    requestId = "diagnostic-request-id",
                    promptVersion = "prompt-v1",
                    capability = StructuredModelCapability.UserMessageUnderstanding,
                    attemptNumber = attemptNumber,
                    diagnostics =
                        StructuredModelRequestDiagnostics(
                            includedContextBlockCount = 2,
                            fullUserPayloadSerializedChars = 123,
                        ),
                ),
        )

    private fun assertNoSensitiveLogContent(logger: RecordingLogger) {
        val rendered = logger.entries.joinToString("|") { entry ->
            entry.fields.entries.joinToString("|") { (key, value) -> "$key=$value" } + "|${entry.errorType.orEmpty()}"
        }
        assertFalse(rendered.contains("secret"))
        assertFalse(rendered.contains("api-key"))
        assertFalse(rendered.contains("system prompt"))
        assertFalse(rendered.contains("raw user"))
        assertFalse(rendered.contains("raw secret body"))
    }

    private data class Entry(
        val level: LogLevel,
        val component: String,
        val event: String,
        val fields: Map<String, String>,
        val errorType: String?,
    )

    private class RecordingLogger : StructuredLogger {
        val entries = mutableListOf<Entry>()

        override fun log(
            level: LogLevel,
            component: String,
            event: String,
            fields: LogFields,
            cause: Throwable?,
        ) {
            entries += Entry(level, component, event, fields.values, cause?.let { it::class.simpleName })
        }
    }

    private companion object {
        const val SUCCESS_BODY =
            """
            {
              "id": "provider-request-1",
              "choices": [
                {
                  "message": { "content": "{\"ok\":true}" },
                  "finish_reason": "stop"
                }
              ],
              "usage": {
                "prompt_tokens": 10,
                "completion_tokens": 5,
                "total_tokens": 15
              }
            }
            """
    }
}
