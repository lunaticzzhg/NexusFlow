package com.nexusflow.ai.provider.compatible

import com.nexusflow.ai.provider.InvalidStructuredOutputException
import com.nexusflow.ai.provider.ProviderRateLimitedException
import com.nexusflow.ai.provider.ProviderRefusedException
import com.nexusflow.ai.provider.ProviderTimeoutException
import com.nexusflow.ai.provider.ProviderUnauthorizedException
import com.nexusflow.ai.provider.ProviderUnavailableException
import com.nexusflow.ai.provider.TextModelRequest
import com.nexusflow.ai.provider.TextModelResult
import com.nexusflow.ai.provider.TextModelResultMetadata
import com.nexusflow.ai.provider.StructuredModelFinishCategory
import com.nexusflow.contracts.backendai.common.StructuredModelCapability
import com.nexusflow.ai.provider.StructuredModelException
import com.nexusflow.ai.provider.StructuredModelRequest
import com.nexusflow.ai.provider.StructuredModelResult
import com.nexusflow.ai.provider.StructuredModelResultMetadata
import com.nexusflow.contracts.backendai.common.StructuredModelUsage
import com.nexusflow.observability.LogFields
import com.nexusflow.observability.StructuredLogger
import com.nexusflow.observability.logFields
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal class OpenAiCompatibleStructuredTransport(
    private val client: HttpClient,
    private val provider: String,
    private val apiKey: String,
    private val model: String,
    baseUrl: String,
    private val mode: OpenAiCompatibleMode,
    private val enableThinking: Boolean? = null,
    private val logger: StructuredLogger? = null,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    private val endpointUrl = "${baseUrl.trimEnd('/')}/${mode.path}"

    init {
        require(provider.isNotBlank()) { "provider must not be blank" }
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
        require(model.isNotBlank()) { "model must not be blank" }
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
    }

    suspend fun generate(request: StructuredModelRequest): StructuredModelResult {
        val started = TimeSource.Monotonic.markNow()
        logger?.info(
            component = AI_COMPONENT,
            event = "ai_request_started",
            fields = request.safeLogFields(),
        )
        val response = try {
            client.post(endpointUrl) {
                bearerAuth(apiKey)
                contentType(ContentType.Application.Json)
                setBody(mode.body(model, request, json, enableThinking))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpRequestTimeoutException) {
            throw ProviderTimeoutException(error).also { failure -> logFailure(request, started, failure) }
        } catch (error: IOException) {
            throw ProviderUnavailableException(error).also { failure -> logFailure(request, started, failure) }
        }

        when {
            response.status == HttpStatusCode.RequestTimeout ->
                throw ProviderTimeoutException().also { failure -> logFailure(request, started, failure) }
            response.status == HttpStatusCode.Unauthorized ||
                response.status == HttpStatusCode.Forbidden ->
                throw ProviderUnauthorizedException().also { failure -> logFailure(request, started, failure) }
            response.status.value == 429 ->
                throw ProviderRateLimitedException().also { failure -> logFailure(request, started, failure) }
            response.status.value >= 500 ->
                throw ProviderUnavailableException().also { failure -> logFailure(request, started, failure) }
            response.status.value !in 200..299 ->
                throw ProviderUnavailableException().also { failure -> logFailure(request, started, failure) }
        }

        val body = response.bodyAsText()
        return try {
            when (mode) {
                OpenAiCompatibleMode.Responses -> decodeResponses(body, request)
                OpenAiCompatibleMode.ChatJsonSchema,
                OpenAiCompatibleMode.ChatJsonObject,
                -> decodeChatCompletion(body, request)
            }.also { result ->
                logger?.info(
                    component = AI_COMPONENT,
                    event = "ai_request_finished",
                    fields = request.safeLogFields(started, result),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logFailure(request, started, error)
            throw error
        }
    }

    suspend fun stream(
        request: TextModelRequest,
        onDelta: suspend (String) -> Unit,
    ): TextModelResult {
        val started = TimeSource.Monotonic.markNow()
        logger?.info(
            component = AI_COMPONENT,
            event = "ai_request_started",
            fields = request.safeLogFields(),
        )
        val response = try {
            client.post(endpointUrl) {
                bearerAuth(apiKey)
                accept(ContentType.Text.EventStream)
                contentType(ContentType.Application.Json)
                setBody(mode.streamingBody(model, request, json, enableThinking))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpRequestTimeoutException) {
            throw ProviderTimeoutException(error).also { failure -> logFailure(request, started, failure) }
        } catch (error: IOException) {
            throw ProviderUnavailableException(error).also { failure -> logFailure(request, started, failure) }
        }

        when {
            response.status == HttpStatusCode.RequestTimeout ->
                throw ProviderTimeoutException().also { failure -> logFailure(request, started, failure) }
            response.status == HttpStatusCode.Unauthorized ||
                response.status == HttpStatusCode.Forbidden ->
                throw ProviderUnauthorizedException().also { failure -> logFailure(request, started, failure) }
            response.status.value == 429 ->
                throw ProviderRateLimitedException().also { failure -> logFailure(request, started, failure) }
            response.status.value >= 500 ->
                throw ProviderUnavailableException().also { failure -> logFailure(request, started, failure) }
            response.status.value !in 200..299 ->
                throw ProviderUnavailableException().also { failure -> logFailure(request, started, failure) }
        }

        val body = response.bodyAsChannel()
        val accumulator = StreamingAccumulator(request)
        val dataBuffer = StringBuilder()
        suspend fun flushData() {
            val payload = dataBuffer.toString()
            dataBuffer.clear()
            if (payload.isBlank() || payload == "[DONE]") return
            accumulator.consume(payload, onDelta)
        }
        try {
            while (true) {
                val line = body.readUTF8Line() ?: break
                if (line.isBlank()) {
                    flushData()
                } else if (line.startsWith("data:")) {
                    if (dataBuffer.isNotEmpty()) dataBuffer.append('\n')
                    dataBuffer.append(line.removePrefix("data:").trimStart())
                }
            }
            flushData()
            return accumulator.result().also { result ->
                logger?.info(
                    component = AI_COMPONENT,
                    event = "ai_request_finished",
                    fields = request.safeLogFields(started, result),
                )
            }
        } catch (error: CancellationException) {
            body.cancel(error)
            throw error
        } catch (error: Throwable) {
            logFailure(request, started, error)
            throw error
        }
    }

    private fun logFailure(
        request: StructuredModelRequest,
        started: TimeMark,
        failure: Throwable,
    ) {
        logger?.error(
            component = AI_COMPONENT,
            event = "ai_request_failed",
            fields =
                request.safeLogFields(started).withFailureCategory(
                    when (failure) {
                        is StructuredModelException -> failure.category.name.toSnakeCase()
                        else -> failure::class.simpleName?.toSnakeCase() ?: "unknown"
                    },
                ),
            cause = failure,
        )
    }

    private fun logFailure(
        request: TextModelRequest,
        started: TimeMark,
        failure: Throwable,
    ) {
        logger?.error(
            component = AI_COMPONENT,
            event = "ai_request_failed",
            fields =
                request.safeLogFields(started).withFailureCategory(
                    when (failure) {
                        is StructuredModelException -> failure.category.name.toSnakeCase()
                        else -> failure::class.simpleName?.toSnakeCase() ?: "unknown"
                    },
                ),
            cause = failure,
        )
    }

    private fun StructuredModelRequest.safeLogFields(
        started: TimeMark? = null,
        result: StructuredModelResult? = null,
    ): LogFields =
        logFields {
            "operation" value metadata.capability.toLogOperation()
            "provider" value provider
            "model" value model
            "attempt" value metadata.attemptNumber
            "prompt_version" value metadata.promptVersion
            "available_context_definition_count" value metadata.diagnostics.availableContextDefinitionCount
            "selected_context_key_count" value metadata.diagnostics.selectedContextKeyCount
            "resolved_context_block_count" value metadata.diagnostics.resolvedContextBlockCount
            "included_context_block_count" value metadata.diagnostics.includedContextBlockCount
            "omitted_context_block_count" value metadata.diagnostics.omittedContextBlockCount
            "optional_context_serialized_chars" value metadata.diagnostics.optionalContextSerializedChars
            "context_definitions_serialized_chars" value metadata.diagnostics.contextDefinitionsSerializedChars
            "full_user_payload_serialized_chars" value metadata.diagnostics.fullUserPayloadSerializedChars
            started?.let { "duration_ms" value it.elapsedNow().inWholeMilliseconds }
            result?.metadata?.usage?.inputTokens?.let { "input_tokens" value it }
            result?.metadata?.usage?.outputTokens?.let { "output_tokens" value it }
            result?.metadata?.usage?.totalTokens?.let { "total_tokens" value it }
            result?.metadata?.finishCategory?.let { "finish_category" value it.name.toSnakeCase() }
            result?.metadata?.providerRequestId?.let { "provider_request_id" value it }
        }

    private fun TextModelRequest.safeLogFields(
        started: TimeMark? = null,
        result: TextModelResult? = null,
    ): LogFields =
        logFields {
            "operation" value metadata.capability.toLogOperation()
            "provider" value provider
            "model" value model
            "attempt" value metadata.attemptNumber
            "prompt_version" value metadata.promptVersion
            "available_context_definition_count" value metadata.diagnostics.availableContextDefinitionCount
            "selected_context_key_count" value metadata.diagnostics.selectedContextKeyCount
            "resolved_context_block_count" value metadata.diagnostics.resolvedContextBlockCount
            "included_context_block_count" value metadata.diagnostics.includedContextBlockCount
            "omitted_context_block_count" value metadata.diagnostics.omittedContextBlockCount
            "optional_context_serialized_chars" value metadata.diagnostics.optionalContextSerializedChars
            "context_definitions_serialized_chars" value metadata.diagnostics.contextDefinitionsSerializedChars
            "full_user_payload_serialized_chars" value metadata.diagnostics.fullUserPayloadSerializedChars
            started?.let { "duration_ms" value it.elapsedNow().inWholeMilliseconds }
            result?.metadata?.usage?.inputTokens?.let { "input_tokens" value it }
            result?.metadata?.usage?.outputTokens?.let { "output_tokens" value it }
            result?.metadata?.usage?.totalTokens?.let { "total_tokens" value it }
            result?.metadata?.finishCategory?.let { "finish_category" value it.name.toSnakeCase() }
            result?.metadata?.providerRequestId?.let { "provider_request_id" value it }
        }

    private fun LogFields.withFailureCategory(failureCategory: String): LogFields =
        LogFields.from(values + ("failure_category" to failureCategory))

    private fun decodeResponses(
        body: String,
        request: StructuredModelRequest,
    ): StructuredModelResult {
        val response = try {
            json.decodeFromString<OpenAiResponsesResponse>(body)
        } catch (error: SerializationException) {
            throw InvalidStructuredOutputException("Provider response envelope was not valid structured output", error)
        }
        response.output
            .flatMap { it.content }
            .firstOrNull { !it.refusal.isNullOrBlank() }
            ?.let { throw ProviderRefusedException() }
        val outputText = response.outputText
            ?.takeIf(String::isNotBlank)
            ?: response.output
                .flatMap { it.content }
                .firstNotNullOfOrNull { content -> content.text?.takeIf(String::isNotBlank) }
            ?: throw InvalidStructuredOutputException("Provider response did not contain structured output text")
        return StructuredModelResult(
            outputText = outputText,
            metadata = StructuredModelResultMetadata(
                provider = provider,
                model = model,
                providerRequestId = response.id,
                attemptCount = request.metadata.attemptNumber,
                usage = response.usage?.toUsage(),
                finishCategory = StructuredModelFinishCategory.Complete,
                requestDiagnostics = request.metadata.diagnostics,
            ),
        )
    }

    private fun decodeChatCompletion(
        body: String,
        request: StructuredModelRequest,
    ): StructuredModelResult {
        val response = try {
            json.decodeFromString<OpenAiChatCompletionResponse>(body)
        } catch (error: SerializationException) {
            throw InvalidStructuredOutputException("Provider response envelope was not valid structured output", error)
        }
        val choice = response.choices.firstOrNull()
            ?: throw InvalidStructuredOutputException("Provider response did not contain a chat completion choice")
        if (!choice.message.refusal.isNullOrBlank() || choice.finishReason == "content_filter") {
            throw ProviderRefusedException()
        }
        val outputText = choice.message.content?.takeIf(String::isNotBlank)
            ?: throw InvalidStructuredOutputException("Provider response did not contain structured output text")
        return StructuredModelResult(
            outputText = outputText,
            metadata = StructuredModelResultMetadata(
                provider = provider,
                model = model,
                providerRequestId = response.id,
                attemptCount = request.metadata.attemptNumber,
                usage = response.usage?.toUsage(),
                finishCategory = choice.finishReason.toFinishCategory(),
                requestDiagnostics = request.metadata.diagnostics,
            ),
        )
    }

    private inner class StreamingAccumulator(
        private val request: TextModelRequest,
    ) {
        private val text = StringBuilder()
        private var providerRequestId: String? = null
        private var usage: StructuredModelUsage? = null
        private var finishCategory = StructuredModelFinishCategory.Complete

        suspend fun consume(
            payload: String,
            onDelta: suspend (String) -> Unit,
        ) {
            val event = try {
                json.decodeFromString<JsonElement>(payload).jsonObject
            } catch (error: SerializationException) {
                throw InvalidStructuredOutputException("Provider stream event was not valid JSON", error)
            }
            event["id"]?.jsonPrimitive?.contentOrNull?.let { providerRequestId = it }
            event["usage"]?.toUsageOrNull()?.let { usage = it }
            event["response"]?.jsonObjectOrNull()?.let { response ->
                response["id"]?.jsonPrimitive?.contentOrNull?.let { providerRequestId = it }
                response["usage"]?.toUsageOrNull()?.let { usage = it }
            }
            event["type"]?.jsonPrimitive?.contentOrNull?.let { type ->
                if ("refusal" in type || type == "response.refused") throw ProviderRefusedException()
            }
            event["choices"]?.jsonArrayOrNull()?.forEach { choiceElement ->
                val choice = choiceElement.jsonObject
                val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
                if (finishReason == "content_filter") throw ProviderRefusedException()
                finishReason?.toFinishCategory()?.let { finishCategory = it }
                choice["delta"]?.jsonObjectOrNull()
                    ?.get("content")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.let { emit(it, onDelta) }
            }
            event["delta"]?.jsonPrimitiveOrNull()?.contentOrNull?.let { emit(it, onDelta) }
        }

        fun result(): TextModelResult =
            TextModelResult(
                outputText = text.toString(),
                metadata = TextModelResultMetadata(
                    provider = provider,
                    model = model,
                    providerRequestId = providerRequestId,
                    attemptCount = request.metadata.attemptNumber,
                    usage = usage,
                    finishCategory = finishCategory,
                    requestDiagnostics = request.metadata.diagnostics,
                ),
            )

        private suspend fun emit(
            delta: String,
            onDelta: suspend (String) -> Unit,
        ) {
            if (delta.isEmpty()) return
            text.append(delta)
            onDelta(delta)
        }
    }
}

internal enum class OpenAiCompatibleMode(val path: String) {
    Responses("responses"),
    ChatJsonSchema("chat/completions"),
    ChatJsonObject("chat/completions"),
}

private fun String.toSnakeCase(): String =
    buildString(length + 4) {
        this@toSnakeCase.forEachIndexed { index, character ->
            if (character.isUpperCase() && index > 0) append('_')
            append(character.lowercaseChar())
        }
    }

private fun StructuredModelCapability.toLogOperation(): String =
    when (this) {
        StructuredModelCapability.UnderstandMessage -> "understanding"
        StructuredModelCapability.ConversationDecision -> "conversation_decision"
        StructuredModelCapability.ConversationAnswer -> "conversation_answer"
        StructuredModelCapability.PlanningResearch -> "planning_research"
        StructuredModelCapability.CreatePlans -> "plan_compose"
        StructuredModelCapability.ExplainPlans -> "plan_explain"
    }

private const val AI_COMPONENT = "ai"

private fun OpenAiCompatibleMode.body(
    model: String,
    request: StructuredModelRequest,
    json: Json,
    enableThinking: Boolean?,
): Any =
    request.userPayloadText(json).let { userPayload ->
        when (this) {
            OpenAiCompatibleMode.Responses -> OpenAiResponsesRequest(
                model = model,
                instructions = request.systemPrompt,
                input = userPayload,
                text = OpenAiTextConfig(
                    format = OpenAiResponsesJsonSchemaFormat(
                        type = "json_schema",
                        name = request.outputSchema.name,
                        schema = request.outputSchema.schema,
                        strict = request.outputSchema.strict,
                    ),
                ),
            )
            OpenAiCompatibleMode.ChatJsonSchema -> OpenAiChatCompletionRequest(
                model = model,
                messages = listOf(
                    OpenAiChatMessage(role = "system", content = request.systemPrompt),
                    OpenAiChatMessage(role = "user", content = userPayload),
                ),
                responseFormat = OpenAiChatResponseFormat(
                    type = "json_schema",
                    jsonSchema = OpenAiChatJsonSchema(
                        name = request.outputSchema.name,
                        schema = request.outputSchema.schema,
                        strict = request.outputSchema.strict,
                    ),
                ),
                enableThinking = enableThinking,
            )
            OpenAiCompatibleMode.ChatJsonObject -> OpenAiChatCompletionRequest(
                model = model,
                messages = listOf(
                    OpenAiChatMessage(
                        role = "system",
                        content = "${request.systemPrompt}\nReturn only JSON matching schema ${request.outputSchema.name}.",
                    ),
                    OpenAiChatMessage(role = "user", content = userPayload),
                ),
                responseFormat = OpenAiChatResponseFormat(type = "json_object"),
                enableThinking = enableThinking,
            )
        }
    }

private fun OpenAiCompatibleMode.streamingBody(
    model: String,
    request: TextModelRequest,
    json: Json,
    enableThinking: Boolean?,
): Any =
    request.userPayloadText(json).let { userPayload ->
        when (this) {
            OpenAiCompatibleMode.Responses -> OpenAiResponsesTextRequest(
                model = model,
                instructions = request.systemPrompt,
                input = userPayload,
                stream = true,
            )
            OpenAiCompatibleMode.ChatJsonSchema,
            OpenAiCompatibleMode.ChatJsonObject,
            -> OpenAiChatCompletionRequest(
                model = model,
                messages = listOf(
                    OpenAiChatMessage(role = "system", content = request.systemPrompt),
                    OpenAiChatMessage(role = "user", content = userPayload),
                ),
                stream = true,
                enableThinking = enableThinking,
            )
        }
    }

private fun StructuredModelRequest.userPayloadText(json: Json): String =
    json.encodeToString(JsonObject.serializer(), userPayload)

private fun TextModelRequest.userPayloadText(json: Json): String =
    json.encodeToString(JsonObject.serializer(), userPayload)

private fun OpenAiTokenUsage.toUsage(): StructuredModelUsage =
    StructuredModelUsage(
        inputTokens = inputTokens ?: promptTokens,
        outputTokens = outputTokens ?: completionTokens,
        totalTokens = totalTokens,
    )

private fun String?.toFinishCategory(): StructuredModelFinishCategory =
    when (this) {
        null,
        "stop",
        -> StructuredModelFinishCategory.Complete
        "length" -> StructuredModelFinishCategory.Length
        "content_filter" -> StructuredModelFinishCategory.Refusal
        else -> StructuredModelFinishCategory.Unknown
    }

private fun JsonElement.jsonObjectOrNull(): JsonObject? =
    this as? JsonObject

private fun JsonElement.jsonArrayOrNull() =
    runCatching { jsonArray }.getOrNull()

private fun JsonElement.jsonPrimitiveOrNull() =
    runCatching { jsonPrimitive }.getOrNull()

private fun JsonElement.toUsageOrNull(): StructuredModelUsage? =
    runCatching {
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        json.decodeFromJsonElement(OpenAiTokenUsage.serializer(), this).toUsage()
    }.getOrNull()

@Serializable
private data class OpenAiResponsesRequest(
    @SerialName("model")
    val model: String,
    @SerialName("instructions")
    val instructions: String,
    @SerialName("input")
    val input: String,
    @SerialName("text")
    val text: OpenAiTextConfig,
)

@Serializable
private data class OpenAiResponsesTextRequest(
    @SerialName("model")
    val model: String,
    @SerialName("instructions")
    val instructions: String,
    @SerialName("input")
    val input: String,
    @SerialName("stream")
    val stream: Boolean,
)

@Serializable
private data class OpenAiTextConfig(
    @SerialName("format")
    val format: OpenAiResponsesJsonSchemaFormat,
)

@Serializable
private data class OpenAiResponsesJsonSchemaFormat(
    @SerialName("type")
    val type: String,
    @SerialName("name")
    val name: String,
    @SerialName("schema")
    val schema: JsonObject,
    @SerialName("strict")
    val strict: Boolean,
)

@Serializable
private data class OpenAiResponsesResponse(
    @SerialName("id")
    val id: String? = null,
    @SerialName("output_text")
    val outputText: String? = null,
    @SerialName("output")
    val output: List<OpenAiOutputItem> = emptyList(),
    @SerialName("usage")
    val usage: OpenAiTokenUsage? = null,
)

@Serializable
private data class OpenAiOutputItem(
    @SerialName("content")
    val content: List<OpenAiOutputContent> = emptyList(),
)

@Serializable
private data class OpenAiOutputContent(
    @SerialName("text")
    val text: String? = null,
    @SerialName("refusal")
    val refusal: String? = null,
)

@Serializable
private data class OpenAiChatCompletionRequest(
    @SerialName("model")
    val model: String,
    @SerialName("messages")
    val messages: List<OpenAiChatMessage>,
    @SerialName("response_format")
    val responseFormat: OpenAiChatResponseFormat? = null,
    @SerialName("stream")
    val stream: Boolean? = null,
    @SerialName("enable_thinking")
    val enableThinking: Boolean? = null,
)

@Serializable
private data class OpenAiChatMessage(
    @SerialName("role")
    val role: String,
    @SerialName("content")
    val content: String,
)

@Serializable
private data class OpenAiChatResponseFormat(
    @SerialName("type")
    val type: String,
    @SerialName("json_schema")
    val jsonSchema: OpenAiChatJsonSchema? = null,
)

@Serializable
private data class OpenAiChatJsonSchema(
    @SerialName("name")
    val name: String,
    @SerialName("schema")
    val schema: JsonObject,
    @SerialName("strict")
    val strict: Boolean,
)

@Serializable
private data class OpenAiChatCompletionResponse(
    @SerialName("id")
    val id: String? = null,
    @SerialName("choices")
    val choices: List<OpenAiChatChoice> = emptyList(),
    @SerialName("usage")
    val usage: OpenAiTokenUsage? = null,
)

@Serializable
private data class OpenAiChatChoice(
    @SerialName("message")
    val message: OpenAiChatMessageResponse,
    @SerialName("finish_reason")
    val finishReason: String? = null,
)

@Serializable
private data class OpenAiChatMessageResponse(
    @SerialName("content")
    val content: String? = null,
    @SerialName("refusal")
    val refusal: String? = null,
)

@Serializable
private data class OpenAiTokenUsage(
    @SerialName("input_tokens")
    val inputTokens: Int? = null,
    @SerialName("output_tokens")
    val outputTokens: Int? = null,
    @SerialName("prompt_tokens")
    val promptTokens: Int? = null,
    @SerialName("completion_tokens")
    val completionTokens: Int? = null,
    @SerialName("total_tokens")
    val totalTokens: Int? = null,
)
