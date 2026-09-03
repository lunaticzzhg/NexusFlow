package com.nexusflow.contracts.api

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PlanResponse(
    @SerialName("id")
    val id: String,
    @SerialName("taskId")
    val taskId: String,
    @SerialName("revision")
    val revision: Long,
    @SerialName("direction")
    val direction: PlanDirection,
    @SerialName("title")
    val title: String,
    @SerialName("summary")
    val summary: String,
    @SerialName("timeline")
    val timeline: List<PlanTimelineItemResponse>,
    @SerialName("estimatedCost")
    val estimatedCost: PlanEstimatedCostResponse? = null,
    @SerialName("commuteMinutes")
    val commuteMinutes: Int? = null,
    @SerialName("requirementEvaluations")
    val requirementEvaluations: List<RequirementEvaluationResponse>,
    @SerialName("tradeoffs")
    val tradeoffs: List<String>,
    @SerialName("reasons")
    val reasons: List<String>,
    @SerialName("sourceRefs")
    val sourceRefs: List<PlanSourceRefResponse>,
    @SerialName("opportunityRefs")
    val opportunityRefs: List<String>,
    @SerialName("validUntil")
    val validUntil: Instant? = null,
    @SerialName("createdAt")
    val createdAt: Instant,
)

@Serializable
data class RequirementEvaluationResponse(
    @SerialName("requirementId")
    val requirementId: String,
    @SerialName("result")
    val result: RequirementEvaluationResult,
    @SerialName("explanation")
    val explanation: String? = null,
)

@Serializable
enum class RequirementEvaluationResult {
    @SerialName("satisfied")
    Satisfied,

    @SerialName("not_applicable")
    NotApplicable,
}

@Serializable
enum class PlanDirection {
    @SerialName("best_match")
    BestMatch,

    @SerialName("more_relaxed")
    MoreRelaxed,

    @SerialName("new_experience")
    NewExperience,
}

@Serializable
data class PlanTimelineItemResponse(
    @SerialName("title")
    val title: String,
    @SerialName("startAt")
    val startAt: Instant? = null,
    @SerialName("endAt")
    val endAt: Instant? = null,
    @SerialName("location")
    val location: String? = null,
)

@Serializable
data class PlanEstimatedCostResponse(
    @SerialName("wholeUnits")
    val wholeUnits: Long,
    @SerialName("currencyCode")
    val currencyCode: String? = null,
)

@Serializable
data class PlanSourceRefResponse(
    @SerialName("label")
    val label: String,
    @SerialName("uri")
    val uri: String? = null,
    @SerialName("sourceUpdatedAt")
    val sourceUpdatedAt: Instant? = null,
)
