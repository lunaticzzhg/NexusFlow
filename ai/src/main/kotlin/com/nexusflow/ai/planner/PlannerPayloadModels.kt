package com.nexusflow.ai.planner

import com.nexusflow.contracts.backendai.common.ModelContextBlockPayload
import com.nexusflow.contracts.backendai.conversation.ReadOnlyToolCallProposal
import com.nexusflow.contracts.backendai.conversation.ReadOnlyToolDefinitionPayload
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class PlanningModelPayload(
    @SerialName("request")
    val request: PlanningModelRequest,
    @SerialName("coreContext")
    val coreContext: PlanningCoreContextPayload,
    @SerialName("optionalContext")
    val optionalContext: List<ModelContextBlockPayload> = emptyList(),
)

@Serializable
internal data class PlanningModelRequest(
    @SerialName("referenceTime")
    val referenceTime: Instant,
    @SerialName("timeZoneId")
    val timeZoneId: String,
)

@Serializable
internal data class PlanningCoreContextPayload(
    @SerialName("intent")
    val intent: String,
    @SerialName("requirements")
    val requirements: List<PlanningRequirementPayload>,
    @SerialName("opportunities")
    val opportunities: List<CandidateOpportunityPayload>,
)

@Serializable
internal data class PlanningRequirementPayload(
    @SerialName("kind")
    val kind: String,
    @SerialName("valueSummary")
    val valueSummary: String,
    @SerialName("strength")
    val strength: String,
)

@Serializable
internal data class CandidateOpportunityPayload(
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
    val sources: List<CandidateSourceRefPayload>,
    @SerialName("validUntil")
    val validUntil: Instant?,
)

@Serializable
internal data class CandidateSourceRefPayload(
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

@Serializable
internal data class PlanningResearchModelPayload(
    @SerialName("request")
    val request: PlanningResearchModelRequest,
    @SerialName("coreContext")
    val coreContext: PlanningResearchCoreContextPayload,
    @SerialName("optionalContext")
    val optionalContext: List<ModelContextBlockPayload> = emptyList(),
)

@Serializable
internal data class PlanningResearchModelRequest(
    @SerialName("referenceTime")
    val referenceTime: Instant,
    @SerialName("timeZoneId")
    val timeZoneId: String,
    @SerialName("taskRevision")
    val taskRevision: Long,
)

@Serializable
internal data class PlanningResearchCoreContextPayload(
    @SerialName("goal")
    val goal: String,
    @SerialName("requirements")
    val requirements: List<PlanningRequirementPayload>,
    @SerialName("availableReadTools")
    val availableReadTools: List<ReadOnlyToolDefinitionPayload>,
)

@Serializable
internal data class PlanningResearchPayload(
    @SerialName("toolCalls")
    val toolCalls: List<ReadOnlyToolCallProposal>,
)

@Serializable
internal data class PlanExplanationModelPayload(
    @SerialName("request")
    val request: PlanExplanationModelRequest,
    @SerialName("coreContext")
    val coreContext: PlanExplanationCoreContextPayload,
)

@Serializable
internal data class PlanExplanationModelRequest(
    @SerialName("referenceTime")
    val referenceTime: Instant,
    @SerialName("timeZoneId")
    val timeZoneId: String,
)

@Serializable
internal data class PlanExplanationCoreContextPayload(
    @SerialName("plans")
    val plans: List<PlanForExplanationPayload>,
)

@Serializable
internal data class PlanForExplanationPayload(
    @SerialName("planId")
    val planId: String,
    @SerialName("direction")
    val direction: String,
    @SerialName("opportunityRefs")
    val opportunityRefs: List<String>,
    @SerialName("facts")
    val facts: List<PlanExplanationFactPayload>,
)

@Serializable
internal data class PlanExplanationFactPayload(
    @SerialName("id")
    val id: String,
    @SerialName("text")
    val text: String,
)

@Serializable
internal data class PlanCompositionPayload(
    @SerialName("drafts")
    val drafts: List<PlanDraftPayload>,
)

@Serializable
internal data class PlanDraftPayload(
    @SerialName("direction")
    val direction: String,
    @SerialName("opportunityRefs")
    val opportunityRefs: List<String>,
)

@Serializable
internal data class PlanExplanationPayload(
    @SerialName("narratives")
    val narratives: List<PlanNarrativePayload>,
)

@Serializable
internal data class PlanNarrativePayload(
    @SerialName("planId")
    val planId: String,
    @SerialName("title")
    val title: String,
    @SerialName("summary")
    val summary: String,
    @SerialName("reasons")
    val reasons: List<PlanNarrativePointPayload>,
    @SerialName("tradeoffs")
    val tradeoffs: List<PlanNarrativePointPayload>,
)

@Serializable
internal data class PlanNarrativePointPayload(
    @SerialName("text")
    val text: String,
    @SerialName("factIds")
    val factIds: List<String>,
)
