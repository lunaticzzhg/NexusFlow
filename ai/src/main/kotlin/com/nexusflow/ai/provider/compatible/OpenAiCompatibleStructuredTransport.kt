package com.nexusflow.ai.provider.compatible

import com.nexusflow.ai.provider.InvalidStructuredOutputException
import com.nexusflow.ai.provider.ProviderRateLimitedException
import com.nexusflow.ai.provider.ProviderRefusedException
import com.nexusflow.ai.provider.ProviderRequestException
import com.nexusflow.ai.provider.ProviderTimeoutException
import com.nexusflow.ai.provider.ProviderUnauthorizedException
import com.nexusflow.ai.provider.ProviderUnavailableException
import com.nexusflow.ai.provider.TextModelRequest
import com.nexusflow.ai.provider.TextModelResult
import com.nexusflow.ai.provider.TextModelResultMetadata
import com.nexusflow.ai.provider.TurnModelRequest
import com.nexusflow.ai.provider.TurnModelResult
import com.nexusflow.ai.provider.TurnModelResultMetadata
import com.nexusflow.ai.provider.TurnModelTool
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
import io.ktor.client.request.preparePost
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
        logger?.debug(
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

        response.failureForStatus()?.let { failure ->
            throw failure.also { logFailure(request, started, it) }
        }

        val body = response.bodyAsText()
        return try {
            when (mode) {
                OpenAiCompatibleMode.Responses -> decodeResponses(body, request)
                OpenAiCompatibleMode.ChatJsonSchema,
                OpenAiCompatibleMode.ChatJsonObject,
                -> decodeChatCompletion(body, request)
            }.also { result ->
                logger?.debug(
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
        logger?.debug(
            component = AI_COMPONENT,
            event = "ai_request_started",
            fields = request.safeLogFields(),
        )
        return try {
            client.preparePost(endpointUrl) {
                bearerAuth(apiKey)
                accept(ContentType.Text.EventStream)
                contentType(ContentType.Application.Json)
                setBody(mode.streamingBody(model, request, json, enableThinking))
            }.execute { response ->
                response.requireSuccessful(request, started)
                val body = response.bodyAsChannel()
                val accumulator = TextStreamingAccumulator(request)
                readSse(body) { payload -> accumulator.consume(payload, onDelta) }
                accumulator.result().also { result ->
                    logger?.debug(
                        component = AI_COMPONENT,
                        event = "ai_request_finished",
                        fields = request.safeLogFields(started, result),
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpRequestTimeoutException) {
            throw ProviderTimeoutException(error).also { failure -> logFailure(request, started, failure) }
        } catch (error: IOException) {
            throw ProviderUnavailableException(error).also { failure -> logFailure(request, started, failure) }
        } catch (error: StructuredModelException) {
            throw error.also { failure -> logFailure(request, started, failure) }
        } catch (error: Throwable) {
            logFailure(request, started, error)
            throw error
        }
    }

    suspend fun streamTurn(
        request: TurnModelRequest,
        onTextDelta: suspend (String) -> Unit,
    ): TurnModelResult {
        val started = TimeSource.Monotonic.markNow()
        logger?.debug(
            component = AI_COMPONENT,
            event = "ai_request_started",
            fields = request.safeLogFields(),
        )
        return try {
            client.preparePost(endpointUrl) {
                bearerAuth(apiKey)
                accept(ContentType.Text.EventStream)
                contentType(ContentType.Application.Json)
                setBody(mode.turnStreamingBody(model, request, json, enableThinking))
            }.execute { response ->
                response.requireSuccessful(request, started)
                val body = response.bodyAsChannel()
                val accumulator = TurnStreamingAccumulator(request)
                readSse(body) { payload -> accumulator.consume(payload, onTextDelta) }
                accumulator.result().also { result ->
                    logger?.debug(
                        component = AI_COMPONENT,
                        event = "ai_request_finished",
                        fields = request.safeLogFields(started, result),
                    )
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpRequestTimeoutException) {
            throw ProviderTimeoutException(error).also { failure -> logFailure(request, started, failure) }
        } catch (error: IOException) {
            throw ProviderUnavailableException(error).also { failure -> logFailure(request, started, failure) }
        } catch (error: StructuredModelException) {
            throw error.also { failure -> logFailure(request, started, failure) }
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
                request.safeLogFields(started).withFailure(failure),
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
                request.safeLogFields(started).withFailure(failure),
            cause = failure,
        )
    }

    private fun logFailure(
        request: TurnModelRequest,
        started: TimeMark,
        failure: Throwable,
    ) {
        logger?.error(
            component = AI_COMPONENT,
            event = "ai_request_failed",
            fields =
                request.safeLogFields(started).withFailure(failure),
            cause = failure,
        )
    }

    private fun StructuredModelRequest.safeLogFields(
        started: TimeMark? = null,
        result: StructuredModelResult? = null,
    ): LogFields =
        logFields {
            "request_id" value metadata.requestId
            "ai_request_id" value metadata.requestId
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
            "request_id" value metadata.requestId
            "ai_request_id" value metadata.requestId
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

    private fun TurnModelRequest.safeLogFields(
        started: TimeMark? = null,
        result: TurnModelResult? = null,
    ): LogFields {
        val metadata = when (result) {
            is TurnModelResult.Text -> result.metadata
            is TurnModelResult.ToolCall -> result.metadata
            null -> null
        }
        return logFields {
            "request_id" value this@safeLogFields.metadata.requestId
            "ai_request_id" value this@safeLogFields.metadata.requestId
            "operation" value this@safeLogFields.metadata.capability.toLogOperation()
            "provider" value provider
            "model" value model
            "attempt" value this@safeLogFields.metadata.attemptNumber
            "prompt_version" value this@safeLogFields.metadata.promptVersion
            "tool_count" value tools.size
            "available_context_definition_count" value this@safeLogFields.metadata.diagnostics.availableContextDefinitionCount
            "selected_context_key_count" value this@safeLogFields.metadata.diagnostics.selectedContextKeyCount
            "resolved_context_block_count" value this@safeLogFields.metadata.diagnostics.resolvedContextBlockCount
            "included_context_block_count" value this@safeLogFields.metadata.diagnostics.includedContextBlockCount
            "omitted_context_block_count" value this@safeLogFields.metadata.diagnostics.omittedContextBlockCount
            "optional_context_serialized_chars" value this@safeLogFields.metadata.diagnostics.optionalContextSerializedChars
            "context_definitions_serialized_chars" value this@safeLogFields.metadata.diagnostics.contextDefinitionsSerializedChars
            "full_user_payload_serialized_chars" value this@safeLogFields.metadata.diagnostics.fullUserPayloadSerializedChars
            started?.let { "duration_ms" value it.elapsedNow().inWholeMilliseconds }
            metadata?.usage?.inputTokens?.let { "input_tokens" value it }
            metadata?.usage?.outputTokens?.let { "output_tokens" value it }
            metadata?.usage?.totalTokens?.let { "total_tokens" value it }
            metadata?.finishCategory?.let { "finish_category" value it.name.toSnakeCase() }
            metadata?.providerRequestId?.let { "provider_request_id" value it }
        }
    }

    private fun LogFields.withFailure(failure: Throwable): LogFields {
        val failureCategory = when (failure) {
            is StructuredModelException -> failure.category.name.toSnakeCase()
            else -> failure::class.simpleName?.toSnakeCase() ?: "unknown"
        }
        val safeDiagnostics = when (failure) {
            is ProviderRequestException -> buildMap {
                failure.httpStatusCode?.let { put("provider_http_status", it.toString()) }
                failure.providerErrorCode?.let { put("provider_error_code", it) }
            }
            else -> emptyMap()
        }
        return LogFields.from(values + ("failure_category" to failureCategory) + safeDiagnostics)
    }

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

    private suspend fun readSse(
        body: io.ktor.utils.io.ByteReadChannel,
        onPayload: suspend (String) -> Unit,
    ) {
        val dataBuffer = StringBuilder()
        suspend fun flushData() {
            val payload = dataBuffer.toString()
            dataBuffer.clear()
            if (payload.isBlank() || payload == "[DONE]") return
            onPayload(payload)
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
        } catch (error: CancellationException) {
            body.cancel(error)
            throw error
        }
    }

    private suspend fun io.ktor.client.statement.HttpResponse.requireSuccessful(
        request: TextModelRequest,
        started: TimeMark,
    ) {
        failureForStatus()?.let { throw it }
    }

    private suspend fun io.ktor.client.statement.HttpResponse.requireSuccessful(
        request: TurnModelRequest,
        started: TimeMark,
    ) {
        failureForStatus()?.let { throw it }
    }

    private suspend fun io.ktor.client.statement.HttpResponse.failureForStatus(): StructuredModelException? =
        when {
            status == HttpStatusCode.RequestTimeout -> ProviderTimeoutException()
            status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden -> ProviderUnauthorizedException()
            status.value == 429 -> ProviderRateLimitedException()
            status.value >= 500 -> ProviderUnavailableException()
            status.value !in 200..299 -> ProviderRequestException(
                httpStatusCode = status.value,
                providerErrorCode = safeProviderErrorCodeFromBodyOrNull(),
            )
            else -> null
        }

    private suspend fun io.ktor.client.statement.HttpResponse.safeProviderErrorCodeFromBodyOrNull(): String? {
        val body = runCatching {
            bodyAsChannel().readUTF8Line(MAX_PROVIDER_ERROR_BODY_CHARS + 1)
        }.getOrNull() ?: return null
        if (body.length > MAX_PROVIDER_ERROR_BODY_CHARS) return null
        return body.safeProviderErrorCodeOrNull()
    }

    private fun String.safeProviderErrorCodeOrNull(): String? {
        val root = runCatching { json.parseToJsonElement(this).jsonObjectOrNull() }.getOrNull() ?: return null
        val nestedError = root["error"]?.jsonObjectOrNull()
        return listOfNotNull(
            nestedError?.get("code")?.jsonPrimitiveOrNull()?.contentOrNull,
            nestedError?.get("type")?.jsonPrimitiveOrNull()?.contentOrNull,
            root["code"]?.jsonPrimitiveOrNull()?.contentOrNull,
            root["type"]?.jsonPrimitiveOrNull()?.contentOrNull,
        ).firstOrNull { code -> code.isSafeProviderErrorCode() }
    }

    private inner class TextStreamingAccumulator(
        private val request: TextModelRequest,
    ) {
        private val text = StringBuilder()
        private var providerRequestId: String? = null
        private var usage: StructuredModelUsage? = null
        private var finishCategory: StructuredModelFinishCategory? = null
        private var sawCompleted = false

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
            val type = event["type"]?.jsonPrimitive?.contentOrNull
            if (type != null) {
                if ("refusal" in type || type == "response.refused") throw ProviderRefusedException()
                if (type == "response.failed" || type == "error") {
                    throw ProviderUnavailableException()
                }
                if (type.contains("function_call") || type == "response.output_item.added") {
                    throw InvalidStructuredOutputException("Provider text stream emitted a tool call")
                }
                if (type == "response.output_text.delta") {
                    event["delta"]?.jsonPrimitiveOrNull()?.contentOrNull?.let { emit(it, onDelta) }
                }
                if (type == "response.completed") {
                    sawCompleted = true
                    finishCategory = StructuredModelFinishCategory.Complete
                }
                if (type == "response.incomplete") {
                    finishCategory = StructuredModelFinishCategory.Length
                }
            }
            event["choices"]?.jsonArrayOrNull()?.forEach { choiceElement ->
                val choice = choiceElement.jsonObject
                val choiceIndex = choice["index"]?.jsonPrimitiveOrNull()?.contentOrNull
                if (choiceIndex != null && choiceIndex != "0") {
                    throw InvalidStructuredOutputException("Provider emitted multiple chat choices")
                }
                val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
                if (finishReason == "content_filter") throw ProviderRefusedException()
                val delta = choice["delta"]?.jsonObjectOrNull()
                if (delta?.get("refusal") != null) throw ProviderRefusedException()
                if (delta?.get("tool_calls") != null || finishReason == "tool_calls") {
                    throw InvalidStructuredOutputException("Provider text stream emitted a tool call")
                }
                finishReason?.toFinishCategory()?.let {
                    finishCategory = it
                    if (it == StructuredModelFinishCategory.Complete) sawCompleted = true
                }
                delta
                    ?.get("content")
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.let { emit(it, onDelta) }
            }
            if (type == null) {
                event["delta"]?.jsonPrimitiveOrNull()?.contentOrNull?.let { emit(it, onDelta) }
            }
        }

        fun result(): TextModelResult {
            val category = finishCategory
            if (!sawCompleted || category != StructuredModelFinishCategory.Complete) {
                throw InvalidStructuredOutputException("Provider stream ended before a complete answer")
            }
            if (text.isBlank()) {
                throw InvalidStructuredOutputException("Provider stream did not contain answer text")
            }
            return TextModelResult(
                outputText = text.toString(),
                metadata = TextModelResultMetadata(
                    provider = provider,
                    model = model,
                    providerRequestId = providerRequestId,
                    attemptCount = request.metadata.attemptNumber,
                    usage = usage,
                    finishCategory = category,
                    requestDiagnostics = request.metadata.diagnostics,
                ),
            )
        }

        private suspend fun emit(
            delta: String,
            onDelta: suspend (String) -> Unit,
        ) {
            if (delta.isEmpty()) return
            if (text.length + delta.length > MAX_STREAM_TEXT_CHARS) {
                throw InvalidStructuredOutputException("Provider stream text exceeded the maximum length")
            }
            text.append(delta)
            onDelta(delta)
        }
    }

    private inner class TurnStreamingAccumulator(
        private val request: TurnModelRequest,
    ) {
        private val text = StringBuilder()
        private val toolArguments = StringBuilder()
        private var providerRequestId: String? = null
        private var usage: StructuredModelUsage? = null
        private var finishCategory: StructuredModelFinishCategory? = null
        private var branch: TurnBranch? = null
        private var sawCompleted = false
        private var toolName: String? = null
        private var toolIdentity: String? = null
        private var toolProviderId: String? = null

        suspend fun consume(
            payload: String,
            onTextDelta: suspend (String) -> Unit,
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
            val type = event["type"]?.jsonPrimitive?.contentOrNull
            if (type != null) consumeResponsesEvent(type, event, onTextDelta)
            event["choices"]?.jsonArrayOrNull()?.forEach { choiceElement -> consumeChatChoice(choiceElement.jsonObject, onTextDelta) }
        }

        private suspend fun consumeResponsesEvent(
            type: String,
            event: JsonObject,
            onTextDelta: suspend (String) -> Unit,
        ) {
            if ("refusal" in type || type == "response.refused") throw ProviderRefusedException()
            if (type == "response.failed" || type == "error") {
                throw ProviderUnavailableException()
            }
            when (type) {
                "response.output_text.delta" -> emitText(event["delta"]?.jsonPrimitiveOrNull()?.contentOrNull.orEmpty(), onTextDelta)
                "response.output_item.added" -> {
                    val item = event["item"]?.jsonObjectOrNull() ?: return
                    val itemType = item["type"]?.jsonPrimitive?.contentOrNull
                    val name = item["name"]?.jsonPrimitive?.contentOrNull
                    val identity = event.responsesToolIdentity(item)
                    if (itemType == "function_call" && !name.isNullOrBlank()) startTool(name, identity.key, identity.providerId)
                }
                "response.function_call_arguments.delta" -> emitToolArguments(
                    event["delta"]?.jsonPrimitiveOrNull()?.contentOrNull.orEmpty(),
                    event.responsesToolIdentity().key,
                    event.responsesToolIdentity().providerId,
                )
                "response.function_call_arguments.done" -> {
                    val identity = event.responsesToolIdentity()
                    validateToolIdentity(identity.key, identity.providerId)
                    event["name"]?.jsonPrimitiveOrNull()?.contentOrNull?.let { startTool(it, identity.key, identity.providerId) }
                    event["arguments"]?.jsonPrimitiveOrNull()?.contentOrNull?.let { doneArguments ->
                        if (toolArguments.isBlank()) {
                            if (doneArguments.length > MAX_TOOL_ARGUMENT_CHARS) {
                                throw InvalidStructuredOutputException("Provider tool arguments exceeded the maximum length")
                            }
                            toolArguments.append(doneArguments)
                        } else if (toolArguments.toString() != doneArguments) {
                            throw InvalidStructuredOutputException("Provider tool arguments done event did not match streamed arguments")
                        }
                    }
                }
                "response.completed" -> {
                    sawCompleted = true
                    finishCategory = StructuredModelFinishCategory.Complete
                }
                "response.incomplete" -> finishCategory = StructuredModelFinishCategory.Length
            }
        }

        private suspend fun consumeChatChoice(
            choice: JsonObject,
            onTextDelta: suspend (String) -> Unit,
        ) {
            val choiceIndex = choice["index"]?.jsonPrimitiveOrNull()?.contentOrNull
            if (choiceIndex != null && choiceIndex != "0") {
                throw InvalidStructuredOutputException("Provider emitted multiple chat choices")
            }
            val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
            if (finishReason == "content_filter") throw ProviderRefusedException()
            finishReason?.toFinishCategory()?.let {
                finishCategory = it
                if (it == StructuredModelFinishCategory.Complete) sawCompleted = true
            }
            if (finishReason == "tool_calls") {
                finishCategory = StructuredModelFinishCategory.Complete
                sawCompleted = true
            }
            val delta = choice["delta"]?.jsonObjectOrNull() ?: return
            if (delta["refusal"] != null) throw ProviderRefusedException()
            delta["content"]?.jsonPrimitiveOrNull()?.contentOrNull?.let { emitText(it, onTextDelta) }
            delta["tool_calls"]?.jsonArrayOrNull()?.forEach { element ->
                val call = element.jsonObject
                val identity = call.chatToolIdentity()
                call["function"]?.jsonObjectOrNull()?.let { function ->
                    function["name"]?.jsonPrimitiveOrNull()?.contentOrNull?.let { startTool(it, identity.key, identity.providerId) }
                    function["arguments"]?.jsonPrimitiveOrNull()?.contentOrNull?.let {
                        emitToolArguments(it, identity.key, identity.providerId)
                    }
                }
            }
        }

        fun result(): TurnModelResult {
            val category = finishCategory
            if (!sawCompleted || category != StructuredModelFinishCategory.Complete) {
                throw InvalidStructuredOutputException("Provider stream ended before a complete turn result")
            }
            val metadata = TurnModelResultMetadata(
                provider = provider,
                model = model,
                providerRequestId = providerRequestId,
                attemptCount = request.metadata.attemptNumber,
                usage = usage,
                finishCategory = category,
                requestDiagnostics = request.metadata.diagnostics,
            )
            return when (branch) {
                TurnBranch.Text -> {
                    if (text.isBlank()) throw InvalidStructuredOutputException("Provider turn text was blank")
                    TurnModelResult.Text(text.toString(), metadata)
                }
                TurnBranch.Tool -> {
                    val name = toolName ?: throw InvalidStructuredOutputException("Provider tool call was missing a name")
                    val arguments = toolArguments.toString().trim()
                    if (arguments.isBlank()) throw InvalidStructuredOutputException("Provider tool call arguments were blank")
                    TurnModelResult.ToolCall(name, arguments, metadata)
                }
                null -> throw InvalidStructuredOutputException("Provider turn stream did not contain text or a tool call")
            }
        }

        private suspend fun emitText(
            delta: String,
            onTextDelta: suspend (String) -> Unit,
        ) {
            if (delta.isEmpty()) return
            if (branch == TurnBranch.Tool) {
                throw InvalidStructuredOutputException("Provider mixed text with a tool call")
            }
            if (text.length + delta.length > MAX_STREAM_TEXT_CHARS) {
                throw InvalidStructuredOutputException("Provider turn text exceeded the maximum length")
            }
            branch = TurnBranch.Text
            text.append(delta)
            onTextDelta(delta)
        }

        private fun startTool(
            name: String,
            identity: String?,
            providerId: String?,
        ) {
            if (name.isBlank()) return
            if (branch == TurnBranch.Text) {
                throw InvalidStructuredOutputException("Provider mixed a tool call with text")
            }
            val normalizedIdentity = identity ?: "single"
            if (toolIdentity != null && toolIdentity != normalizedIdentity) {
                throw InvalidStructuredOutputException("Provider emitted multiple tool calls")
            }
            if (providerId != null && toolProviderId != null && toolProviderId != providerId) {
                throw InvalidStructuredOutputException("Provider changed tool call identity")
            }
            if (toolName != null && toolName != name) {
                throw InvalidStructuredOutputException("Provider emitted multiple tool calls")
            }
            branch = TurnBranch.Tool
            toolName = name
            toolIdentity = normalizedIdentity
            if (providerId != null) toolProviderId = providerId
        }

        private fun emitToolArguments(
            delta: String,
            identity: String?,
            providerId: String?,
        ) {
            if (branch == TurnBranch.Text) {
                throw InvalidStructuredOutputException("Provider mixed tool arguments with text")
            }
            validateToolIdentity(identity, providerId)
            if (delta.isEmpty()) return
            if (toolArguments.length + delta.length > MAX_TOOL_ARGUMENT_CHARS) {
                throw InvalidStructuredOutputException("Provider tool arguments exceeded the maximum length")
            }
            branch = TurnBranch.Tool
            toolIdentity = identity ?: toolIdentity ?: "single"
            if (providerId != null) toolProviderId = providerId
            toolArguments.append(delta)
        }

        private fun validateToolIdentity(
            identity: String?,
            providerId: String?,
        ) {
            val normalizedIdentity = identity ?: toolIdentity ?: "single"
            if (toolIdentity != null && toolIdentity != normalizedIdentity) {
                throw InvalidStructuredOutputException("Provider emitted multiple tool calls")
            }
            if (providerId != null && toolProviderId != null && toolProviderId != providerId) {
                throw InvalidStructuredOutputException("Provider changed tool call identity")
            }
        }

        private fun JsonObject.chatToolIdentity(): ToolEventIdentity =
            ToolEventIdentity(
                key = this["index"]?.jsonPrimitiveOrNull()?.contentOrNull?.let { "chat-index:$it" }
                    ?: this["id"]?.jsonPrimitiveOrNull()?.contentOrNull?.let { "chat-id:$it" },
                providerId = this["id"]?.jsonPrimitiveOrNull()?.contentOrNull,
            )

        private fun JsonObject.responsesToolIdentity(item: JsonObject? = null): ToolEventIdentity =
            ToolEventIdentity(
                key = this["output_index"]?.jsonPrimitiveOrNull()?.contentOrNull?.let { "responses-output-index:$it" }
                    ?: this["item_id"]?.jsonPrimitiveOrNull()?.contentOrNull?.let { "responses-item:$it" }
                    ?: item?.get("id")?.jsonPrimitiveOrNull()?.contentOrNull?.let { "responses-item:$it" }
                    ?: this["call_id"]?.jsonPrimitiveOrNull()?.contentOrNull?.let { "responses-call:$it" },
                providerId = this["item_id"]?.jsonPrimitiveOrNull()?.contentOrNull
                    ?: item?.get("id")?.jsonPrimitiveOrNull()?.contentOrNull
                    ?: this["call_id"]?.jsonPrimitiveOrNull()?.contentOrNull,
            )
    }
}

private enum class TurnBranch {
    Text,
    Tool,
}

private data class ToolEventIdentity(
    val key: String?,
    val providerId: String?,
)

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

private fun String.isSafeProviderErrorCode(): Boolean =
    length in 1..64 && all { character ->
        character.isLetterOrDigit() || character == '_' || character == '-' || character == '.' || character == ':'
    }

private fun StructuredModelCapability.toLogOperation(): String =
    when (this) {
        StructuredModelCapability.UnderstandMessage -> "understanding"
        StructuredModelCapability.ConversationDecision -> "conversation_decision"
        StructuredModelCapability.ConversationAnswer -> "conversation_answer"
        StructuredModelCapability.ConversationTurn -> "conversation_turn"
        StructuredModelCapability.PlanningResearch -> "planning_research"
        StructuredModelCapability.CreatePlans -> "plan_compose"
        StructuredModelCapability.ExplainPlans -> "plan_explain"
    }

private const val AI_COMPONENT = "ai"
private const val MAX_STREAM_TEXT_CHARS = 24_000
private const val MAX_TOOL_ARGUMENT_CHARS = 16_000
private const val MAX_PROVIDER_ERROR_BODY_CHARS = 4_096

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

private fun OpenAiCompatibleMode.turnStreamingBody(
    model: String,
    request: TurnModelRequest,
    json: Json,
    enableThinking: Boolean?,
): Any =
    request.userPayloadText(json).let { userPayload ->
        when (this) {
            OpenAiCompatibleMode.Responses -> OpenAiResponsesTurnRequest(
                model = model,
                instructions = request.systemPrompt,
                input = userPayload,
                stream = true,
                tools = request.tools.map { it.toResponsesTool() },
                toolChoice = "auto",
                parallelToolCalls = false,
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
                tools = request.tools.map { it.toChatTool() },
                toolChoice = "auto",
                parallelToolCalls = false,
                enableThinking = enableThinking,
            )
        }
    }

private fun StructuredModelRequest.userPayloadText(json: Json): String =
    json.encodeToString(JsonObject.serializer(), userPayload)

private fun TextModelRequest.userPayloadText(json: Json): String =
    json.encodeToString(JsonObject.serializer(), userPayload)

private fun TurnModelRequest.userPayloadText(json: Json): String =
    json.encodeToString(JsonObject.serializer(), userPayload)

private fun TurnModelTool.toResponsesTool(): OpenAiResponsesTool =
    OpenAiResponsesTool(
        type = "function",
        name = name,
        description = description,
        parameters = parameters,
    )

private fun TurnModelTool.toChatTool(): OpenAiChatTool =
    OpenAiChatTool(
        type = "function",
        function = OpenAiChatFunctionTool(
            name = name,
            description = description,
            parameters = parameters,
        ),
    )

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
private data class OpenAiResponsesTurnRequest(
    @SerialName("model")
    val model: String,
    @SerialName("instructions")
    val instructions: String,
    @SerialName("input")
    val input: String,
    @SerialName("stream")
    val stream: Boolean,
    @SerialName("tools")
    val tools: List<OpenAiResponsesTool>,
    @SerialName("tool_choice")
    val toolChoice: String,
    @SerialName("parallel_tool_calls")
    val parallelToolCalls: Boolean,
)

@Serializable
private data class OpenAiResponsesTool(
    @SerialName("type")
    val type: String,
    @SerialName("name")
    val name: String,
    @SerialName("description")
    val description: String,
    @SerialName("parameters")
    val parameters: JsonObject,
    @SerialName("strict")
    val strict: Boolean = false,
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
    @SerialName("tools")
    val tools: List<OpenAiChatTool>? = null,
    @SerialName("tool_choice")
    val toolChoice: String? = null,
    @SerialName("parallel_tool_calls")
    val parallelToolCalls: Boolean? = null,
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
private data class OpenAiChatTool(
    @SerialName("type")
    val type: String,
    @SerialName("function")
    val function: OpenAiChatFunctionTool,
)

@Serializable
private data class OpenAiChatFunctionTool(
    @SerialName("name")
    val name: String,
    @SerialName("description")
    val description: String,
    @SerialName("parameters")
    val parameters: JsonObject,
    @SerialName("strict")
    val strict: Boolean = false,
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
