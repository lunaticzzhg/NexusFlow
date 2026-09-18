package com.nexusflow.ai.provider.qwen

import com.nexusflow.ai.provider.StructuredModelProvider
import com.nexusflow.ai.provider.StructuredModelRequest
import com.nexusflow.ai.provider.StructuredModelResult
import com.nexusflow.ai.provider.StreamingTextModelProvider
import com.nexusflow.ai.provider.TextModelRequest
import com.nexusflow.ai.provider.TextModelResult
import com.nexusflow.ai.provider.compatible.OpenAiCompatibleMode
import com.nexusflow.ai.provider.compatible.OpenAiCompatibleStructuredTransport
import com.nexusflow.observability.StructuredLogger
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json

class QwenStructuredModelProvider(
    client: HttpClient,
    apiKey: String,
    model: String,
    baseUrl: String,
    enableThinking: Boolean = false,
    logger: StructuredLogger? = null,
    json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) : StructuredModelProvider, StreamingTextModelProvider {
    private val transport = OpenAiCompatibleStructuredTransport(
        client = client,
        provider = "qwen",
        apiKey = apiKey,
        model = model,
        baseUrl = baseUrl,
        mode = OpenAiCompatibleMode.ChatJsonSchema,
        enableThinking = enableThinking,
        logger = logger,
        json = json,
    )

    override suspend fun generate(request: StructuredModelRequest): StructuredModelResult =
        transport.generate(request)

    override suspend fun stream(
        request: TextModelRequest,
        onDelta: suspend (String) -> Unit,
    ): TextModelResult =
        transport.stream(request, onDelta)
}
