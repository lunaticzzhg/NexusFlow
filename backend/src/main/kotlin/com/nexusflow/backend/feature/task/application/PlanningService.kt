package com.nexusflow.backend.feature.task.application

import com.nexusflow.ai.context.ModelContextBlockPayload as AiModelContextBlockPayload
import com.nexusflow.ai.context.ModelContextTrustPayload as AiModelContextTrustPayload
import com.nexusflow.ai.planner.CandidateOpportunity as AiCandidateOpportunity
import com.nexusflow.ai.planner.PlanComposer
import com.nexusflow.ai.planner.PlanDirection as AiPlanDirection
import com.nexusflow.ai.planner.PlanDraft as AiPlanDraft
import com.nexusflow.ai.planner.PlanExplainer
import com.nexusflow.ai.planner.PlanExplanationContext as AiPlanExplanationContext
import com.nexusflow.ai.planner.PlanExplanationFact as AiPlanExplanationFact
import com.nexusflow.ai.planner.PlanForExplanation as AiPlanForExplanation
import com.nexusflow.ai.planner.PlanNarrative as AiPlanNarrative
import com.nexusflow.ai.planner.PlanningContext as AiPlanningContext
import com.nexusflow.ai.planner.PlanningRequirement as AiPlanningRequirement
import com.nexusflow.ai.planner.PlanningRequirementStrength as AiPlanningRequirementStrength
import com.nexusflow.ai.provider.StructuredModelCapability
import com.nexusflow.ai.provider.StructuredModelException
import com.nexusflow.ai.provider.StructuredModelRequestDiagnostics
import com.nexusflow.backend.core.aicontext.ModelContextAllowance
import com.nexusflow.backend.core.aicontext.ModelContextAssemblyDiagnostics
import com.nexusflow.backend.core.aicontext.ModelContextAssembler
import com.nexusflow.backend.core.aicontext.ModelContextBlock
import com.nexusflow.backend.core.aicontext.ModelContextKey
import com.nexusflow.backend.core.aicontext.ModelContextLifecycle
import com.nexusflow.backend.core.aicontext.ModelContextResolveRequest
import com.nexusflow.backend.core.aicontext.ModelContextTrust
import com.nexusflow.backend.core.identity.ActorContext
import com.nexusflow.backend.feature.task.domain.ActivityModeValue
import com.nexusflow.backend.feature.task.domain.AvailabilityFact
import com.nexusflow.backend.feature.task.domain.Opportunity
import com.nexusflow.backend.feature.task.domain.OpportunityId
import com.nexusflow.backend.feature.task.domain.OpportunityProvider
import com.nexusflow.backend.feature.task.domain.OpportunityRequest
import com.nexusflow.backend.feature.task.domain.PersistPlansCommand
import com.nexusflow.backend.feature.task.domain.PersistPlansResult
import com.nexusflow.backend.feature.task.domain.Plan
import com.nexusflow.backend.feature.task.domain.PlanDirection
import com.nexusflow.backend.feature.task.domain.PlanDraft
import com.nexusflow.backend.feature.task.domain.PlanId
import com.nexusflow.backend.feature.task.domain.PlanValidationResult
import com.nexusflow.backend.feature.task.domain.PlanValidator
import com.nexusflow.backend.feature.task.domain.PlanningContextSnapshot
import com.nexusflow.backend.feature.task.domain.Requirement
import com.nexusflow.backend.feature.task.domain.RequirementKind
import com.nexusflow.backend.feature.task.domain.RequirementStrength
import com.nexusflow.backend.feature.task.domain.RequirementValue
import com.nexusflow.backend.feature.task.domain.SelectPlanCommand
import com.nexusflow.backend.feature.task.domain.SelectPlanResult
import com.nexusflow.backend.feature.task.domain.TaskDetail
import com.nexusflow.backend.feature.task.domain.TaskId
import com.nexusflow.backend.feature.task.domain.TaskOwner
import com.nexusflow.backend.feature.task.domain.TaskRepository
import com.nexusflow.backend.feature.task.domain.TenantId
import com.nexusflow.backend.feature.task.domain.UserId
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant as ContractInstant
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class PlanningService(
    private val repository: TaskRepository,
    private val opportunityProvider: OpportunityProvider,
    private val planValidator: PlanValidator,
    private val planComposer: PlanComposer? = null,
    private val planExplainer: PlanExplainer? = null,
    private val modelContextAssembler: ModelContextAssembler? = null,
    private val readinessPolicy: PlanningReadinessPolicy = PlanningReadinessPolicy(),
    private val clock: Clock = Clock.systemUTC(),
    private val uuidFactory: () -> UUID = UUID::randomUUID,
    private val timeZoneId: String = "UTC",
) {
    init {
        try {
            ZoneId.of(timeZoneId)
        } catch (_: DateTimeException) {
            error("PlanningService timeZoneId is invalid")
        }
    }

    suspend fun planIfReady(
        actor: ActorContext,
        owner: TaskOwner,
        detail: TaskDetail,
    ): TaskDetail {
        if (readinessPolicy.decide(detail) != PlanningDecision.Plan) return detail
        return plan(actor, owner, detail)
    }

    suspend fun selectPlan(
        actor: ActorContext,
        taskId: String,
        planId: String,
    ): TaskDetail {
        actor.requireScope(WRITE_SCOPE)
        return when (
            val result = repository.selectCurrentPlan(
                SelectPlanCommand(
                    owner = actor.taskOwner(),
                    taskId = taskId.toTaskId(),
                    planId = planId.toPlanId(),
                    now = clock.instant(),
                ),
            )
        ) {
            is SelectPlanResult.Selected -> result.detail
            SelectPlanResult.Expired,
            SelectPlanResult.RevisionConflict,
            -> throw TaskConflictException()
            SelectPlanResult.PlanNotFound,
            SelectPlanResult.TaskNotFound,
            -> throw TaskNotFoundException()
        }
    }

    private suspend fun plan(
        actor: ActorContext,
        owner: TaskOwner,
        detail: TaskDetail,
    ): TaskDetail {
        val now = clock.instant()
        val opportunities = opportunityProvider.discover(
            OpportunityRequest(
                task = detail.task,
                requirements = detail.requirements,
                referenceTime = now,
            ),
        ).filterVerified(now)
        if (opportunities.isEmpty()) {
            throw TaskDependencyUnavailableException("Planning candidates are temporarily unavailable")
        }

        val optionalContext = planningOptionalContext(actor, detail)
        val drafts = composePlans(detail, opportunities, now, optionalContext)
        val context = PlanningContextSnapshot(
            task = detail.task,
            requirements = detail.requirements,
            opportunities = opportunities,
            referenceTime = now,
        )
        val materialized = validatePlans(context, drafts)
        val finalPlans = explainPlans(detail.task.id.value.toString(), materialized, opportunities, now)

        return when (
            val persisted = repository.persistPlans(
                PersistPlansCommand(
                    owner = owner,
                    taskId = detail.task.id,
                    expectedTaskRevision = detail.task.revision,
                    opportunities = opportunities,
                    plans = finalPlans,
                    now = clock.instant(),
                ),
            )
        ) {
            is PersistPlansResult.Persisted -> persisted.detail
            PersistPlansResult.TaskNotFound -> throw TaskNotFoundException()
            PersistPlansResult.StaleTaskRevision -> throw TaskConflictException()
        }
    }

    private suspend fun composePlans(
        detail: TaskDetail,
        opportunities: List<Opportunity>,
        now: Instant,
        optionalContext: PlanningOptionalContext,
    ): List<AiPlanDraft> {
        val composer = planComposer ?: throw TaskDependencyUnavailableException("Planning is temporarily unavailable")
        return try {
            composer.compose(
                AiPlanningContext(
                    planningRequestId = "plan-${detail.task.id.value}-${detail.task.revision}",
                    taskId = detail.task.id.value.toString(),
                    taskRevision = detail.task.revision,
                    intent = detail.task.intent,
                    requirements = detail.requirements.map { it.toAiPlanningRequirement() },
                    opportunities = opportunities.map { it.toAiCandidateOpportunity() },
                    referenceTime = now.toContractInstant(),
                    timeZoneId = timeZoneId,
                    optionalContext = optionalContext.blocks.map { it.toAiPayload() },
                    diagnostics = optionalContext.diagnostics,
                ),
            ).drafts
        } catch (error: CancellationException) {
            throw error
        } catch (_: StructuredModelException) {
            throw TaskDependencyUnavailableException("Planning is temporarily unavailable")
        }
    }

    private suspend fun planningOptionalContext(
        actor: ActorContext,
        detail: TaskDetail,
    ): PlanningOptionalContext {
        if (detail.selectedContextKeys.isEmpty()) return PlanningOptionalContext()
        val assembler = modelContextAssembler ?: return PlanningOptionalContext()
        return try {
            val selectedKeys = detail.selectedContextKeys.map(::ModelContextKey)
            val allowance = ModelContextAllowance(
                capability = StructuredModelCapability.PlanComposition,
                lifecycles = setOf(ModelContextLifecycle.Task),
            )
            val request = ModelContextResolveRequest(
                actor = actor,
                allowance = allowance,
                taskId = detail.task.id.value.toString(),
                taskVersion = detail.task.revision,
                shadowedKeys = detail.requirements.mapNotNullTo(mutableSetOf()) { it.kind.profileContextKeyOrNull() },
            )
            val assembled = assembler.assemble(request, selectedKeys)
            PlanningOptionalContext(
                blocks = assembled.optionalContext,
                diagnostics = assembled.diagnostics.toAiRequestDiagnostics(),
            )
        } catch (_: IllegalArgumentException) {
            throw TaskDependencyUnavailableException("Planning is temporarily unavailable")
        }
    }

    private fun validatePlans(
        context: PlanningContextSnapshot,
        proposals: List<AiPlanDraft>,
    ): List<Plan> {
        val drafts = proposals.map { proposal ->
            PlanDraft(
                id = PlanId(uuidFactory()),
                direction = proposal.direction.toBackendDirection(),
                opportunityRefs = proposal.opportunityRefs.map { it.toOpportunityId() },
            )
        }
        return when (val result = planValidator.validate(context, drafts)) {
            is PlanValidationResult.Accepted -> result.plans
            is PlanValidationResult.Rejected -> throw TaskDependencyUnavailableException("Planning result is temporarily unavailable")
        }
    }

    private suspend fun explainPlans(
        requestId: String,
        plans: List<Plan>,
        opportunities: List<Opportunity>,
        now: Instant,
    ): List<Plan> {
        val explainer = planExplainer ?: throw TaskDependencyUnavailableException("Planning is temporarily unavailable")
        val explanationContext = AiPlanExplanationContext(
            planningRequestId = requestId,
            plans = plans.map { it.toAiPlanForExplanation(opportunities) },
            referenceTime = now.toContractInstant(),
            timeZoneId = timeZoneId,
        )
        val explanation = try {
            explainer.explain(explanationContext)
        } catch (error: CancellationException) {
            throw error
        } catch (_: StructuredModelException) {
            throw TaskDependencyUnavailableException("Planning explanation is temporarily unavailable")
        }
        val factsByPlan = explanationContext.plans.associate { plan ->
            plan.planId to plan.facts.map { it.id }.toSet()
        }
        val narratives = explanation.narratives.associateBy { it.planId }
        if (narratives.keys != plans.map { it.id.value.toString() }.toSet()) {
            throw TaskDependencyUnavailableException("Planning explanation is temporarily unavailable")
        }
        return plans.map { plan ->
            val planId = plan.id.value.toString()
            val narrative = narratives.getValue(planId)
            narrative.verifyFactRefs(factsByPlan.getValue(planId))
            plan.copy(
                title = narrative.title,
                summary = narrative.summary,
                reasons = narrative.reasons.map { it.text },
                tradeoffs = narrative.tradeoffs.map { it.text },
            )
        }
    }

    private fun AiPlanNarrative.verifyFactRefs(allowedFactIds: Set<String>) {
        val referenced = (reasons + tradeoffs).flatMap { it.factIds }
        if (referenced.any { it !in allowedFactIds }) {
            throw TaskDependencyUnavailableException("Planning explanation is temporarily unavailable")
        }
    }

    private fun ActorContext.taskOwner(): TaskOwner =
        TaskOwner(
            tenantId = TenantId(tenantId.toUuid("tenantId")),
            userId = UserId(userId.toUuid("userId")),
        )

    private fun ActorContext.requireScope(scope: String) {
        if (!hasScope(scope)) throw MissingTaskScopeException()
    }

    private fun String.toTaskId(): TaskId = TaskId(toUuid("taskId"))

    private fun String.toPlanId(): PlanId = PlanId(toUuid("planId"))

    private fun String.toOpportunityId() = OpportunityId(toUuid("opportunityRef"))

    private fun String.toUuid(fieldName: String): UUID =
        try {
            UUID.fromString(this)
        } catch (_: IllegalArgumentException) {
            throw InvalidTaskRequestException("$fieldName is invalid")
        }

    private fun List<Opportunity>.filterVerified(now: Instant): List<Opportunity> =
        filter { opportunity ->
            opportunity.facts.availability != AvailabilityFact.Unavailable &&
                opportunity.validUntil?.isAfter(now) == true &&
                opportunity.sources.isNotEmpty()
        }

    private fun Requirement.toAiPlanningRequirement(): AiPlanningRequirement =
        AiPlanningRequirement(
            id = id.value.toString(),
            kind = kind.name,
            valueSummary = value.summary(),
            strength = when (strength) {
                RequirementStrength.Must -> AiPlanningRequirementStrength.Must
                RequirementStrength.Prefer -> AiPlanningRequirementStrength.Prefer
            },
        )

    private fun RequirementValue.summary(): String =
        when (this) {
            is RequirementValue.TimeWindow -> originalText
            is RequirementValue.BudgetLimit -> listOfNotNull(wholeUnits.toString(), currencyCode).joinToString(" ")
            is RequirementValue.CommuteLimit -> "$maxMinutes minutes"
            is RequirementValue.CommutePreference -> value.name
            is RequirementValue.Location -> text
            is RequirementValue.ActivityDomain -> value
            is RequirementValue.ActivityMode -> value.name
            is RequirementValue.Topic -> text
            is RequirementValue.ExperiencePreference -> text
        }

    private fun RequirementKind.profileContextKeyOrNull(): ModelContextKey? =
        when (this) {
            RequirementKind.TimeWindow -> ModelContextKey("profile.preference.time_window")
            RequirementKind.BudgetLimit -> ModelContextKey("profile.preference.budget_limit")
            RequirementKind.CommuteLimit -> ModelContextKey("profile.preference.commute_limit")
            RequirementKind.CommutePreference -> ModelContextKey("profile.preference.commute_mode")
            RequirementKind.Location -> ModelContextKey("profile.preference.location")
            RequirementKind.ActivityDomain -> ModelContextKey("profile.preference.activity_domain")
            RequirementKind.ActivityMode -> ModelContextKey("profile.preference.activity_mode")
            RequirementKind.Topic -> ModelContextKey("profile.preference.topic")
            RequirementKind.ExperiencePreference -> ModelContextKey("profile.preference.experience")
        }

    private fun ModelContextBlock.toAiPayload(): AiModelContextBlockPayload =
        AiModelContextBlockPayload(
            key = key,
            trust = trust.toAiPayload(),
            content = content,
        )

    private fun ModelContextAssemblyDiagnostics.toAiRequestDiagnostics(): StructuredModelRequestDiagnostics =
        StructuredModelRequestDiagnostics(
            selectedContextKeyCount = selectedContextKeyCount,
            resolvedContextBlockCount = resolvedContextBlockCount,
            includedContextBlockCount = includedContextBlockCount,
            omittedContextBlockCount = omittedContextBlockCount,
            optionalContextSerializedChars = optionalContextSerializedChars,
        )

    private fun ModelContextTrust.toAiPayload(): AiModelContextTrustPayload =
        AiModelContextTrustPayload.valueOf(name)

    private fun Opportunity.toAiCandidateOpportunity(): AiCandidateOpportunity =
        AiCandidateOpportunity(
            id = id.value.toString(),
            domain = kind.name,
            title = title,
            summary = facts.summary,
            location = facts.location?.displayName.orEmpty(),
            activityMode = when (facts.activityMode) {
                ActivityModeValue.AtHome -> "at_home"
                ActivityModeValue.OutOfHome -> "out_of_home"
                null -> "unknown"
            },
            startsAt = facts.startTime?.toContractInstant() ?: observedAt.toContractInstant(),
            endsAt = facts.endTime?.toContractInstant() ?: observedAt.toContractInstant(),
            estimatedCostWholeUnits = facts.price?.wholeUnits,
            currencyCode = facts.price?.currencyCode,
            commuteMinutes = facts.commute?.minutes,
            sourceLabel = sources.first().label,
            sourceUpdatedAt = sources.first().sourceUpdatedAt?.toContractInstant() ?: observedAt.toContractInstant(),
            validUntil = validUntil?.toContractInstant() ?: observedAt.toContractInstant(),
        )

    private fun Plan.toAiPlanForExplanation(opportunities: List<Opportunity>): AiPlanForExplanation {
        val opportunitiesById = opportunities.associateBy { it.id }
        val facts = opportunityRefs.flatMap { opportunityId ->
            val opportunity = opportunitiesById.getValue(opportunityId)
            listOf(
                AiPlanExplanationFact("opportunity:${opportunity.id.value}:title", "Opportunity title: ${opportunity.title}"),
                AiPlanExplanationFact("opportunity:${opportunity.id.value}:time", "Runs from ${opportunity.facts.startTime} to ${opportunity.facts.endTime}"),
                AiPlanExplanationFact("opportunity:${opportunity.id.value}:location", "Location: ${opportunity.facts.location?.displayName.orEmpty()}"),
                AiPlanExplanationFact("opportunity:${opportunity.id.value}:cost", "Estimated cost: ${opportunity.facts.price?.wholeUnits ?: 0}"),
                AiPlanExplanationFact("opportunity:${opportunity.id.value}:commute", "Commute minutes: ${opportunity.facts.commute?.minutes ?: 0}"),
                AiPlanExplanationFact("opportunity:${opportunity.id.value}:source", "Source: ${opportunity.sources.first().label} updated at ${opportunity.sources.first().sourceUpdatedAt}"),
                AiPlanExplanationFact("opportunity:${opportunity.id.value}:validUntil", "Valid until ${opportunity.validUntil}"),
            )
        }
        return AiPlanForExplanation(
            planId = id.value.toString(),
            direction = direction.toAiDirection(),
            opportunityRefs = opportunityRefs.map { it.value.toString() },
            facts = facts,
        )
    }

    private fun PlanDirection.toAiDirection(): AiPlanDirection =
        when (this) {
            PlanDirection.BestMatch -> AiPlanDirection.BestMatch
            PlanDirection.MoreRelaxed -> AiPlanDirection.MoreRelaxed
            PlanDirection.NewExperience -> AiPlanDirection.NewExperience
        }

    private fun AiPlanDirection.toBackendDirection(): PlanDirection =
        when (this) {
            AiPlanDirection.BestMatch -> PlanDirection.BestMatch
            AiPlanDirection.MoreRelaxed -> PlanDirection.MoreRelaxed
            AiPlanDirection.NewExperience -> PlanDirection.NewExperience
        }

    private fun Instant.toContractInstant(): ContractInstant =
        ContractInstant.fromEpochSeconds(epochSecond, nano.toLong())
}

class PlanningReadinessPolicy {
    fun decide(detail: TaskDetail): PlanningDecision =
        if (detail.requirements.isNotEmpty() && detail.plans.none { it.revision == detail.task.revision }) {
            PlanningDecision.Plan
        } else {
            PlanningDecision.KeepCurrentPlans
        }
}

enum class PlanningDecision {
    KeepCurrentPlans,
    Plan,
}

private data class PlanningOptionalContext(
    val blocks: List<ModelContextBlock> = emptyList(),
    val diagnostics: StructuredModelRequestDiagnostics = StructuredModelRequestDiagnostics(),
)

private const val WRITE_SCOPE = "orbit.tasks.write"
