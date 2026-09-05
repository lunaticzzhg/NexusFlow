package com.nexusflow.ai.provider.openai

import com.nexusflow.ai.provider.StructuredModelProvider
import com.nexusflow.ai.provider.StructuredModelRequest
import com.nexusflow.ai.provider.StructuredModelResult
import com.nexusflow.ai.provider.compatible.OpenAiCompatibleMode
import com.nexusflow.ai.provider.compatible.OpenAiCompatibleStructuredTransport
import com.nexusflow.observability.StructuredLogger
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json

class OpenAiStructuredModelProvider(
    client: HttpClient,
    apiKey: String,
    model: String,
    baseUrl: String = "https://api.openai.com/v1",
    logger: StructuredLogger? = null,
    json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) : StructuredModelProvider {
    private val transport = OpenAiCompatibleStructuredTransport(
        client = client,
        provider = "openai",
        apiKey = apiKey,
        model = model,
        baseUrl = baseUrl,
        mode = OpenAiCompatibleMode.Responses,
        logger = logger,
        json = json,
    )

    override suspend fun generate(request: StructuredModelRequest): StructuredModelResult =
        transport.generate(request)
}
