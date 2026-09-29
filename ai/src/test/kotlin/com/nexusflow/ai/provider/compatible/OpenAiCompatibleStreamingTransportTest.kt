package com.nexusflow.ai.provider.compatible

import com.nexusflow.ai.provider.InvalidStructuredOutputException
import com.nexusflow.ai.provider.ProviderRateLimitedException
import com.nexusflow.ai.provider.ProviderRefusedException
import com.nexusflow.ai.provider.ProviderRequestException
import com.nexusflow.ai.provider.TextModelRequest
import com.nexusflow.ai.provider.TextModelRequestMetadata
import com.nexusflow.ai.provider.TurnModelRequest
import com.nexusflow.ai.provider.TurnModelRequestMetadata
import com.nexusflow.ai.provider.TurnModelResult
import com.nexusflow.ai.provider.TurnModelTool
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.ServerSocket
import kotlin.concurrent.thread
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
            assertEquals(true, Json.parseToJsonElement(requestBody).jsonObject.getValue("stream_options").jsonObject.getValue("include_usage").jsonPrimitive.content.toBoolean())
        }


    @Test
    fun `responses stream emits delta before provider completes over real http`() =
        runBlocking {
            val firstChunkSent = CompletableDeferred<Unit>()
            val releaseCompletion = CompletableDeferred<Unit>()
            val client = HttpClient {
                install(ContentNegotiation) {
                    json()
                }
            }
            val server = ServerSocket(0)
            val serverThread = thread(start = true, isDaemon = true) {
                server.use { socket ->
                    socket.accept().use { client ->
                        val input = client.getInputStream().bufferedReader()
                        var contentLength = 0
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                contentLength = line.substringAfter(':').trim().toInt()
                            }
                        }
                        if (contentLength > 0) {
                            val discard = CharArray(contentLength)
                            var read = 0
                            while (read < contentLength) {
                                val count = input.read(discard, read, contentLength - read)
                                if (count < 0) break
                                read += count
                            }
                        }
                        val output = client.getOutputStream()
                        output.write(
                            "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                                .toByteArray(),
                        )
                        fun writeChunk(value: String) {
                            val bytes = value.toByteArray()
                            output.write(bytes.size.toString(16).toByteArray())
                            output.write("\r\n".toByteArray())
                            output.write(bytes)
                            output.write("\r\n".toByteArray())
                            output.flush()
                        }
                        writeChunk("data: {\"type\":\"response.output_text.delta\",\"delta\":\"early\"}\n\n")
                        firstChunkSent.complete(Unit)
                        runBlocking { releaseCompletion.await() }
                        writeChunk("data: {\"type\":\"response.completed\",\"response\":{\"id\":\"real-http-response\"}}\n\n")
                        output.write("0\r\n\r\n".toByteArray())
                        output.flush()
                    }
                }
            }
            try {
                val transport = transport(
                    mode = OpenAiCompatibleMode.Responses,
                    baseUrl = "http://127.0.0.1:${server.localPort}/v1",
                    client = client,
                )
                val firstDelta = CompletableDeferred<String>()
                val streamResult = async {
                    transport.stream(request()) { delta ->
                        firstDelta.complete(delta)
                    }
                }

                withTimeout(1_000) { firstChunkSent.await() }
                assertEquals("early", withTimeout(1_000) { firstDelta.await() })
                assertEquals(false, streamResult.isCompleted)
                releaseCompletion.complete(Unit)

                val result = withTimeout(1_000) { streamResult.await() }
                assertEquals("early", result.outputText)
                assertEquals("real-http-response", result.metadata.providerRequestId)
            } finally {
                releaseCompletion.complete(Unit)
                client.close()
                server.close()
                serverThread.join(1_000)
            }
        }

    @Test
    fun `responses turn stream emits answer delta before provider completes over real http`() =
        runBlocking {
            val firstChunkSent = CompletableDeferred<Unit>()
            val releaseCompletion = CompletableDeferred<Unit>()
            val client = HttpClient {
                install(ContentNegotiation) {
                    json()
                }
            }
            val server = ServerSocket(0)
            val serverThread = thread(start = true, isDaemon = true) {
                server.use { socket ->
                    socket.accept().use { client ->
                        val input = client.getInputStream().bufferedReader()
                        var contentLength = 0
                        while (true) {
                            val line = input.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                                contentLength = line.substringAfter(':').trim().toInt()
                            }
                        }
                        if (contentLength > 0) {
                            val discard = CharArray(contentLength)
                            var read = 0
                            while (read < contentLength) {
                                val count = input.read(discard, read, contentLength - read)
                                if (count < 0) break
                                read += count
                            }
                        }
                        val output = client.getOutputStream()
                        output.write(
                            "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                                .toByteArray(),
                        )
                        fun writeChunk(value: String) {
                            val bytes = value.toByteArray()
                            output.write(bytes.size.toString(16).toByteArray())
                            output.write("\r\n".toByteArray())
                            output.write(bytes)
                            output.write("\r\n".toByteArray())
                            output.flush()
                        }
                        writeChunk("data: {\"type\":\"response.output_text.delta\",\"delta\":\"turn-early\"}\n\n")
                        firstChunkSent.complete(Unit)
                        runBlocking { releaseCompletion.await() }
                        writeChunk("data: {\"type\":\"response.completed\",\"response\":{\"id\":\"real-http-turn-response\"}}\n\n")
                        output.write("0\r\n\r\n".toByteArray())
                        output.flush()
                    }
                }
            }
            try {
                val transport = transport(
                    mode = OpenAiCompatibleMode.Responses,
                    baseUrl = "http://127.0.0.1:${server.localPort}/v1",
                    client = client,
                )
                val firstDelta = CompletableDeferred<String>()
                val streamResult = async {
                    transport.streamTurn(turnRequest()) { delta ->
                        firstDelta.complete(delta)
                    }
                }

                withTimeout(1_000) { firstChunkSent.await() }
                assertEquals("turn-early", withTimeout(1_000) { firstDelta.await() })
                assertEquals(false, streamResult.isCompleted)
                releaseCompletion.complete(Unit)

                val result = withTimeout(1_000) { streamResult.await() }
                assertEquals("turn-early", (result as TurnModelResult.Text).text)
                assertEquals("real-http-turn-response", result.metadata.providerRequestId)
            } finally {
                releaseCompletion.complete(Unit)
                client.close()
                server.close()
                serverThread.join(1_000)
            }
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
    fun `responses text stream ignores non output text deltas`() =
        runBlocking {
            val transport = transport(
                mode = OpenAiCompatibleMode.Responses,
                engine = MockEngine {
                    respond(
                        content = """
                            data: {"type":"response.reasoning_summary_text.delta","delta":"hidden reasoning"}

                            data: {"type":"response.output_text.delta","delta":"Visible"}

                            data: {"type":"response.completed","response":{"id":"response-request-2"}}

                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                },
            )
            val deltas = mutableListOf<String>()

            val result = transport.stream(request()) { deltas += it }

            assertEquals(listOf("Visible"), deltas)
            assertEquals("Visible", result.outputText)
        }

    @Test
    fun `invalid stream JSON has a distinct safe stage`() =
        runBlocking {
            val transport = transport(
                mode = OpenAiCompatibleMode.Responses,
                engine = MockEngine {
                    respond(
                        content = "data: {private invalid payload}\n\n",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                },
            )

            val error = assertFailsWith<InvalidStructuredOutputException> {
                transport.stream(request()) { }
            }
            assertEquals("invalid_stream_json", error.failureStage)
        }

    @Test
    fun `responses text stream rejects function call events`() =
        runBlocking {
            val transport = transport(
                mode = OpenAiCompatibleMode.Responses,
                engine = MockEngine {
                    respond(
                        content = """
                            data: {"type":"response.output_item.added","output_index":0,"item":{"id":"fc_1","type":"function_call","name":"research"}}

                            data: {"type":"response.completed","response":{"id":"response-request-tool"}}

                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                },
            )

            val error = assertFailsWith<InvalidStructuredOutputException> {
                transport.stream(request()) { }
            }
            assertEquals("unexpected_tool_call", error.failureStage)
            Unit
        }

    @Test
    fun `chat turn tool call is grouped by index when id appears only in first chunk`() =
        runBlocking {
            var requestBody = ""
            val transport = transport(
                mode = OpenAiCompatibleMode.ChatJsonSchema,
                engine = MockEngine { request ->
                    requestBody = (request.body as TextContent).text
                    respond(
                        content = """
                            data: {"id":"chat-request-1","choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_123","type":"function","function":{"name":"rese","arguments":"{\"q\""}}]},"finish_reason":null}]}

                            data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"","function":{"name":"arch","arguments":":\"x\"}"}}]},"finish_reason":null}]}

                            data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}

                            data: {"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":2,"total_tokens":3}}

                            data: [DONE]

                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                },
            )

            val result = transport.streamTurn(turnRequest()) { }

            val call = assertTrueResultTool(result)
            assertEquals("research", call.name)
            assertEquals("{\"q\":\"x\"}", call.argumentsJson)
            assertEquals(3, call.metadata.usage?.totalTokens)
            val body = Json.parseToJsonElement(requestBody).jsonObject
            val tool = body.getValue("tools").jsonArray.single().jsonObject
            assertEquals("function", tool.getValue("type").jsonPrimitive.content)
            assertEquals(false, body.getValue("parallel_tool_calls").jsonPrimitive.content.toBoolean())
            assertEquals(true, body.getValue("stream_options").jsonObject.getValue("include_usage").jsonPrimitive.content.toBoolean())
            assertEquals(false, tool.getValue("function").jsonObject.getValue("strict").jsonPrimitive.content.toBoolean())
        }

    @Test
    fun `responses turn tool done arguments are validated without duplicating streamed deltas`() =
        runBlocking {
            var requestBody = ""
            val transport = transport(
                mode = OpenAiCompatibleMode.Responses,
                engine = MockEngine { request ->
                    requestBody = (request.body as TextContent).text
                    respond(
                        content = """
                            data: {"type":"response.output_item.added","output_index":0,"item":{"id":"fc_1","type":"function_call","name":"research"}}

                            data: {"type":"response.function_call_arguments.delta","output_index":0,"item_id":"fc_1","delta":"{\"q\""}

                            data: {"type":"response.function_call_arguments.delta","output_index":0,"item_id":"fc_1","delta":":\"x\"}"}

                            data: {"type":"response.function_call_arguments.done","output_index":0,"item_id":"fc_1","arguments":"{\"q\":\"x\"}"}

                            data: {"type":"response.completed","response":{"id":"response-request-tool"}}

                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                },
            )

            val result = transport.streamTurn(turnRequest()) { }

            val call = assertTrueResultTool(result)
            assertEquals("research", call.name)
            assertEquals("{\"q\":\"x\"}", call.argumentsJson)
            val body = Json.parseToJsonElement(requestBody).jsonObject
            val tool = body.getValue("tools").jsonArray.single().jsonObject
            assertEquals("function", tool.getValue("type").jsonPrimitive.content)
            assertEquals(false, body.getValue("parallel_tool_calls").jsonPrimitive.content.toBoolean())
            assertEquals(false, tool.getValue("strict").jsonPrimitive.content.toBoolean())
        }

    @Test
    fun `chat turn rejects a second tool index even when call ids are empty`() = runBlocking {
        val transport = transport(
            mode = OpenAiCompatibleMode.ChatJsonSchema,
            engine = MockEngine {
                respond(
                    content = """
                        data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"research","arguments":"{}"}}]}}]}

                        data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"","function":{"arguments":"{}"}}]}}]}

                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                )
            },
        )

        val error = assertFailsWith<InvalidStructuredOutputException> { transport.streamTurn(turnRequest()) { } }
        assertEquals("multiple_tool_calls", error.failureStage)
    }

    @Test
    fun `oversized single line and multiline SSE events fail before JSON parsing`() = runBlocking {
        val oversizedSingleLine = "data: " + "private payload".repeat(19_000) + "\n\n"
        val oversizedMultiline = buildString {
            repeat(3) { append("data: ").append("private payload".repeat(7_000)).append('\n') }
            append('\n')
        }
        listOf(oversizedSingleLine, oversizedMultiline).forEach { content ->
            val transport = transport(
                mode = OpenAiCompatibleMode.ChatJsonSchema,
                engine = MockEngine {
                    respond(
                        content = content,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                },
            )
            val error = assertFailsWith<InvalidStructuredOutputException> { transport.streamTurn(turnRequest()) { } }
            assertEquals("stream_event_too_large", error.failureStage)
        }
    }

    @Test
    fun `responses turn rejects mismatched done arguments`() =
        runBlocking {
            val transport = transport(
                mode = OpenAiCompatibleMode.Responses,
                engine = MockEngine {
                    respond(
                        content = """
                            data: {"type":"response.output_item.added","output_index":0,"item":{"id":"fc_1","type":"function_call","name":"research"}}

                            data: {"type":"response.function_call_arguments.delta","output_index":0,"item_id":"fc_1","delta":"{\"q\":\"x\"}"}

                            data: {"type":"response.function_call_arguments.done","output_index":0,"item_id":"fc_1","arguments":"{\"q\":\"y\"}"}

                            data: {"type":"response.completed","response":{"id":"response-request-tool"}}

                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                },
            )

            assertFailsWith<InvalidStructuredOutputException> {
                transport.streamTurn(turnRequest()) { }
            }
            Unit
        }

    @Test
    fun `turn text stream rejects incomplete eof`() =
        runBlocking {
            val transport = transport(
                mode = OpenAiCompatibleMode.Responses,
                engine = MockEngine {
                    respond(
                        content = """
                            data: {"type":"response.output_text.delta","delta":"partial"}

                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                },
            )

            val error = assertFailsWith<InvalidStructuredOutputException> {
                transport.streamTurn(turnRequest()) { }
            }
            assertEquals("incomplete_stream", error.failureStage)
            Unit
        }


    @Test
    fun `chat turn rejects refusal delta and non zero choice index`() =
        runBlocking {
            val refusal = transport(
                mode = OpenAiCompatibleMode.ChatJsonSchema,
                engine = MockEngine {
                    respond(
                        content = """
                            data: {"choices":[{"index":0,"delta":{"refusal":"no"},"finish_reason":null}]}

                            data: [DONE]

                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                },
            )
            assertFailsWith<ProviderRefusedException> {
                refusal.streamTurn(turnRequest()) { }
            }

            val multipleChoices = transport(
                mode = OpenAiCompatibleMode.ChatJsonSchema,
                engine = MockEngine {
                    respond(
                        content = """
                            data: {"choices":[{"index":1,"delta":{"content":"other"},"finish_reason":"stop"}]}

                            data: [DONE]

                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                },
            )
            assertFailsWith<InvalidStructuredOutputException> {
                multipleChoices.streamTurn(turnRequest()) { }
            }
            Unit
        }

    @Test
    fun `chat text stream rejects refusal delta and non zero choice index`() =
        runBlocking {
            val refusal = transport(
                mode = OpenAiCompatibleMode.ChatJsonSchema,
                engine = MockEngine {
                    respond(
                        content = """
                            data: {"choices":[{"index":0,"delta":{"refusal":"no"},"finish_reason":null}]}

                            data: [DONE]

                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                },
            )
            assertFailsWith<ProviderRefusedException> {
                refusal.stream(request()) { }
            }

            val multipleChoices = transport(
                mode = OpenAiCompatibleMode.ChatJsonSchema,
                engine = MockEngine {
                    respond(
                        content = """
                            data: {"choices":[{"index":1,"delta":{"content":"other"},"finish_reason":"stop"}]}

                            data: [DONE]

                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                },
            )
            assertFailsWith<InvalidStructuredOutputException> {
                multipleChoices.stream(request()) { }
            }
            Unit
        }

    @Test
    fun `responses turn done event validates call identity even without name`() =
        runBlocking {
            val transport = transport(
                mode = OpenAiCompatibleMode.Responses,
                engine = MockEngine {
                    respond(
                        content = """
                            data: {"type":"response.output_item.added","output_index":0,"item":{"id":"fc_1","type":"function_call","name":"research"}}

                            data: {"type":"response.function_call_arguments.delta","output_index":0,"item_id":"fc_1","delta":"{\"q\":\"x\"}"}

                            data: {"type":"response.function_call_arguments.done","output_index":1,"item_id":"fc_2","arguments":"{\"q\":\"x\"}"}

                            data: {"type":"response.completed","response":{"id":"response-request-tool"}}

                        """.trimIndent(),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                },
            )

            val error = assertFailsWith<InvalidStructuredOutputException> {
                transport.streamTurn(turnRequest()) { }
            }
            assertEquals("multiple_tool_calls", error.failureStage)
            Unit
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

            val badRequest = transport(
                mode = OpenAiCompatibleMode.ChatJsonSchema,
                engine = MockEngine {
                    respond(
                        content = """{"error":"bad request"}""",
                        status = HttpStatusCode.BadRequest,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                },
            )
            assertFailsWith<ProviderRequestException> {
                badRequest.streamTurn(turnRequest()) { }
            }
            Unit
        }


    private fun turnRequest(): TurnModelRequest =
        TurnModelRequest(
            systemPrompt = "system prompt",
            userPayload = JsonObject(mapOf("message" to JsonPrimitive("hello"))),
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
                requestId = "turn-request-1",
                promptVersion = "prompt-v1",
                capability = StructuredModelCapability.ConversationTurn,
                attemptNumber = 1,
                diagnostics = StructuredModelRequestDiagnostics(fullUserPayloadSerializedChars = 42),
            ),
        )

    private fun assertTrueResultTool(result: TurnModelResult): TurnModelResult.ToolCall {
        assertTrue(result is TurnModelResult.ToolCall)
        return result
    }

    private fun transport(
        mode: OpenAiCompatibleMode,
        engine: MockEngine,
    ): OpenAiCompatibleStructuredTransport =
        transport(
            mode = mode,
            baseUrl = "https://api.example/v1",
            client = HttpClient(engine) {
                install(ContentNegotiation) {
                    json()
                }
            },
        )

    private fun transport(
        mode: OpenAiCompatibleMode,
        baseUrl: String,
        client: HttpClient = HttpClient {
            install(ContentNegotiation) {
                json()
            }
        },
    ): OpenAiCompatibleStructuredTransport =
        OpenAiCompatibleStructuredTransport(
            client = client,
            provider = "openai",
            apiKey = "test",
            model = "test-model",
            baseUrl = baseUrl,
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
