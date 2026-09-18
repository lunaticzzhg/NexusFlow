package com.nexusflow.contracts.backendai.conversation

import com.nexusflow.contracts.backendai.common.ModelContextBlockPayload
import com.nexusflow.contracts.backendai.common.StructuredModelRequestDiagnostics
import com.nexusflow.contracts.backendai.common.StructuredModelUsage
import kotlinx.datetime.Instant
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

fun interface ConversationDecisionCapability {
    suspend fun decide(request: ConversationDecisionRequest): ConversationDecisionResult
}

@Serializable
data class ConversationDecisionRequest(
    @SerialName("aiRequestId")
    val aiRequestId: String,
    @SerialName("conversationId")
    val conversationId: String?,
    @SerialName("taskId")
    val taskId: String?,
    @SerialName("taskRevision")
    val taskRevision: Long?,
    @SerialName("currentMessage")
    val currentMessage: String,
    @SerialName("recentMessages")
    val recentMessages: List<ConversationMessagePayload> = emptyList(),
    @SerialName("referenceTime")
    val referenceTime: Instant,
    @SerialName("timeZoneId")
    val timeZoneId: String,
    @SerialName("optionalContext")
    val optionalContext: List<ModelContextBlockPayload> = emptyList(),
    @SerialName("availableReadTools")
    val availableReadTools: List<ReadOnlyToolDefinitionPayload> = emptyList(),
    @SerialName("maxReadToolCalls")
    val maxReadToolCalls: Int = 4,
    @SerialName("diagnostics")
    val diagnostics: StructuredModelRequestDiagnostics = StructuredModelRequestDiagnostics(),
)

@Serializable
data class ConversationMessagePayload(
    @SerialName("role")
    val role: ConversationMessageRole,
    @SerialName("content")
    val content: String,
)

@Serializable
enum class ConversationMessageRole {
    @SerialName("user")
    User,

    @SerialName("assistant")
    Assistant,
}

@Serializable
data class ReadOnlyToolDefinitionPayload(
    @SerialName("toolKey")
    val toolKey: String,
    @SerialName("description")
    val description: String,
    @SerialName("argumentHint")
    val argumentHint: String,
)

@Serializable
data class ConversationDecisionResult(
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("informationNeeds")
    val informationNeeds: List<InformationNeedProposal>,
    @SerialName("metadata")
    val metadata: ConversationDecisionMetadata = ConversationDecisionMetadata(),
)

@Serializable
data class InformationNeedProposal(
    @SerialName("id")
    val id: String,
    @SerialName("question")
    val question: String,
    @SerialName("mode")
    val mode: InformationNeedMode,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("toolCalls")
    val toolCalls: List<ReadOnlyToolCallProposal> = emptyList(),
    @SerialName("requestedCapabilityHint")
    val requestedCapabilityHint: String? = null,
)

@Serializable
enum class InformationNeedMode {
    @SerialName("model_only")
    MODEL_ONLY,

    @SerialName("tool_enhanced")
    TOOL_ENHANCED,

    @SerialName("tool_required")
    TOOL_REQUIRED,
}

@Serializable
data class ReadOnlyToolCallProposal(
    @SerialName("toolKey")
    val toolKey: String,
    @SerialName("arguments")
    val arguments: JsonObject,
)

@Serializable
data class ConversationDecisionMetadata(
    @SerialName("provider")
    val provider: String? = null,
    @SerialName("model")
    val model: String? = null,
    @SerialName("promptVersion")
    val promptVersion: String? = null,
    @SerialName("providerRequestId")
    val providerRequestId: String? = null,
    @SerialName("attemptCount")
    val attemptCount: Int? = null,
    @SerialName("usage")
    val usage: StructuredModelUsage? = null,
    @SerialName("diagnostics")
    val diagnostics: StructuredModelRequestDiagnostics = StructuredModelRequestDiagnostics(),
)
