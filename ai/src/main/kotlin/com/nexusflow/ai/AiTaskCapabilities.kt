package com.nexusflow.ai

import com.nexusflow.ai.planner.StructuredPlanComposer
import com.nexusflow.ai.planner.StructuredPlanExplainer
import com.nexusflow.ai.provider.StructuredModelProvider
import com.nexusflow.ai.provider.deepseek.DeepSeekStructuredModelProvider
import com.nexusflow.ai.provider.openai.OpenAiStructuredModelProvider
import com.nexusflow.ai.provider.qwen.QwenStructuredModelProvider
import com.nexusflow.ai.understanding.StructuredUserMessageUnderstanding
import com.nexusflow.contracts.backendai.planning.PlanComposer
import com.nexusflow.contracts.backendai.planning.PlanExplainer
import com.nexusflow.contracts.backendai.understanding.UserMessageUnderstanding
import com.nexusflow.observability.StructuredLogger
import io.ktor.client.HttpClient

data class AiTaskCapabilityConfig(
    val provider: AiTaskCapabilityProvider,
    val apiKey: String,
    val model: String,
    val baseUrl: String?,
    val enableThinking: Boolean = false,
)

enum class AiTaskCapabilityProvider {
    OpenAi,
    Qwen,
    DeepSeek,
}

data class AiTaskCapabilities(
    val structuredProvider: StructuredModelProvider,
    val understanding: UserMessageUnderstanding,
    val planComposer: PlanComposer,
    val planExplainer: PlanExplainer,
)

fun createAiTaskCapabilities(
    client: HttpClient,
    config: AiTaskCapabilityConfig,
    logger: StructuredLogger,
): AiTaskCapabilities {
    val structuredProvider = config.createProvider(client, logger)
    return AiTaskCapabilities(
        structuredProvider = structuredProvider,
        understanding = StructuredUserMessageUnderstanding(structuredProvider, logger = logger),
        planComposer = StructuredPlanComposer(structuredProvider, logger = logger),
        planExplainer = StructuredPlanExplainer(structuredProvider, logger = logger),
    )
}

private fun AiTaskCapabilityConfig.createProvider(
    client: HttpClient,
    logger: StructuredLogger,
): StructuredModelProvider =
    when (provider) {
        AiTaskCapabilityProvider.OpenAi -> OpenAiStructuredModelProvider(
            client = client,
            apiKey = apiKey,
            model = model,
            baseUrl = baseUrl ?: "https://api.openai.com/v1",
            logger = logger,
        )
        AiTaskCapabilityProvider.Qwen -> QwenStructuredModelProvider(
            client = client,
            apiKey = apiKey,
            model = model,
            baseUrl = requireNotNull(baseUrl) { "Qwen baseUrl must be configured" },
            enableThinking = enableThinking,
            logger = logger,
        )
        AiTaskCapabilityProvider.DeepSeek -> DeepSeekStructuredModelProvider(
            client = client,
            apiKey = apiKey,
            model = model,
            baseUrl = requireNotNull(baseUrl) { "DeepSeek baseUrl must be configured" },
            logger = logger,
        )
    }
