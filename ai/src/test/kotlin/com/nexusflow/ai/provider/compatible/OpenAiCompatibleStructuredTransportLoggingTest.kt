package com.nexusflow.ai.provider.compatible

import com.nexusflow.ai.provider.ProviderUnauthorizedException
import com.nexusflow.ai.provider.ProviderRequestException
import com.nexusflow.contracts.backendai.common.StructuredModelCapability
import com.nexusflow.ai.provider.StructuredModelRequest
import com.nexusflow.contracts.backendai.common.StructuredModelRequestDiagnostics
import com.nexusflow.ai.provider.StructuredModelRequestMetadata
import com.nexusflow.ai.provider.StructuredOutputSchema
import com.nexusflow.ai.provider.TurnModelRequest
import com.nexusflow.ai.provider.TurnModelRequestMetadata
import com.nexusflow.ai.provider.TurnModelTool
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
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OpenAiCompatibleStructuredTransportLoggingTest {
    @Test
    fun `logs safe provider start and finish fields at debug`() =
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
            assertEquals(LogLevel.DEBUG, logger.entries.first().level)
            assertEquals(LogLevel.DEBUG, finish.level)
            assertEquals("ai", finish.component)
            assertEquals("diagnostic-request-id", finish.fields["request_id"])
            assertEquals("diagnostic-request-id", finish.fields["ai_request_id"])
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
            assertEquals("diagnostic-request-id", failure.fields["request_id"])
            assertEquals("diagnostic-request-id", failure.fields["ai_request_id"])
            assertEquals("openai", failure.fields["provider"])
            assertTrue(failure.fields.containsKey("duration_ms"))
            assertNoSensitiveLogContent(logger)
        }

    @Test
    fun `deterministic provider request failure logs once without raw body`() =
        runBlocking {
            val logger = RecordingLogger()
            val transport =
                transport(
                    logger = logger,
                    engine =
                        MockEngine {
                            respond(
                                content = """{"error":{"code":"invalid_parameter","message":"raw schema body"}}""",
                                status = HttpStatusCode.BadRequest,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                )

            assertFailsWith<ProviderRequestException> {
                transport.generate(request())
            }

            assertEquals(listOf("ai_request_started", "ai_request_failed"), logger.entries.map { it.event })
            val failure = logger.entries.last()
            assertEquals("provider_request", failure.fields["failure_category"])
            assertEquals("400", failure.fields["provider_http_status"])
            assertEquals("invalid_parameter", failure.fields["provider_error_code"])
            assertEquals("ProviderRequestException", failure.errorType)
            assertNoSensitiveLogContent(logger)
        }

    @Test
    fun `oversized provider request failure keeps status and omits provider error code`() =
        runBlocking {
            val logger = RecordingLogger()
            val oversizedBody = """{"error":{"code":"invalid_parameter","padding":"${"x".repeat(5_000)}"}}"""
            val transport =
                transport(
                    logger = logger,
                    engine =
                        MockEngine {
                            respond(
                                content = oversizedBody,
                                status = HttpStatusCode.BadRequest,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                )

            assertFailsWith<ProviderRequestException> {
                transport.generate(request())
            }

            assertEquals(listOf("ai_request_started", "ai_request_failed"), logger.entries.map { it.event })
            val failure = logger.entries.last()
            assertEquals("provider_request", failure.fields["failure_category"])
            assertEquals("400", failure.fields["provider_http_status"])
            assertFalse(failure.fields.containsKey("provider_error_code"))
            assertNoSensitiveLogContent(logger)
        }

    @Test
    fun `stream turn provider request failure logs once with safe diagnostics`() =
        runBlocking {
            val logger = RecordingLogger()
            val transport =
                transport(
                    logger = logger,
                    engine =
                        MockEngine {
                            respond(
                                content = """{"error":{"code":"invalid_tool_schema","message":"raw tool schema body"}}""",
                                status = HttpStatusCode.BadRequest,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                )

            assertFailsWith<ProviderRequestException> {
                transport.streamTurn(turnRequest()) { }
            }

            assertEquals(listOf("ai_request_started", "ai_request_failed"), logger.entries.map { it.event })
            val failure = logger.entries.last()
            assertEquals("provider_request", failure.fields["failure_category"])
            assertEquals("400", failure.fields["provider_http_status"])
            assertEquals("invalid_tool_schema", failure.fields["provider_error_code"])
            assertEquals("ProviderRequestException", failure.errorType)
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

    @Test
    fun `logs conversation decision capability operation`() =
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

            transport.generate(request(capability = StructuredModelCapability.ConversationDecision))

            assertEquals("conversation_decision", logger.entries.last().fields["operation"])
            assertNoSensitiveLogContent(logger)
        }

    @Test
    fun `chat completion request can disable thinking`() =
        runBlocking {
            var requestBody = ""
            val transport =
                transport(
                    logger = RecordingLogger(),
                    enableThinking = false,
                    engine =
                        MockEngine { request ->
                            requestBody = (request.body as TextContent).text
                            respond(
                                content = SUCCESS_BODY,
                                status = HttpStatusCode.OK,
                                headers = headersOf(HttpHeaders.ContentType, "application/json"),
                            )
                        },
                )

            transport.generate(request())

            assertTrue(requestBody.contains(""""enable_thinking":false"""))
        }

    private fun transport(
        logger: StructuredLogger,
        engine: MockEngine,
        enableThinking: Boolean? = null,
    ): OpenAiCompatibleStructuredTransport =
        OpenAiCompatibleStructuredTransport(
            client =
                HttpClient(engine) {
                    install(ContentNegotiation) {
                        json()
                    }
                },
            provider = "openai",
            apiKey = "test",
            model = "test-model",
            baseUrl = "https://api.example/v1",
            mode = OpenAiCompatibleMode.ChatJsonSchema,
            enableThinking = enableThinking,
            logger = logger,
        )

    private fun request(
        attemptNumber: Int = 1,
        capability: StructuredModelCapability = StructuredModelCapability.UnderstandMessage,
    ): StructuredModelRequest =
        StructuredModelRequest(
            systemPrompt = "system prompt with secret",
            userPayload = JsonObject(mapOf("message" to JsonPrimitive("raw user secret"))),
            outputSchema = StructuredOutputSchema("test_schema", JsonObject(emptyMap())),
            metadata =
                StructuredModelRequestMetadata(
                    requestId = "diagnostic-request-id",
                    promptVersion = "prompt-v1",
                    capability = capability,
                    attemptNumber = attemptNumber,
                    diagnostics =
                        StructuredModelRequestDiagnostics(
                            includedContextBlockCount = 2,
                            fullUserPayloadSerializedChars = 123,
                        ),
                ),
        )

    private fun turnRequest(): TurnModelRequest =
        TurnModelRequest(
            systemPrompt = "system prompt with secret",
            userPayload = JsonObject(mapOf("message" to JsonPrimitive("raw user secret"))),
            tools = listOf(
                TurnModelTool(
                    name = "research",
                    description = "Research current facts.",
                    parameters = buildJsonObject {
                        put("type", "object")
                        put("additionalProperties", true)
                    },
                ),
            ),
            metadata = TurnModelRequestMetadata(
                requestId = "turn-diagnostic-request-id",
                promptVersion = "turn-prompt-v1",
                capability = StructuredModelCapability.ConversationTurn,
                attemptNumber = 1,
                diagnostics = StructuredModelRequestDiagnostics(fullUserPayloadSerializedChars = 42),
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
