package com.nexusflow.backend.feature.conversation.domain

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class ResponseRunResultType {
    ConversationAnswer,
    PlanningUnderstanding,
    PlanningResult,
}

data class ResponseRunResult(
    val runId: ResponseRunId,
    val attempt: Int,
    val resultType: ResponseRunResultType,
    val payload: ResponseRunResultPayload,
    val createdAt: Instant,
    val consumedAt: Instant?,
)

@Serializable
sealed interface ResponseRunResultPayload {
    @Serializable
    @SerialName("conversation_answer")
    data class ConversationAnswer(
        val conversationId: String,
        val userMessageId: String,
        val aiRequestId: String,
        val assistantMessageId: String,
        val text: String,
        val terminalKind: String,
    ) : ResponseRunResultPayload

    @Serializable
    @SerialName("planning_understanding")
    data class PlanningUnderstanding(
        val conversationId: String,
        val userMessageId: String,
        val aiRequestId: String,
        val taskId: String?,
        val taskCreationRequestId: String,
        val intent: String,
        val expectedTaskRevision: Long?,
        val intentPatch: String?,
        val requirements: List<RequirementWritePayload>,
        val removedRequirementKinds: List<String> = emptyList(),
        val selectedTaskContextKeys: List<String> = emptyList(),
        val clarificationText: String?,
        val planningRequested: Boolean,
    ) : ResponseRunResultPayload

    @Serializable
    @SerialName("planning_result")
    data class PlanningResult(
        val conversationId: String,
        val userMessageId: String,
        val aiRequestId: String,
        val taskId: String,
        val expectedTaskRevision: Long,
        val outcome: String,
        val opportunities: List<OpportunityPayload> = emptyList(),
        val plans: List<PlanPayload> = emptyList(),
    ) : ResponseRunResultPayload
}

@Serializable
data class RequirementWritePayload(
    val id: String,
    val kind: String,
    val value: RequirementValuePayload,
    val strength: String,
)

@Serializable
sealed interface RequirementValuePayload {
    @Serializable
    @SerialName("time_window")
    data class TimeWindow(
        val startAt: String?,
        val endAt: String?,
        val timeZoneId: String,
        val originalText: String,
    ) : RequirementValuePayload

    @Serializable
    @SerialName("budget_limit")
    data class BudgetLimit(
        val wholeUnits: Long,
        val currencyCode: String?,
    ) : RequirementValuePayload

    @Serializable
    @SerialName("commute_limit")
    data class CommuteLimit(val maxMinutes: Int) : RequirementValuePayload

    @Serializable
    @SerialName("commute_preference")
    data class CommutePreference(val value: String) : RequirementValuePayload

    @Serializable
    @SerialName("location")
    data class Location(val text: String) : RequirementValuePayload

    @Serializable
    @SerialName("activity_domain")
    data class ActivityDomain(val value: String) : RequirementValuePayload

    @Serializable
    @SerialName("activity_mode")
    data class ActivityMode(val value: String) : RequirementValuePayload

    @Serializable
    @SerialName("topic")
    data class Topic(val text: String) : RequirementValuePayload

    @Serializable
    @SerialName("experience_preference")
    data class ExperiencePreference(val text: String) : RequirementValuePayload
}

@Serializable
data class OpportunityPayload(
    val id: String,
    val provider: String,
    val externalKey: String,
    val kind: String,
    val title: String,
    val facts: OpportunityFactsPayload,
    val sources: List<SourceRefPayload>,
    val observedAt: String,
    val validUntil: String?,
)

@Serializable
data class OpportunityFactsPayload(
    val summary: String?,
    val startTime: String?,
    val endTime: String?,
    val location: LocationFactPayload?,
    val activityMode: String?,
    val price: MoneyFactPayload?,
    val commute: DurationFactPayload?,
    val availability: String?,
    val attributes: Map<String, FactValuePayload> = emptyMap(),
)

@Serializable
data class LocationFactPayload(
    val displayName: String,
    val normalizedName: String,
)

@Serializable
data class MoneyFactPayload(
    val wholeUnits: Long,
    val currencyCode: String?,
)

@Serializable
data class DurationFactPayload(val minutes: Int)

@Serializable
sealed interface FactValuePayload {
    @Serializable
    @SerialName("text")
    data class Text(val value: String) : FactValuePayload

    @Serializable
    @SerialName("number")
    data class Number(val value: Long) : FactValuePayload

    @Serializable
    @SerialName("flag")
    data class Flag(val value: Boolean) : FactValuePayload
}

@Serializable
data class SourceRefPayload(
    val label: String,
    val uri: String?,
    val sourceUpdatedAt: String?,
    val sourceId: String,
    val authority: String,
    val factKeys: List<String> = emptyList(),
)

@Serializable
data class PlanPayload(
    val id: String,
    val taskId: String,
    val revision: Long,
    val direction: String,
    val title: String,
    val summary: String,
    val timeline: List<PlanTimelineItemPayload>,
    val estimatedCost: PlanEstimatedCostPayload?,
    val commuteMinutes: Int?,
    val requirementEvaluations: List<RequirementEvaluationPayload>,
    val tradeoffs: List<String>,
    val reasons: List<String>,
    val sourceRefs: List<PlanSourceRefPayload>,
    val opportunityRefs: List<String>,
    val validUntil: String?,
    val createdAt: String,
)

@Serializable
data class PlanTimelineItemPayload(
    val title: String,
    val startAt: String?,
    val endAt: String?,
    val location: String?,
)

@Serializable
data class PlanEstimatedCostPayload(
    val wholeUnits: Long,
    val currencyCode: String?,
)

@Serializable
data class RequirementEvaluationPayload(
    val requirementId: String,
    val result: String,
    val explanation: String?,
)

@Serializable
data class PlanSourceRefPayload(
    val label: String,
    val uri: String?,
    val sourceUpdatedAt: String?,
)
