package com.nexusflow.contracts.backendai.planning

import com.nexusflow.contracts.backendai.common.ModelContextBlockPayload
import com.nexusflow.contracts.backendai.common.StructuredModelRequestDiagnostics
import com.nexusflow.contracts.backendai.common.StructuredModelUsage
import com.nexusflow.contracts.backendai.conversation.ReadOnlyToolCallProposal
import com.nexusflow.contracts.backendai.conversation.ReadOnlyToolDefinitionPayload
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

fun interface PlanComposer {
    suspend fun compose(request: CreatePlansRequest): CreatePlansResult
}

fun interface PlanExplainer {
    suspend fun explain(request: ExplainPlansRequest): ExplainPlansResult
}

fun interface PlanningResearchCapability {
    suspend fun research(request: PlanningResearchRequest): PlanningResearchResult
}

/** Backend asks the AI layer to propose plan drafts from Backend-supplied Task facts and verified opportunities. */
@Serializable
data class CreatePlansRequest(
    @SerialName("planningRequestId")
    val planningRequestId: String,
    @SerialName("taskId")
    val taskId: String,
    @SerialName("taskRevision")
    val taskRevision: Long,
    @SerialName("intent")
    val intent: String,
    @SerialName("requirements")
    val requirements: List<PlanningRequirement>,
    @SerialName("opportunities")
    val opportunities: List<CandidateOpportunity>,
    @SerialName("referenceTime")
    val referenceTime: Instant,
    @SerialName("timeZoneId")
    val timeZoneId: String,
    @SerialName("optionalContext")
    val optionalContext: List<ModelContextBlockPayload> = emptyList(),
    @SerialName("diagnostics")
    val diagnostics: StructuredModelRequestDiagnostics = StructuredModelRequestDiagnostics(),
)

@Serializable
data class PlanningRequirement(
    @SerialName("id")
    val id: String,
    @SerialName("kind")
    val kind: String,
    @SerialName("valueSummary")
    val valueSummary: String,
    @SerialName("strength")
    val strength: PlanningRequirementStrength,
)

/**
 * Backend asks the AI layer to propose bounded read-only research calls needed before plan generation.
 *
 * The AI only proposes tool keys and JsonObject arguments from Backend-offered definitions. Backend owns validation,
 * source execution, opportunity projection, provenance, permissions, idempotency, persistence, and side effects.
 */
@Serializable
data class PlanningResearchRequest(
    @SerialName("planningResearchRequestId")
    val planningResearchRequestId: String,
    @SerialName("taskId")
    val taskId: String,
    @SerialName("taskRevision")
    val taskRevision: Long,
    @SerialName("goal")
    val goal: String,
    @SerialName("requirements")
    val requirements: List<PlanningRequirement>,
    @SerialName("optionalContext")
    val optionalContext: List<ModelContextBlockPayload> = emptyList(),
    @SerialName("availableReadTools")
    val availableReadTools: List<ReadOnlyToolDefinitionPayload> = emptyList(),
    @SerialName("referenceTime")
    val referenceTime: Instant,
    @SerialName("timeZoneId")
    val timeZoneId: String,
    @SerialName("diagnostics")
    val diagnostics: StructuredModelRequestDiagnostics = StructuredModelRequestDiagnostics(),
)

/**
 * Empty proposals are allowed only when no external facts are needed for the current planning request snapshot.
 */
@Serializable
data class PlanningResearchResult(
    @SerialName("toolCalls")
    val toolCalls: List<ReadOnlyToolCallProposal>,
    @SerialName("metadata")
    val metadata: PlanModelMetadata = PlanModelMetadata(),
)

@Serializable
enum class PlanningRequirementStrength {
    @SerialName("must")
    Must,

    @SerialName("prefer")
    Prefer,
}

@Serializable
data class CandidateOpportunity(
    @SerialName("id")
    val id: String,
    @SerialName("domain")
    val domain: String,
    @SerialName("title")
    val title: String,
    @SerialName("summary")
    val summary: String?,
    @SerialName("location")
    val location: String?,
    @SerialName("activityMode")
    val activityMode: String?,
    @SerialName("availability")
    val availability: String? = null,
    @SerialName("startsAt")
    val startsAt: Instant?,
    @SerialName("endsAt")
    val endsAt: Instant?,
    @SerialName("estimatedCostWholeUnits")
    val estimatedCostWholeUnits: Long?,
    @SerialName("currencyCode")
    val currencyCode: String?,
    @SerialName("commuteMinutes")
    val commuteMinutes: Int?,
    @SerialName("sources")
    val sources: List<CandidateSourceRef>,
    @SerialName("validUntil")
    val validUntil: Instant?,
)

@Serializable
data class CandidateSourceRef(
    @SerialName("label")
    val label: String,
    @SerialName("uri")
    val uri: String?,
    @SerialName("sourceUpdatedAt")
    val sourceUpdatedAt: Instant?,
    @SerialName("sourceId")
    val sourceId: String,
    @SerialName("authority")
    val authority: String,
    @SerialName("factKeys")
    val factKeys: List<String>,
)

/**
 * AI returns plan proposals, not authoritative Plans.
 *
 * Backend validates opportunity references, feasibility, task revision, and persistence before any Plan becomes state.
 */
@Serializable
data class CreatePlansResult(
    @SerialName("drafts")
    val drafts: List<PlanProposal>,
    @SerialName("metadata")
    val metadata: PlanModelMetadata = PlanModelMetadata(),
)

@Serializable
data class PlanProposal(
    @SerialName("direction")
    val direction: PlanDirection,
    @SerialName("opportunityRefs")
    val opportunityRefs: List<String>,
)

@Serializable
enum class PlanDirection {
    @SerialName("best_match")
    BestMatch,

    @SerialName("more_relaxed")
    MoreRelaxed,

    @SerialName("new_experience")
    NewExperience,
}

/** Backend asks the AI layer to explain already-validated Plans using only the provided facts. */
@Serializable
data class ExplainPlansRequest(
    @SerialName("planningRequestId")
    val planningRequestId: String,
    @SerialName("plans")
    val plans: List<PlanForExplanation>,
    @SerialName("referenceTime")
    val referenceTime: Instant,
    @SerialName("timeZoneId")
    val timeZoneId: String,
)

@Serializable
data class PlanForExplanation(
    @SerialName("planId")
    val planId: String,
    @SerialName("direction")
    val direction: PlanDirection,
    @SerialName("opportunityRefs")
    val opportunityRefs: List<String>,
    @SerialName("facts")
    val facts: List<PlanExplanationFact>,
)

@Serializable
data class PlanExplanationFact(
    @SerialName("id")
    val id: String,
    @SerialName("text")
    val text: String,
)

/** AI returns narratives grounded in Backend-provided facts; it must not add new prices, times, places, or sources. */
@Serializable
data class ExplainPlansResult(
    @SerialName("narratives")
    val narratives: List<PlanNarrative>,
    @SerialName("metadata")
    val metadata: PlanModelMetadata = PlanModelMetadata(),
)

@Serializable
data class PlanModelMetadata(
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

@Serializable
data class PlanNarrative(
    @SerialName("planId")
    val planId: String,
    @SerialName("title")
    val title: String,
    @SerialName("summary")
    val summary: String,
    @SerialName("reasons")
    val reasons: List<PlanNarrativePoint>,
    @SerialName("tradeoffs")
    val tradeoffs: List<PlanNarrativePoint>,
)

@Serializable
data class PlanNarrativePoint(
    @SerialName("text")
    val text: String,
    @SerialName("factIds")
    val factIds: List<String>,
)
