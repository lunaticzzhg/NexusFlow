package com.nexusflow.contracts.backendai.conversation

import com.nexusflow.contracts.backendai.common.ModelContextBlockPayload
import com.nexusflow.contracts.backendai.common.StructuredModelRequestDiagnostics
import com.nexusflow.contracts.backendai.common.StructuredModelUsage
import com.nexusflow.contracts.backendai.understanding.ActivePlanningContextPayload
import com.nexusflow.contracts.backendai.understanding.ClarificationProposal
import com.nexusflow.contracts.backendai.understanding.ConstraintDeltaProposal
import com.nexusflow.contracts.backendai.understanding.ContextSelectionProposal
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

fun interface ConversationTurnCapability {
    suspend fun execute(
        request: ConversationTurnRequest,
        onAnswerDelta: suspend (String) -> Unit,
    ): ConversationTurnResult
}

@Serializable
data class ConversationTurnRequest(
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
    @SerialName("activePlanning")
    val activePlanning: ActivePlanningContextPayload? = null,
    @SerialName("diagnostics")
    val diagnostics: StructuredModelRequestDiagnostics = StructuredModelRequestDiagnostics(),
)

@Serializable
sealed interface ConversationTurnResult {
    val metadata: ConversationTurnMetadata

    @Serializable
    @SerialName("answer")
    data class Answer(
        @SerialName("answer")
        val answer: String,
        @SerialName("metadata")
        override val metadata: ConversationTurnMetadata = ConversationTurnMetadata(),
    ) : ConversationTurnResult

    @Serializable
    @SerialName("research")
    data class Research(
        @SerialName("informationNeeds")
        val informationNeeds: List<InformationNeedProposal>,
        @SerialName("metadata")
        override val metadata: ConversationTurnMetadata = ConversationTurnMetadata(),
    ) : ConversationTurnResult

    @Serializable
    @SerialName("planning")
    data class Planning(
        @SerialName("planningGoalPatch")
        val planningGoalPatch: String? = null,
        @SerialName("constraintDeltas")
        val constraintDeltas: List<ConstraintDeltaProposal> = emptyList(),
        @SerialName("clarification")
        val clarification: ClarificationProposal,
        @SerialName("contextSelection")
        val contextSelection: ContextSelectionProposal = ContextSelectionProposal(),
        @SerialName("metadata")
        override val metadata: ConversationTurnMetadata = ConversationTurnMetadata(),
    ) : ConversationTurnResult
}

@Serializable
data class ConversationTurnMetadata(
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
