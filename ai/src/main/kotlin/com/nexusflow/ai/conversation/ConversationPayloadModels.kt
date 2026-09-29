package com.nexusflow.ai.conversation

import com.nexusflow.contracts.backendai.common.ModelContextBlockPayload
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class ConversationDecisionModelPayload(
    @SerialName("request")
    val request: ConversationDecisionModelRequest,
    @SerialName("coreContext")
    val coreContext: ConversationDecisionCoreContextPayload,
    @SerialName("optionalContext")
    val optionalContext: List<ModelContextBlockPayload> = emptyList(),
)

@Serializable
internal data class ConversationDecisionModelRequest(
    @SerialName("currentMessage")
    val currentMessage: String,
    @SerialName("recentMessages")
    val recentMessages: List<ConversationMessageModelPayload>,
    @SerialName("referenceTime")
    val referenceTime: Instant,
    @SerialName("timeZoneId")
    val timeZoneId: String,
    @SerialName("taskRevision")
    val taskRevision: Long?,
)

@Serializable
internal data class ConversationMessageModelPayload(
    @SerialName("role")
    val role: String,
    @SerialName("content")
    val content: String,
)

@Serializable
internal data class ConversationDecisionCoreContextPayload(
    @SerialName("availableReadTools")
    val availableReadTools: List<ReadOnlyToolDefinitionModelPayload>,
    @SerialName("maxReadToolCalls")
    val maxReadToolCalls: Int,
)

@Serializable
internal data class ReadOnlyToolDefinitionModelPayload(
    @SerialName("toolKey")
    val toolKey: String,
    @SerialName("description")
    val description: String,
    @SerialName("argumentHint")
    val argumentHint: String,
)

@Serializable
internal data class ConversationDecisionPayload(
    @SerialName("informationNeeds")
    val informationNeeds: List<InformationNeedPayload>,
)

@Serializable
internal data class InformationNeedPayload(
    @SerialName("question")
    val question: String,
    @SerialName("mode")
    val mode: String,
    @SerialName("toolCalls")
    val toolCalls: List<ReadOnlyToolCallPayload> = emptyList(),
)

@Serializable
internal data class ReadOnlyToolCallPayload(
    @SerialName("toolKey")
    val toolKey: String,
    @SerialName("arguments")
    val arguments: JsonObject,
)
