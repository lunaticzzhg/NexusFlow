package com.nexusflow.ai.provider.compatible

import com.nexusflow.ai.provider.ProviderRateLimitedException
import com.nexusflow.ai.provider.ProviderRefusedException
import com.nexusflow.ai.provider.TextModelRequest
import com.nexusflow.ai.provider.TextModelRequestMetadata
import com.nexusflow.contracts.backendai.common.StructuredModelCapability
import com.nexusflow.contracts.backendai.common.StructuredModelRequestDiagnostics
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenAiCompatibleStreamingTransportTest {
    @Test
    fun `chat stream normalizes deltas and assembles final text`() =
        runBlocking {
            var requestBody = ""
            val transport = transport(
                mode = OpenAiCompatibleMode.ChatJsonSchema,
                engine = MockEngine { request ->
                    requestBody = (request.body as TextContent).text
                    respond(
                        content = """
                            data: {"id":"provider-request-1","choices":[{"delta":{"content":"你"},"finish_reason":null}]}

                            data: {"choices":[{"delta":{"content":"好\n深圳"},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}

                            data: [DONE]

                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                },
            )
            val deltas = mutableListOf<String>()

            val result = transport.stream(request()) { delta -> deltas += delta }

            assertTrue(requestBody.contains(""""stream":true"""))
            assertEquals(listOf("你", "好\n深圳"), deltas)
            assertEquals("你好\n深圳", result.outputText)
            assertEquals(result.outputText, deltas.joinToString(separator = ""))
            assertEquals("provider-request-1", result.metadata.providerRequestId)
            assertEquals(5, result.metadata.usage?.totalTokens)
        }

    @Test
    fun `responses stream normalizes output text delta events`() =
        runBlocking {
            val transport = transport(
                mode = OpenAiCompatibleMode.Responses,
                engine = MockEngine {
                    respond(
                        content = """
                            data: {"type":"response.output_text.delta","delta":"Hello "}

                            data: {"type":"response.output_text.delta","delta":"world"}

                            data: {"type":"response.completed","response":{"id":"response-request-1"}}

                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                },
            )
            val deltas = mutableListOf<String>()

            val result = transport.stream(request()) { delta -> deltas += delta }

            assertEquals(listOf("Hello ", "world"), deltas)
            assertEquals("Hello world", result.outputText)
            assertEquals("response-request-1", result.metadata.providerRequestId)
        }

    @Test
    fun `stream refusal and rate limit map to provider failures`() =
        runBlocking {
            val refusal = transport(
                mode = OpenAiCompatibleMode.Responses,
                engine = MockEngine {
                    respond(
                        content = """data: {"type":"response.refusal.delta","delta":"no"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                },
            )
            assertFailsWith<ProviderRefusedException> {
                refusal.stream(request()) { }
            }

            val rateLimited = transport(
                mode = OpenAiCompatibleMode.ChatJsonSchema,
                engine = MockEngine {
                    respond(
                        content = """{"error":"rate limited"}""",
                        status = HttpStatusCode.TooManyRequests,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            )
            assertFailsWith<ProviderRateLimitedException> {
                rateLimited.stream(request()) { }
            }
            Unit
        }

    private fun transport(
        mode: OpenAiCompatibleMode,
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
            apiKey = "test",
            model = "test-model",
            baseUrl = "https://api.example/v1",
            mode = mode,
        )

    private fun request(): TextModelRequest =
        TextModelRequest(
            systemPrompt = "system prompt",
            userPayload = JsonObject(mapOf("message" to JsonPrimitive("hello"))),
            metadata = TextModelRequestMetadata(
                requestId = "request-1",
                promptVersion = "prompt-v1",
                capability = StructuredModelCapability.ConversationAnswer,
                attemptNumber = 1,
                diagnostics = StructuredModelRequestDiagnostics(fullUserPayloadSerializedChars = 42),
            ),
        )
}
