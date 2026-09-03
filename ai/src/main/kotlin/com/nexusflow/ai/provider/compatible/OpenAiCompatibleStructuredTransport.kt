package com.nexusflow.ai.provider.compatible

import com.nexusflow.ai.provider.InvalidStructuredOutputException
import com.nexusflow.ai.provider.ProviderRateLimitedException
import com.nexusflow.ai.provider.ProviderRefusedException
import com.nexusflow.ai.provider.ProviderTimeoutException
import com.nexusflow.ai.provider.ProviderUnauthorizedException
import com.nexusflow.ai.provider.ProviderUnavailableException
import com.nexusflow.ai.provider.StructuredModelFinishCategory
import com.nexusflow.ai.provider.StructuredModelRequest
import com.nexusflow.ai.provider.StructuredModelResult
import com.nexusflow.ai.provider.StructuredModelResultMetadata
import com.nexusflow.ai.provider.StructuredModelUsage
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException

internal class OpenAiCompatibleStructuredTransport(
    private val client: HttpClient,
    private val provider: String,
    private val apiKey: String,
    private val model: String,
    baseUrl: String,
    private val mode: OpenAiCompatibleMode,
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
        val response = try {
            client.post(endpointUrl) {
                bearerAuth(apiKey)
                contentType(ContentType.Application.Json)
                setBody(mode.body(model, request, json))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpRequestTimeoutException) {
            throw ProviderTimeoutException(error)
        } catch (error: IOException) {
            throw ProviderUnavailableException(error)
        }

        when {
            response.status == HttpStatusCode.RequestTimeout -> throw ProviderTimeoutException()
            response.status == HttpStatusCode.Unauthorized ||
                response.status == HttpStatusCode.Forbidden -> throw ProviderUnauthorizedException()
            response.status.value == 429 -> throw ProviderRateLimitedException()
            response.status.value >= 500 -> throw ProviderUnavailableException()
            response.status.value !in 200..299 -> throw ProviderUnavailableException()
        }

        val body = response.bodyAsText()
        return when (mode) {
            OpenAiCompatibleMode.Responses -> decodeResponses(body, request)
            OpenAiCompatibleMode.ChatJsonSchema,
            OpenAiCompatibleMode.ChatJsonObject,
            -> decodeChatCompletion(body, request)
        }
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
}

internal enum class OpenAiCompatibleMode(val path: String) {
    Responses("responses"),
    ChatJsonSchema("chat/completions"),
    ChatJsonObject("chat/completions"),
}

private fun OpenAiCompatibleMode.body(
    model: String,
    request: StructuredModelRequest,
    json: Json,
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
            )
        }
    }

private fun StructuredModelRequest.userPayloadText(json: Json): String =
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
    val responseFormat: OpenAiChatResponseFormat,
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
