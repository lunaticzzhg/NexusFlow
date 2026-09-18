package com.nexusflow.ai.answer

import com.nexusflow.contracts.backendai.common.ModelContextBlockPayload
import com.nexusflow.contracts.backendai.answer.AnswerEvidenceAuthorityPayload
import com.nexusflow.contracts.backendai.answer.AnswerEvidenceFactPayload
import com.nexusflow.contracts.backendai.conversation.ConversationMessagePayload
import com.nexusflow.contracts.backendai.conversation.InformationNeedMode
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class ConversationAnswerModelPayload(
    @SerialName("request")
    val request: ConversationAnswerModelRequest,
    @SerialName("coreContext")
    val coreContext: ConversationAnswerCoreContextPayload,
    @SerialName("optionalContext")
    val optionalContext: List<ModelContextBlockPayload> = emptyList(),
)

@Serializable
internal data class ConversationAnswerModelRequest(
    @SerialName("question")
    val question: String,
    @SerialName("recentMessages")
    val recentMessages: List<ConversationMessagePayload>,
    @SerialName("referenceTime")
    val referenceTime: Instant,
    @SerialName("timeZoneId")
    val timeZoneId: String,
    @SerialName("taskRevision")
    val taskRevision: Long?,
)

@Serializable
internal data class ConversationAnswerCoreContextPayload(
    @SerialName("informationNeeds")
    val informationNeeds: List<AnswerInformationNeedModelPayload>,
    @SerialName("evidence")
    val evidence: List<AnswerEvidenceModelPayload>,
)

@Serializable
internal data class AnswerInformationNeedModelPayload(
    @SerialName("id")
    val id: String,
    @SerialName("question")
    val question: String,
    @SerialName("mode")
    val mode: InformationNeedMode,
    @SerialName("evidenceSourceIds")
    val evidenceSourceIds: List<String>,
    @SerialName("issues")
    val issues: List<ResearchIssueModelPayload>,
)

@Serializable
internal data class ResearchIssueModelPayload(
    @SerialName("type")
    val type: String,
    @SerialName("missingInputs")
    val missingInputs: List<String>,
)

@Serializable
internal data class AnswerEvidenceModelPayload(
    @SerialName("sourceId")
    val sourceId: String,
    @SerialName("sourceUrl")
    val sourceUrl: String? = null,
    @SerialName("sourceKey")
    val sourceKey: String,
    @SerialName("sourceUpdatedAt")
    val sourceUpdatedAt: Instant? = null,
    @SerialName("authority")
    val authority: AnswerEvidenceAuthorityPayload,
    @SerialName("facts")
    val facts: List<AnswerEvidenceFactPayload>,
)

@Serializable
internal data class ConversationAnswerPayload(
    @SerialName("answer")
    val answer: String,
    @SerialName("coverage")
    val coverage: List<AnswerNeedCoverageModelPayload>,
)

@Serializable
internal data class AnswerNeedCoverageModelPayload(
    @SerialName("needId")
    val needId: String,
    @SerialName("status")
    val status: String,
    @SerialName("usedEvidenceSourceIds")
    val usedEvidenceSourceIds: List<String> = emptyList(),
)
