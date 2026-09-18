package com.nexusflow.backend.feature.task.application

import com.nexusflow.contracts.backendai.common.ModelContextBlockPayload as AiModelContextBlockPayload
import com.nexusflow.contracts.backendai.common.ModelContextTrustPayload as AiModelContextTrustPayload
import com.nexusflow.contracts.backendai.common.AiCapabilityException
import com.nexusflow.contracts.backendai.common.StructuredModelCapability
import com.nexusflow.contracts.backendai.common.StructuredModelRequestDiagnostics
import com.nexusflow.contracts.backendai.planning.CandidateOpportunity as AiCandidateOpportunity
import com.nexusflow.contracts.backendai.planning.PlanComposer
import com.nexusflow.contracts.backendai.planning.PlanDirection as AiPlanDirection
import com.nexusflow.contracts.backendai.planning.PlanExplainer
import com.nexusflow.contracts.backendai.planning.ExplainPlansRequest as AiExplainPlansRequest
import com.nexusflow.contracts.backendai.planning.PlanExplanationFact as AiPlanExplanationFact
import com.nexusflow.contracts.backendai.planning.PlanForExplanation as AiPlanForExplanation
import com.nexusflow.contracts.backendai.planning.PlanNarrative as AiPlanNarrative
import com.nexusflow.contracts.backendai.planning.PlanProposal as AiPlanProposal
import com.nexusflow.contracts.backendai.planning.PlanningResearchCapability
import com.nexusflow.contracts.backendai.planning.PlanningResearchRequest as AiPlanningResearchRequest
import com.nexusflow.contracts.backendai.planning.CreatePlansRequest as AiCreatePlansRequest
import com.nexusflow.contracts.backendai.planning.CandidateSourceRef as AiCandidateSourceRef
import com.nexusflow.contracts.backendai.planning.PlanningRequirement as AiPlanningRequirement
import com.nexusflow.contracts.backendai.planning.PlanningRequirementStrength as AiPlanningRequirementStrength
import com.nexusflow.contracts.backendai.conversation.ReadOnlyToolDefinitionPayload
import com.nexusflow.backend.core.aicontext.ModelContextAllowance
import com.nexusflow.backend.core.aicontext.ModelContextAssemblyDiagnostics
import com.nexusflow.backend.core.aicontext.ModelContextAssembler
import com.nexusflow.backend.core.aicontext.ModelContextBlock
import com.nexusflow.backend.core.aicontext.ModelContextKey
import com.nexusflow.backend.core.aicontext.ModelContextLifecycle
import com.nexusflow.backend.core.aicontext.ModelContextResolveRequest
import com.nexusflow.backend.core.aicontext.ModelContextTrust
import com.nexusflow.backend.core.identity.ActorContext
import com.nexusflow.backend.core.readtool.ReadToolCall
import com.nexusflow.backend.core.readtool.ReadToolCatalog
import com.nexusflow.backend.core.readtool.ReadToolEvidence
import com.nexusflow.backend.core.readtool.ReadToolExecution
import com.nexusflow.backend.core.readtool.ReadToolExecutionContext
import com.nexusflow.backend.core.readtool.ReadToolExecutionObserver
import com.nexusflow.backend.core.readtool.ReadToolFactKind
import com.nexusflow.backend.core.readtool.ReadToolFactValue
import com.nexusflow.backend.core.readtool.ReadToolExecutor
import com.nexusflow.backend.core.readtool.ReadToolKey
import com.nexusflow.backend.core.readtool.ReadToolOutcome
import com.nexusflow.backend.core.readtool.ReadToolSourceAuthority
import com.nexusflow.backend.feature.task.application.readtool.MovieShowtimesKey
import com.nexusflow.backend.feature.task.application.readtool.MusicEventsKey
import com.nexusflow.backend.feature.task.application.readtool.OutdoorTrailsKey
import com.nexusflow.backend.feature.task.application.readtool.SportsEventsKey
import com.nexusflow.backend.feature.task.application.readtool.SportsFixturesKey
import com.nexusflow.backend.feature.task.domain.ActivityModeValue
import com.nexusflow.backend.feature.task.domain.AvailabilityFact
import com.nexusflow.backend.feature.task.domain.DurationFact
import com.nexusflow.backend.feature.task.domain.FactValue
import com.nexusflow.backend.feature.task.domain.LocationFact
import com.nexusflow.backend.feature.task.domain.MoneyFact
import com.nexusflow.backend.feature.task.domain.Opportunity
import com.nexusflow.backend.feature.task.domain.OpportunityFactKey
import com.nexusflow.backend.feature.task.domain.OpportunityFacts
import com.nexusflow.backend.feature.task.domain.OpportunityId
import com.nexusflow.backend.feature.task.domain.OpportunityKind
import com.nexusflow.backend.feature.task.domain.PersistPlansCommand
import com.nexusflow.backend.feature.task.domain.PersistPlansResult
import com.nexusflow.backend.feature.task.domain.Plan
import com.nexusflow.backend.feature.task.domain.PlanDirection
import com.nexusflow.backend.feature.task.domain.PlanDraft
import com.nexusflow.backend.feature.task.domain.PlanId
import com.nexusflow.backend.feature.task.domain.PlanValidationFailure
import com.nexusflow.backend.feature.task.domain.PlanValidator
import com.nexusflow.backend.feature.task.domain.PlanningContextSnapshot
import com.nexusflow.backend.feature.task.domain.Requirement
import com.nexusflow.backend.feature.task.domain.RequirementKind
import com.nexusflow.backend.feature.task.domain.RequirementStrength
import com.nexusflow.backend.feature.task.domain.RequirementValue
import com.nexusflow.backend.feature.task.domain.SelectPlanCommand
import com.nexusflow.backend.feature.task.domain.SelectPlanResult
import com.nexusflow.backend.feature.task.domain.SourceAuthority
import com.nexusflow.backend.feature.task.domain.SourceRef
import com.nexusflow.backend.feature.task.domain.TaskDetail
import com.nexusflow.backend.feature.task.domain.TaskId
import com.nexusflow.backend.feature.task.domain.TaskOwner
import com.nexusflow.backend.feature.task.domain.TaskRepository
import com.nexusflow.backend.feature.task.domain.TenantId
import com.nexusflow.backend.feature.task.domain.UserId
import com.nexusflow.backend.feature.task.domain.isFeasibilityFailure
import com.nexusflow.observability.StructuredLogger
import com.nexusflow.observability.logFields
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant as ContractInstant
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.nio.charset.StandardCharsets
import java.util.UUID

class PlanningService(
    private val repository: TaskRepository,
    private val planValidator: PlanValidator,
    private val planningResearch: PlanningResearchCapability? = null,
    private val readToolCatalog: ReadToolCatalog = ReadToolCatalog(emptyList()),
    private val readToolExecutor: ReadToolExecutor = ReadToolExecutor(readToolCatalog),
    private val planComposer: PlanComposer? = null,
    private val planExplainer: PlanExplainer? = null,
    private val modelContextAssembler: ModelContextAssembler? = null,
    private val readinessPolicy: PlanningReadinessPolicy = PlanningReadinessPolicy(),
    private val logger: StructuredLogger? = null,
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
        trigger: PlanningTrigger = PlanningTrigger.PlanningInputChanged,
    ): PlanningAttemptResult {
        val computation = computeIfReady(actor, detail, trigger)
        if (computation.outcome != PlanningOutcome.Ready) {
            return PlanningAttemptResult(detail, computation.outcome)
        }
        val planned = when (
            val persisted = repository.persistPlans(
                PersistPlansCommand(
                    owner = owner,
                    taskId = detail.task.id,
                    expectedTaskRevision = detail.task.revision,
                    opportunities = computation.opportunities,
                    plans = computation.plans,
                    now = clock.instant(),
                ),
            )
        ) {
            is PersistPlansResult.Persisted -> persisted.detail
            PersistPlansResult.TaskNotFound -> throw TaskNotFoundException()
            PersistPlansResult.StaleTaskRevision -> {
                logger?.warn(
                    component = PLANNING_LOG_COMPONENT,
                    event = "planning_finished",
                    fields =
                        logFields {
                            "task_id" value detail.task.id.value.toString()
                            "task_revision" value detail.task.revision
                            "planning_trigger" value trigger.logValue
                            "outcome" value PlanningOutcome.Superseded.logValue
                            "stage" value "persist"
                        },
                )
                val latest = repository.findTaskDetail(owner, detail.task.id) ?: throw TaskNotFoundException()
                return PlanningAttemptResult(latest, PlanningOutcome.Superseded)
            }
        }
        return PlanningAttemptResult(planned, PlanningOutcome.Ready)
    }

    suspend fun computeIfReady(
        actor: ActorContext,
        detail: TaskDetail,
        trigger: PlanningTrigger = PlanningTrigger.PlanningInputChanged,
        readToolObserver: ReadToolExecutionObserver? = null,
    ): PlanningComputationResult {
        val decision = readinessPolicy.decide(detail, trigger)
        if (decision != PlanningDecision.Plan) {
            logPlanningNotAttempted(detail, trigger, decision)
            return PlanningComputationResult(
                expectedTaskId = detail.task.id,
                expectedTaskRevision = detail.task.revision,
                opportunities = emptyList(),
                plans = emptyList(),
                outcome = PlanningOutcome.NotAttempted,
            )
        }
        return compute(actor, detail, trigger, readToolObserver)
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

    private suspend fun compute(
        actor: ActorContext,
        detail: TaskDetail,
        trigger: PlanningTrigger,
        readToolObserver: ReadToolExecutionObserver?,
    ): PlanningComputationResult {
        val startedAt = clock.instant()
        var stage = "opportunity_discovery"
        var planningFailureLogged = false
        logPlanningStarted(detail, trigger)
        try {
            val now = clock.instant()
            stage = "optional_context"
            val optionalContext = planningOptionalContext(actor, detail)
            stage = "planning_research"
            val discovery = discoverOpportunitiesWithPlanningResearch(actor, detail, now, optionalContext, readToolObserver)
            if (discovery.outcome != null) {
                planningFailureLogged = true
                logPlanningFinished(
                    detail = detail,
                    startedAt = startedAt,
                    trigger = trigger,
                    outcome = discovery.outcome,
                    opportunityCount = 0,
                    planCount = 0,
                )
                return PlanningComputationResult(
                    expectedTaskId = detail.task.id,
                    expectedTaskRevision = detail.task.revision,
                    opportunities = emptyList(),
                    plans = emptyList(),
                    outcome = discovery.outcome,
                )
            }
            val opportunities = discovery.opportunities.filterVerified(now)
            if (opportunities.isEmpty()) {
                planningFailureLogged = true
                logPlanningFinished(
                    detail = detail,
                    startedAt = startedAt,
                    trigger = trigger,
                    outcome = PlanningOutcome.NoCandidates,
                    opportunityCount = 0,
                    planCount = 0,
                )
                return PlanningComputationResult(
                    expectedTaskId = detail.task.id,
                    expectedTaskRevision = detail.task.revision,
                    opportunities = emptyList(),
                    plans = emptyList(),
                    outcome = PlanningOutcome.NoCandidates,
                )
            }

            stage = "plan_compose"
            val drafts = composePlans(detail, opportunities, now, optionalContext)
            val context = PlanningContextSnapshot(
                task = detail.task,
                requirements = detail.requirements,
                opportunities = opportunities,
                referenceTime = now,
            )
            stage = "plan_validation"
            val validation = validatePlans(context, drafts)
            if (validation.plans.isEmpty()) {
                val outcome = validation.failures.toPlanningOutcome()
                planningFailureLogged = true
                logPlanningFinished(
                    detail = detail,
                    startedAt = startedAt,
                    trigger = trigger,
                    outcome = outcome,
                    opportunityCount = opportunities.size,
                    planCount = 0,
                    validationFailures = validation.failures,
                )
                return PlanningComputationResult(
                    expectedTaskId = detail.task.id,
                    expectedTaskRevision = detail.task.revision,
                    opportunities = opportunities,
                    plans = emptyList(),
                    outcome = outcome,
                )
            }
            stage = "plan_explain"
            val finalPlans = explainPlans(detail.task.id.value.toString(), validation.plans, opportunities, now)
            logPlanningFinished(
                detail = detail,
                startedAt = startedAt,
                trigger = trigger,
                outcome = PlanningOutcome.Ready,
                opportunityCount = opportunities.size,
                planCount = finalPlans.size,
                validationFailures = validation.failures,
            )
            return PlanningComputationResult(
                expectedTaskId = detail.task.id,
                expectedTaskRevision = detail.task.revision,
                opportunities = opportunities,
                plans = finalPlans,
                outcome = PlanningOutcome.Ready,
            )
        } catch (cause: CancellationException) {
            if (!planningFailureLogged) {
                logPlanningFailed(detail, startedAt, trigger, stage, "cancelled", cause)
            }
            throw cause
        } catch (cause: TaskDependencyUnavailableException) {
            if (!planningFailureLogged) {
                logPlanningUnavailable(detail, startedAt, trigger, stage, cause)
            }
            return PlanningComputationResult(
                expectedTaskId = detail.task.id,
                expectedTaskRevision = detail.task.revision,
                opportunities = emptyList(),
                plans = emptyList(),
                outcome = PlanningOutcome.Unavailable,
            )
        } catch (cause: Throwable) {
            if (!planningFailureLogged) {
                logPlanningFailed(detail, startedAt, trigger, stage, cause.safeFailureCategory(), cause)
            }
            throw cause
        }
    }

    private suspend fun discoverOpportunitiesWithPlanningResearch(
        actor: ActorContext,
        detail: TaskDetail,
        now: Instant,
        optionalContext: PlanningOptionalContext,
        readToolObserver: ReadToolExecutionObserver?,
    ): PlanningResearchDiscovery {
        val research = planningResearch
            ?: return PlanningResearchDiscovery(outcome = PlanningOutcome.Unavailable)
        val availableReadTools = readToolCatalog.definitions()
        if (availableReadTools.isEmpty()) {
            return PlanningResearchDiscovery(outcome = PlanningOutcome.Unavailable)
        }
        logPlanningResearchStarted(detail, availableReadTools.size)
        val researchResult = try {
            research.research(
                AiPlanningResearchRequest(
                    planningResearchRequestId = "research-${detail.task.id.value}-${detail.task.revision}",
                    taskId = detail.task.id.value.toString(),
                    taskRevision = detail.task.revision,
                    goal = detail.task.intent,
                    requirements = detail.requirements.map { it.toAiPlanningRequirement() },
                    optionalContext = optionalContext.blocks.map { it.toAiPayload() },
                    availableReadTools = availableReadTools.map { definition ->
                        ReadOnlyToolDefinitionPayload(
                            toolKey = definition.key.value,
                            description = definition.description,
                            argumentHint = definition.argumentHint,
                        )
                    },
                    referenceTime = now.toContractInstant(),
                    timeZoneId = timeZoneId,
                    diagnostics = optionalContext.diagnostics,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: AiCapabilityException) {
            return PlanningResearchDiscovery(outcome = PlanningOutcome.Unavailable)
        }

        val offeredKeys = availableReadTools.mapTo(linkedSetOf()) { it.key }
        val calls = researchResult.toolCalls.map { proposal ->
            val key = proposal.toolKey.trim().takeIf(String::isNotBlank)
                ?: return PlanningResearchDiscovery(outcome = PlanningOutcome.Unavailable)
            val readToolKey = ReadToolKey(key)
            if (readToolKey !in offeredKeys) {
                return PlanningResearchDiscovery(outcome = PlanningOutcome.Unavailable)
            }
            ReadToolCall(readToolKey, proposal.arguments)
        }
        if (calls.isEmpty()) {
            logPlanningResearchFinished(detail, emptyList(), 0)
            return PlanningResearchDiscovery(outcome = PlanningOutcome.NoCandidates)
        }

        val executions = try {
            readToolExecutor.execute(
                calls = calls,
                context = ReadToolExecutionContext(
                    referenceTime = now,
                    timeZoneId = timeZoneId,
                    actorTenantId = actor.tenantId,
                    actorUserId = actor.userId,
                    conversationId = null,
                    taskId = detail.task.id.value.toString(),
                ),
                observer = readToolObserver,
            )
        } catch (_: IllegalArgumentException) {
            return PlanningResearchDiscovery(outcome = PlanningOutcome.Unavailable)
        }

        val evidence = executions.flatMap { execution ->
            when (val outcome = execution.outcome) {
                is ReadToolOutcome.Success -> outcome.payload.evidence
                ReadToolOutcome.Empty,
                is ReadToolOutcome.MissingInput,
                is ReadToolOutcome.InvalidArguments,
                is ReadToolOutcome.Unavailable,
                -> emptyList()
            }
        }
        if (evidence.isEmpty()) {
            val outcome = executions.withoutEvidencePlanningOutcome()
            logPlanningResearchFinished(detail, executions, 0)
            return PlanningResearchDiscovery(outcome = outcome)
        }

        val opportunities = evidence.mapIndexedNotNull { index, item ->
            item.toPlanningOpportunityOrNull(
                index = index,
                referenceTime = now,
            )
        }
        logPlanningResearchFinished(detail, executions, opportunities.size)
        return PlanningResearchDiscovery(opportunities = opportunities)
    }

    private suspend fun composePlans(
        detail: TaskDetail,
        opportunities: List<Opportunity>,
        now: Instant,
        optionalContext: PlanningOptionalContext,
    ): List<AiPlanProposal> {
        val composer = planComposer ?: throw TaskDependencyUnavailableException("Planning is temporarily unavailable")
        return try {
            composer.compose(
                AiCreatePlansRequest(
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
        } catch (_: AiCapabilityException) {
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
                capability = StructuredModelCapability.CreatePlans,
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
        proposals: List<AiPlanProposal>,
    ): PlanningValidationDecision {
        val drafts = proposals.map { proposal ->
            PlanDraft(
                id = PlanId(uuidFactory()),
                direction = proposal.direction.toBackendDirection(),
                opportunityRefs = proposal.opportunityRefs.map { it.toOpportunityId() },
            )
        }
        val report = planValidator.validate(context, drafts)
        logger?.debug(
            component = PLANNING_LOG_COMPONENT,
            event = "plan_validation_finished",
            fields =
                logFields {
                    "task_id" value context.task.id.value.toString()
                    "task_revision" value context.task.revision
                    "plan_count" value report.plans.size
                    "failure_count" value report.failures.size
                    "failure_codes" value report.failures.joinToString(",") { it.code.name.toSnakeCase() }
                },
        )
        return PlanningValidationDecision(report.plans, report.failures)
    }

    private suspend fun explainPlans(
        requestId: String,
        plans: List<Plan>,
        opportunities: List<Opportunity>,
        now: Instant,
    ): List<Plan> {
        val explainer = planExplainer
            ?: return plans.also {
                logPlanningDegraded(requestId, plans.firstOrNull()?.revision, "explanation", "dependency_unavailable")
            }
        val explanationContext = AiExplainPlansRequest(
            planningRequestId = requestId,
            plans = plans.map { it.toAiPlanForExplanation(opportunities) },
            referenceTime = now.toContractInstant(),
            timeZoneId = timeZoneId,
        )
        val explanation = try {
            explainer.explain(explanationContext)
        } catch (error: CancellationException) {
            throw error
        } catch (_: AiCapabilityException) {
            logPlanningDegraded(requestId, plans.firstOrNull()?.revision, "explanation", "structured_model_exception")
            return plans
        }
        val factsByPlan = explanationContext.plans.associate { plan ->
            plan.planId to plan.facts.map { it.id }.toSet()
        }
        val narratives = explanation.narratives.associateBy { it.planId }
        return plans.map { plan ->
            val planId = plan.id.value.toString()
            val narrative = narratives[planId]
                ?: return@map plan.also {
                    logPlanningDegraded(requestId, plan.revision, "explanation", "missing_narrative")
                }
            if (narrative.hasInvalidFactRefs(factsByPlan.getValue(planId))) {
                return@map plan.also {
                    logPlanningDegraded(requestId, plan.revision, "explanation", "invalid_fact_reference")
                }
            }
            plan.copy(
                title = narrative.title,
                summary = narrative.summary,
                reasons = narrative.reasons.map { it.text },
                tradeoffs = narrative.tradeoffs.map { it.text },
            )
        }
    }

    private fun AiPlanNarrative.hasInvalidFactRefs(allowedFactIds: Set<String>): Boolean {
        val referenced = (reasons + tradeoffs).flatMap { it.factIds }
        return referenced.any { it !in allowedFactIds }
    }

    private fun logPlanningStarted(
        detail: TaskDetail,
        trigger: PlanningTrigger,
    ) {
        logger?.info(
            component = PLANNING_LOG_COMPONENT,
            event = "planning_started",
            fields =
                logFields {
                    "task_id" value detail.task.id.value.toString()
                    "task_revision" value detail.task.revision
                    "planning_trigger" value trigger.logValue
                },
        )
    }

    private fun logPlanningNotAttempted(
        detail: TaskDetail,
        trigger: PlanningTrigger,
        decision: PlanningDecision,
    ) {
        logger?.info(
            component = PLANNING_LOG_COMPONENT,
            event = "planning_not_attempted",
            fields =
                logFields {
                    "task_id" value detail.task.id.value.toString()
                    "task_revision" value detail.task.revision
                    "planning_trigger" value trigger.logValue
                    "planning_decision" value decision.logValue
                    "outcome" value PlanningOutcome.NotAttempted.logValue
                },
        )
    }

    private fun logPlanningFinished(
        detail: TaskDetail,
        startedAt: java.time.Instant,
        trigger: PlanningTrigger,
        outcome: PlanningOutcome,
        opportunityCount: Int,
        planCount: Int,
        validationFailures: List<PlanValidationFailure> = emptyList(),
    ) {
        logger?.info(
            component = PLANNING_LOG_COMPONENT,
            event = "planning_finished",
            fields =
                logFields {
                    "task_id" value detail.task.id.value.toString()
                    "task_revision" value detail.task.revision
                    "duration_ms" value Duration.between(startedAt, clock.instant()).toMillis().coerceAtLeast(0)
                    "planning_trigger" value trigger.logValue
                    "outcome" value outcome.logValue
                    "opportunity_count" value opportunityCount
                    "plan_count" value planCount
                    "validation_failure_count" value validationFailures.size
                    "validation_failure_codes" value validationFailures.joinToString(",") { it.code.name.toSnakeCase() }
                },
        )
    }

    private fun logPlanningUnavailable(
        detail: TaskDetail,
        startedAt: java.time.Instant,
        trigger: PlanningTrigger,
        stage: String,
        cause: Throwable,
    ) {
        logger?.warn(
            component = PLANNING_LOG_COMPONENT,
            event = "planning_unavailable",
            fields =
                logFields {
                    "task_id" value detail.task.id.value.toString()
                    "task_revision" value detail.task.revision
                    "duration_ms" value Duration.between(startedAt, clock.instant()).toMillis().coerceAtLeast(0)
                    "planning_trigger" value trigger.logValue
                    "stage" value stage
                    "outcome" value PlanningOutcome.Unavailable.logValue
                    "failure_category" value cause.safeFailureCategory()
                    "failure_reason" value (cause.message ?: "unavailable")
                },
        )
    }

    private fun logPlanningDegraded(
        requestId: String,
        taskRevision: Long?,
        stage: String,
        failureCategory: String,
    ) {
        logger?.warn(
            component = PLANNING_LOG_COMPONENT,
            event = "planning_degraded",
            fields =
                logFields {
                    "task_id" value requestId
                    "task_revision" value taskRevision
                    "stage" value stage
                    "failure_category" value failureCategory
                },
        )
    }

    private fun logPlanningResearchStarted(
        detail: TaskDetail,
        availableReadToolCount: Int,
    ) {
        logger?.info(
            component = PLANNING_LOG_COMPONENT,
            event = "planning_research_started",
            fields =
                logFields {
                    "task_id" value detail.task.id.value.toString()
                    "task_revision" value detail.task.revision
                    "available_read_tool_count" value availableReadToolCount
                },
        )
    }

    private fun logPlanningResearchFinished(
        detail: TaskDetail,
        executions: List<ReadToolExecution>,
        opportunityCount: Int,
    ) {
        logger?.info(
            component = PLANNING_LOG_COMPONENT,
            event = "planning_research_finished",
            fields =
                logFields {
                    "task_id" value detail.task.id.value.toString()
                    "task_revision" value detail.task.revision
                    "tool_count" value executions.size
                    "opportunity_count" value opportunityCount
                    "source_failure_count" value executions.count { it.outcome is ReadToolOutcome.Unavailable }
                    "read_tool_outcomes" value executions.joinToString(",") { execution ->
                        "${execution.call.key.value}:${execution.outcome.logValue()}"
                    }
                },
        )
    }

    private fun logPlanningFailed(
        detail: TaskDetail,
        startedAt: java.time.Instant,
        trigger: PlanningTrigger,
        stage: String,
        failureCategory: String,
        cause: Throwable? = null,
    ) {
        logger?.error(
            component = PLANNING_LOG_COMPONENT,
            event = "planning_failed",
            fields =
                logFields {
                    "task_id" value detail.task.id.value.toString()
                    "task_revision" value detail.task.revision
                    "duration_ms" value Duration.between(startedAt, clock.instant()).toMillis().coerceAtLeast(0)
                    "planning_trigger" value trigger.logValue
                    "stage" value stage
                    "failure_category" value failureCategory
                },
            cause = cause,
        )
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

    private fun String.toOpportunityId(): OpportunityId =
        try {
            OpportunityId(UUID.fromString(this))
        } catch (_: IllegalArgumentException) {
            throw TaskDependencyUnavailableException("Planning result is temporarily unavailable")
        }

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
            location = facts.location?.displayName,
            activityMode = when (facts.activityMode) {
                ActivityModeValue.AtHome -> "at_home"
                ActivityModeValue.OutOfHome -> "out_of_home"
                null -> "unknown"
            }.takeUnless { facts.activityMode == null },
            availability = facts.availability?.name,
            startsAt = facts.startTime?.toContractInstant(),
            endsAt = facts.endTime?.toContractInstant(),
            estimatedCostWholeUnits = facts.price?.wholeUnits,
            currencyCode = facts.price?.currencyCode,
            commuteMinutes = facts.commute?.minutes,
            sources = sources.take(MAX_AI_SOURCE_REFS).map { source ->
                AiCandidateSourceRef(
                    label = source.label,
                    uri = source.uri,
                    sourceUpdatedAt = source.sourceUpdatedAt?.toContractInstant(),
                    sourceId = source.sourceId,
                    authority = source.authority.name,
                    factKeys = source.factKeys.map { it.name }.sorted(),
                )
            },
            validUntil = validUntil?.toContractInstant(),
        )

    private fun Plan.toAiPlanForExplanation(opportunities: List<Opportunity>): AiPlanForExplanation {
        val opportunitiesById = opportunities.associateBy { it.id }
        val facts = opportunityRefs.flatMap { opportunityId ->
            val opportunity = opportunitiesById.getValue(opportunityId)
            listOf(
                AiPlanExplanationFact("opportunity:${opportunity.id.value}:title", "Opportunity title: ${opportunity.title}"),
                AiPlanExplanationFact("opportunity:${opportunity.id.value}:time", "Runs from ${opportunity.facts.startTime} to ${opportunity.facts.endTime}"),
                AiPlanExplanationFact("opportunity:${opportunity.id.value}:location", "Location: ${opportunity.facts.location?.displayName.orEmpty()}"),
                AiPlanExplanationFact(
                    "opportunity:${opportunity.id.value}:cost",
                    "Estimated cost: ${opportunity.facts.price?.wholeUnits?.toString() ?: "unknown"}",
                ),
                AiPlanExplanationFact(
                    "opportunity:${opportunity.id.value}:commute",
                    "Commute minutes: ${opportunity.facts.commute?.minutes?.toString() ?: "unknown"}",
                ),
                AiPlanExplanationFact(
                    "opportunity:${opportunity.id.value}:sources",
                    "Sources: ${
                        opportunity.sources
                            .take(MAX_AI_SOURCE_REFS)
                            .joinToString("; ") { source ->
                                listOf(
                                    source.label,
                                    source.authority.name,
                                    source.factKeys.joinToString(",") { it.name },
                                    source.sourceUpdatedAt?.toString().orEmpty(),
                                ).filter(String::isNotBlank).joinToString(" ")
                            }
                    }",
                ),
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

private fun List<ReadToolExecution>.withoutEvidencePlanningOutcome(): PlanningOutcome =
    when {
        any { it.outcome is ReadToolOutcome.Unavailable } -> PlanningOutcome.Unavailable
        isNotEmpty() && all { it.outcome == ReadToolOutcome.Empty } -> PlanningOutcome.NoCandidates
        any { it.outcome is ReadToolOutcome.MissingInput } -> PlanningOutcome.NoCandidates
        any { it.outcome is ReadToolOutcome.InvalidArguments } -> PlanningOutcome.Unavailable
        else -> PlanningOutcome.NoCandidates
    }

private fun ReadToolOutcome.logValue(): String =
    when (this) {
        is ReadToolOutcome.Success -> "success"
        ReadToolOutcome.Empty -> "empty"
        is ReadToolOutcome.MissingInput -> "missing_input"
        is ReadToolOutcome.InvalidArguments -> "invalid_arguments"
        is ReadToolOutcome.Unavailable -> "unavailable"
    }

private fun ReadToolEvidence.toPlanningOpportunityOrNull(
    index: Int,
    referenceTime: Instant,
): Opportunity? {
    val kind = sourceKey.toOpportunityKindOrNull() ?: return null
    val title = textFact(ReadToolFactKind.TITLE)?.takeIf(String::isNotBlank) ?: return null
    val start = timestampFact(ReadToolFactKind.START_TIME)
    val end = timestampFact(ReadToolFactKind.END_TIME)
    val location = textFact(ReadToolFactKind.LOCATION_NAME)?.let { LocationFact(it, it.lowercase()) }
    val price = moneyFact(ReadToolFactKind.PRICE)
    val commute = integerFact(ReadToolFactKind.COMMUTE_MINUTES)?.let { DurationFact(it.toInt()) }
    val availability = textFact(ReadToolFactKind.AVAILABILITY)?.toAvailabilityFactOrNull()
    val activityMode = textFact(ReadToolFactKind.ACTIVITY_MODE)?.toActivityModeValueOrNull()
    val summary = textFact(ReadToolFactKind.SUMMARY)
    return Opportunity(
        id = OpportunityId(UUID.nameUUIDFromBytes("planning-research:$sourceId:$index".toByteArray(StandardCharsets.UTF_8))),
        provider = sourceKey,
        externalKey = sourceId,
        kind = kind,
        title = title.take(MAX_RESEARCH_TITLE_CHARS),
        facts = OpportunityFacts(
            summary = summary?.take(MAX_RESEARCH_SUMMARY_CHARS),
            startTime = start,
            endTime = end,
            location = location,
            activityMode = activityMode,
            price = price,
            commute = commute,
            availability = availability,
            attributes = planningAttributes(kind, title, summary),
        ),
        sources = listOf(
            SourceRef(
                label = sourceKey,
                uri = sourceUrl,
                sourceUpdatedAt = sourceUpdatedAt,
                sourceId = sourceId,
                authority = authority.toPlanningSourceAuthority(),
                factKeys = sourceKey.toOpportunityFactKeys(),
            ),
        ),
        observedAt = referenceTime,
        validUntil = listOfNotNull(start, referenceTime.plus(Duration.ofHours(6)))
            .filter { it.isAfter(referenceTime) }
            .minOrNull(),
    )
}

private fun String.toOpportunityKindOrNull(): OpportunityKind? =
    when (this) {
        MovieShowtimesKey.value,
        -> OpportunityKind.Movies
        OutdoorTrailsKey.value,
        -> OpportunityKind.Outdoor
        SportsFixturesKey.value,
        SportsEventsKey.value,
        -> OpportunityKind.Sports
        MusicEventsKey.value,
        -> OpportunityKind.LiveEvents
        else -> null
    }

private fun String.toOpportunityFactKeys(): Set<OpportunityFactKey> =
    when (this) {
        MovieShowtimesKey.value -> setOf(OpportunityFactKey.MovieShowtime, OpportunityFactKey.Availability)
        MusicEventsKey.value,
        SportsEventsKey.value,
        -> setOf(OpportunityFactKey.LiveEventMetadata, OpportunityFactKey.Availability)
        SportsFixturesKey.value -> setOf(OpportunityFactKey.FixtureStatus, OpportunityFactKey.StartTime)
        OutdoorTrailsKey.value -> setOf(
            OpportunityFactKey.TrailMetadata,
            OpportunityFactKey.Location,
            OpportunityFactKey.Route,
            OpportunityFactKey.Weather,
        )
        else -> setOf(OpportunityFactKey.Summary)
    }

private fun ReadToolEvidence.planningAttributes(
    kind: OpportunityKind,
    title: String,
    summary: String?,
): Map<String, FactValue> {
    val topicTags = (
        listOfNotNull(kind.name, title, sourceKey, summary) +
            listOfNotNull(
                textFact(ReadToolFactKind.HOME_TEAM),
                textFact(ReadToolFactKind.AWAY_TEAM),
                textFact(ReadToolFactKind.COMPETITION),
                textFact(ReadToolFactKind.ARTISTS),
            )
    )
        .flatMap { it.split(',', '/', '|') }
        .map { it.planningTopicToken() }
        .filter(String::isNotBlank)
        .distinct()
    val attributes = mutableMapOf<String, FactValue>()
    if (topicTags.isNotEmpty()) {
        attributes["topics"] = FactValue.Text(topicTags.joinToString(","))
    }
    integerFact(ReadToolFactKind.DISTANCE_METERS)?.let { attributes["distanceMeters"] = FactValue.Number(it) }
    integerFact(ReadToolFactKind.ELEVATION_GAIN_METERS)?.let { attributes["elevationGainMeters"] = FactValue.Number(it) }
    return attributes
}

private fun ReadToolEvidence.textFact(kind: ReadToolFactKind): String? =
    facts.firstOrNull { it.kind == kind }?.value?.let { value ->
        when (value) {
            is ReadToolFactValue.Text -> value.value
            else -> null
        }
    }

private fun ReadToolEvidence.integerFact(kind: ReadToolFactKind): Long? =
    facts.firstOrNull { it.kind == kind }?.value?.let { value ->
        when (value) {
            is ReadToolFactValue.Integer -> value.value
            else -> null
        }
    }

private fun ReadToolEvidence.timestampFact(kind: ReadToolFactKind): Instant? =
    facts.firstOrNull { it.kind == kind }?.value?.let { value ->
        when (value) {
            is ReadToolFactValue.Timestamp -> value.value
            else -> null
        }
    }

private fun ReadToolEvidence.moneyFact(kind: ReadToolFactKind): MoneyFact? =
    facts.firstOrNull { it.kind == kind }?.value?.let { value ->
        when (value) {
            is ReadToolFactValue.Money -> MoneyFact(value.wholeUnits, value.currencyCode)
            else -> null
        }
    }

private fun String.toAvailabilityFactOrNull(): AvailabilityFact? =
    AvailabilityFact.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }

private fun String.toActivityModeValueOrNull(): ActivityModeValue? =
    ActivityModeValue.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }

private fun String.planningTopicToken(): String =
    trim()
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

private fun ReadToolSourceAuthority.toPlanningSourceAuthority(): SourceAuthority =
    when (this) {
        ReadToolSourceAuthority.StructuredPrimary -> SourceAuthority.StructuredPrimary
        ReadToolSourceAuthority.StructuredSecondary -> SourceAuthority.StructuredSecondary
        ReadToolSourceAuthority.OfficialWeb -> SourceAuthority.OfficialWeb
        ReadToolSourceAuthority.GeneralWeb -> SourceAuthority.GeneralWeb
    }

data class PlanningAttemptResult(
    val detail: TaskDetail,
    val outcome: PlanningOutcome,
)

data class PlanningComputationResult(
    val expectedTaskId: TaskId,
    val expectedTaskRevision: Long,
    val opportunities: List<Opportunity>,
    val plans: List<Plan>,
    val outcome: PlanningOutcome,
)

enum class PlanningTrigger {
    PlanningInputChanged,
    ExplicitUserRequest,
}

enum class PlanningOutcome {
    NotAttempted,
    Ready,
    NoCandidates,
    NoFeasiblePlan,
    Unavailable,
    Superseded,
}

class PlanningReadinessPolicy {
    fun decide(
        detail: TaskDetail,
        trigger: PlanningTrigger = PlanningTrigger.PlanningInputChanged,
    ): PlanningDecision {
        if (detail.requirements.isEmpty()) {
            return PlanningDecision.MissingPlanningInputs
        }
        if (trigger == PlanningTrigger.ExplicitUserRequest) {
            return PlanningDecision.Plan
        }
        return if (detail.plans.none { it.revision == detail.task.revision }) {
            PlanningDecision.Plan
        } else {
            PlanningDecision.KeepCurrentPlans
        }
    }
}

enum class PlanningDecision {
    KeepCurrentPlans,
    MissingPlanningInputs,
    Plan,
}

private data class PlanningOptionalContext(
    val blocks: List<ModelContextBlock> = emptyList(),
    val diagnostics: StructuredModelRequestDiagnostics = StructuredModelRequestDiagnostics(),
)

private data class PlanningValidationDecision(
    val plans: List<Plan>,
    val failures: List<PlanValidationFailure>,
)

private data class PlanningResearchDiscovery(
    val opportunities: List<Opportunity> = emptyList(),
    val outcome: PlanningOutcome? = null,
)

private const val WRITE_SCOPE = "orbit.tasks.write"
private const val PLANNING_LOG_COMPONENT = "planning"
private const val MAX_AI_SOURCE_REFS = 5
private const val MAX_RESEARCH_TITLE_CHARS = 120
private const val MAX_RESEARCH_SUMMARY_CHARS = 700

private fun Throwable.safeFailureCategory(): String =
    (this::class.simpleName ?: "Throwable").toSnakeCase()

private fun List<PlanValidationFailure>.toPlanningOutcome(): PlanningOutcome =
    if (isNotEmpty() && all { it.code.isFeasibilityFailure }) {
        PlanningOutcome.NoFeasiblePlan
    } else {
        PlanningOutcome.Unavailable
    }

private val PlanningOutcome.logValue: String
    get() = name.toSnakeCase()

private val PlanningTrigger.logValue: String
    get() = name.toSnakeCase()

private val PlanningDecision.logValue: String
    get() = name.toSnakeCase()

private fun String.toSnakeCase(): String =
    buildString(length + 4) {
        this@toSnakeCase.forEachIndexed { index, character ->
            if (character.isUpperCase() && index > 0) append('_')
            append(character.lowercaseChar())
        }
    }
