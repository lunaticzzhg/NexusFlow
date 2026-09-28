package com.nexusflow.ai

import com.nexusflow.ai.answer.StructuredConversationAnswerer
import com.nexusflow.ai.answer.StreamingConversationAnswerer
import com.nexusflow.ai.conversation.StructuredConversationDecision
import com.nexusflow.ai.conversation.StructuredConversationTurn
import com.nexusflow.ai.planner.StructuredPlanComposer
import com.nexusflow.ai.planner.StructuredPlanExplainer
import com.nexusflow.ai.planner.StructuredPlanningResearch
import com.nexusflow.ai.provider.StructuredModelProvider
import com.nexusflow.ai.provider.StreamingTextModelProvider
import com.nexusflow.ai.provider.StreamingTurnModelProvider
import com.nexusflow.ai.provider.deepseek.DeepSeekStructuredModelProvider
import com.nexusflow.ai.provider.openai.OpenAiStructuredModelProvider
import com.nexusflow.ai.provider.qwen.QwenStructuredModelProvider
import com.nexusflow.ai.understanding.StructuredUserMessageUnderstanding
import com.nexusflow.contracts.backendai.answer.ConversationAnsweringCapability
import com.nexusflow.contracts.backendai.conversation.ConversationTurnCapability
import com.nexusflow.contracts.backendai.conversation.ConversationDecisionCapability
import com.nexusflow.contracts.backendai.planning.PlanComposer
import com.nexusflow.contracts.backendai.planning.PlanExplainer
import com.nexusflow.contracts.backendai.planning.PlanningResearchCapability
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
    val conversationDecision: ConversationDecisionCapability,
    val conversationTurn: ConversationTurnCapability?,
    val conversationAnswering: ConversationAnsweringCapability,
    val planningResearch: PlanningResearchCapability,
    val planComposer: PlanComposer,
    val planExplainer: PlanExplainer,
)

fun createAiTaskCapabilities(
    client: HttpClient,
    config: AiTaskCapabilityConfig,
    logger: StructuredLogger,
): AiTaskCapabilities {
    val structuredProvider = config.createProvider(client, logger)
    val conversationAnswering = if (structuredProvider is StreamingTextModelProvider) {
        StreamingConversationAnswerer(structuredProvider)
    } else {
        StructuredConversationAnswerer(structuredProvider, logger = logger)
    }
    val conversationTurn = if (structuredProvider is StreamingTurnModelProvider) {
        StructuredConversationTurn(structuredProvider)
    } else {
        null
    }
    return AiTaskCapabilities(
        structuredProvider = structuredProvider,
        understanding = StructuredUserMessageUnderstanding(structuredProvider, logger = logger),
        conversationDecision = StructuredConversationDecision(structuredProvider, logger = logger),
        conversationTurn = conversationTurn,
        conversationAnswering = conversationAnswering,
        planningResearch = StructuredPlanningResearch(structuredProvider, logger = logger),
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
