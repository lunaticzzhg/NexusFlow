package com.nexusflow.backend.feature.task.application

import com.nexusflow.backend.core.aicontext.ModelContextAssembler
import com.nexusflow.backend.core.identity.ActorContext
import com.nexusflow.backend.feature.research.application.ReadToolCatalog
import com.nexusflow.backend.feature.research.application.ReadToolExecutionObserver
import com.nexusflow.backend.feature.research.application.ReadToolExecutor
import com.nexusflow.backend.feature.task.domain.PersistPlansCommand
import com.nexusflow.backend.feature.task.domain.PersistPlansResult
import com.nexusflow.backend.feature.task.domain.PlanValidator
import com.nexusflow.backend.feature.task.domain.TaskDetail
import com.nexusflow.backend.feature.task.domain.TaskOwner
import com.nexusflow.backend.feature.task.domain.TaskRepository
import com.nexusflow.contracts.backendai.planning.PlanComposer
import com.nexusflow.contracts.backendai.planning.PlanExplainer
import com.nexusflow.contracts.backendai.planning.PlanningResearchCapability
import com.nexusflow.observability.StructuredLogger
import java.time.Clock
import java.time.DateTimeException
import java.time.ZoneId
import java.util.UUID

class PlanningService(
    private val repository: TaskRepository,
    planValidator: PlanValidator,
    planningResearch: PlanningResearchCapability? = null,
    readToolCatalog: ReadToolCatalog = ReadToolCatalog(emptyList()),
    readToolExecutor: ReadToolExecutor = ReadToolExecutor(readToolCatalog),
    planComposer: PlanComposer? = null,
    planExplainer: PlanExplainer? = null,
    modelContextAssembler: ModelContextAssembler? = null,
    readinessPolicy: PlanningReadinessPolicy = PlanningReadinessPolicy(),
    logger: StructuredLogger? = null,
    private val clock: Clock = Clock.systemUTC(),
    uuidFactory: () -> UUID = UUID::randomUUID,
    timeZoneId: String = "UTC",
) {
    init {
        try {
            ZoneId.of(timeZoneId)
        } catch (_: DateTimeException) {
            error("PlanningService timeZoneId is invalid")
        }
    }

    private val planningLogger = PlanningLogger(logger, clock)
    private val workflow = PlanningWorkflow(
        readiness = readinessPolicy,
        optionalContextResolver = PlanningOptionalContextResolver(modelContextAssembler),
        opportunityDiscovery = OpportunityDiscovery(
            planningResearch = planningResearch,
            readToolCatalog = readToolCatalog,
            readToolExecutor = readToolExecutor,
            timeZoneId = timeZoneId,
            logger = planningLogger,
        ),
        planGenerator = PlanGenerator(
            planComposer = planComposer,
            timeZoneId = timeZoneId,
        ),
        planValidation = PlanValidationStep(
            planValidator = planValidator,
            uuidFactory = uuidFactory,
            logger = planningLogger,
        ),
        planExplainer = PlanExplanationService(
            planExplainer = planExplainer,
            timeZoneId = timeZoneId,
            logger = planningLogger,
        ),
        logger = planningLogger,
        clock = clock,
    )
    private val selectionService = PlanSelectionService(repository, clock)

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
                planningLogger.planningSupersededOnPersist(detail, trigger)
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
    ): PlanningComputationResult =
        workflow.computeIfReady(
            actor = actor,
            detail = detail,
            trigger = trigger,
            readToolObserver = readToolObserver,
        )

    suspend fun selectPlan(
        actor: ActorContext,
        taskId: String,
        planId: String,
    ): TaskDetail =
        selectionService.selectPlan(actor, taskId, planId)
}
