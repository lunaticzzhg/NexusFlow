package com.nexusflow.backend.feature.task.domain

import java.time.Instant

data class Plan(
    val id: PlanId,
    val taskId: TaskId,
    val revision: Long,
    val direction: PlanDirection,
    val title: String,
    val summary: String,
    val timeline: List<PlanTimelineItem>,
    val estimatedCost: PlanEstimatedCost?,
    val commuteMinutes: Int?,
    val requirementEvaluations: List<RequirementEvaluation>,
    val tradeoffs: List<String>,
    val reasons: List<String>,
    val sourceRefs: List<PlanSourceRef>,
    val opportunityRefs: List<OpportunityId>,
    val validUntil: Instant?,
    val createdAt: Instant,
)

enum class PlanDirection {
    BestMatch,
    MoreRelaxed,
    NewExperience,
}

data class PlanTimelineItem(
    val title: String,
    val startAt: Instant?,
    val endAt: Instant?,
    val location: String?,
)

data class PlanEstimatedCost(
    val wholeUnits: Long,
    val currencyCode: String?,
)

data class RequirementEvaluation(
    val requirementId: RequirementId,
    val result: RequirementEvaluationResult,
    val explanation: String? = null,
)

enum class RequirementEvaluationResult {
    Satisfied,
    NotApplicable,
}

data class PlanSourceRef(
    val label: String,
    val uri: String?,
    val sourceUpdatedAt: Instant?,
)
