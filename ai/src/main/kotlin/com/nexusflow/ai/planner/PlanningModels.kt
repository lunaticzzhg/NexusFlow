package com.nexusflow.ai.planner

import com.nexusflow.ai.context.ModelContextBlockPayload
import com.nexusflow.ai.provider.StructuredModelRequestDiagnostics
import com.nexusflow.ai.provider.StructuredModelUsage
import kotlinx.datetime.Instant

data class PlanningContext(
    val planningRequestId: String,
    val taskId: String,
    val taskRevision: Long,
    val intent: String,
    val requirements: List<PlanningRequirement>,
    val opportunities: List<CandidateOpportunity>,
    val referenceTime: Instant,
    val timeZoneId: String,
    val optionalContext: List<ModelContextBlockPayload> = emptyList(),
    val diagnostics: StructuredModelRequestDiagnostics = StructuredModelRequestDiagnostics(),
)

data class PlanningRequirement(
    val id: String,
    val kind: String,
    val valueSummary: String,
    val strength: PlanningRequirementStrength,
)

enum class PlanningRequirementStrength {
    Must,
    Prefer,
}

data class CandidateOpportunity(
    val id: String,
    val domain: String,
    val title: String,
    val summary: String?,
    val location: String,
    val activityMode: String,
    val startsAt: Instant,
    val endsAt: Instant,
    val estimatedCostWholeUnits: Long?,
    val currencyCode: String?,
    val commuteMinutes: Int?,
    val sourceLabel: String,
    val sourceUpdatedAt: Instant,
    val validUntil: Instant,
)

data class PlanComposition(
    val drafts: List<PlanDraft>,
    val metadata: PlanModelMetadata = PlanModelMetadata(),
)

data class PlanDraft(
    val direction: PlanDirection,
    val opportunityRefs: List<String>,
)

enum class PlanDirection {
    BestMatch,
    MoreRelaxed,
    NewExperience,
}

data class PlanExplanationContext(
    val planningRequestId: String,
    val plans: List<PlanForExplanation>,
    val referenceTime: Instant,
    val timeZoneId: String,
)

data class PlanForExplanation(
    val planId: String,
    val direction: PlanDirection,
    val opportunityRefs: List<String>,
    val facts: List<PlanExplanationFact>,
)

data class PlanExplanationFact(
    val id: String,
    val text: String,
)

data class PlanExplanation(
    val narratives: List<PlanNarrative>,
    val metadata: PlanModelMetadata = PlanModelMetadata(),
)

data class PlanModelMetadata(
    val provider: String? = null,
    val model: String? = null,
    val promptVersion: String? = null,
    val providerRequestId: String? = null,
    val attemptCount: Int? = null,
    val usage: StructuredModelUsage? = null,
    val diagnostics: StructuredModelRequestDiagnostics = StructuredModelRequestDiagnostics(),
)

data class PlanNarrative(
    val planId: String,
    val title: String,
    val summary: String,
    val reasons: List<PlanNarrativePoint>,
    val tradeoffs: List<PlanNarrativePoint>,
)

data class PlanNarrativePoint(
    val text: String,
    val factIds: List<String>,
)
