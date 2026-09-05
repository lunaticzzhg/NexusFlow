package com.nexusflow.ai.provider.deepseek

import com.nexusflow.ai.provider.StructuredModelProvider
import com.nexusflow.ai.provider.StructuredModelRequest
import com.nexusflow.ai.provider.StructuredModelResult
import com.nexusflow.ai.provider.compatible.OpenAiCompatibleMode
import com.nexusflow.ai.provider.compatible.OpenAiCompatibleStructuredTransport
import com.nexusflow.observability.StructuredLogger
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json

class DeepSeekStructuredModelProvider(
    client: HttpClient,
    apiKey: String,
    model: String,
    baseUrl: String,
    logger: StructuredLogger? = null,
    json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) : StructuredModelProvider {
    private val transport = OpenAiCompatibleStructuredTransport(
        client = client,
        provider = "deepseek",
        apiKey = apiKey,
        model = model,
        baseUrl = baseUrl,
        mode = OpenAiCompatibleMode.ChatJsonObject,
        logger = logger,
        json = json,
    )

    override suspend fun generate(request: StructuredModelRequest): StructuredModelResult =
        transport.generate(request)
}
