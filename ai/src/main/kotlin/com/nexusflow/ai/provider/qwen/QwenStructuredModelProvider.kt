package com.nexusflow.ai.provider.qwen

import com.nexusflow.ai.provider.StructuredModelProvider
import com.nexusflow.ai.provider.StructuredModelRequest
import com.nexusflow.ai.provider.StructuredModelResult
import com.nexusflow.ai.provider.compatible.OpenAiCompatibleMode
import com.nexusflow.ai.provider.compatible.OpenAiCompatibleStructuredTransport
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json

class QwenStructuredModelProvider(
    client: HttpClient,
    apiKey: String,
    model: String,
    baseUrl: String,
    json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) : StructuredModelProvider {
    private val transport = OpenAiCompatibleStructuredTransport(
        client = client,
        provider = "qwen",
        apiKey = apiKey,
        model = model,
        baseUrl = baseUrl,
        mode = OpenAiCompatibleMode.ChatJsonSchema,
        json = json,
    )

    override suspend fun generate(request: StructuredModelRequest): StructuredModelResult =
        transport.generate(request)
}
