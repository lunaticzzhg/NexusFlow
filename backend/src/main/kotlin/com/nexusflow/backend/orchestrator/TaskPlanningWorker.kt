package com.nexusflow.backend.orchestrator

import com.nexusflow.ai.planner.OpportunitySnapshot
import com.nexusflow.ai.planner.PlanOption as AiPlanOption
import com.nexusflow.ai.planner.Planner
import com.nexusflow.ai.planner.PlanningContext
import com.nexusflow.ai.planner.PlanningResult
import com.nexusflow.ai.planner.RequestedActionType
import com.nexusflow.backend.application.TaskRepository
import com.nexusflow.backend.domain.TaskAggregate
import com.nexusflow.contracts.planning.ActionRequest
import com.nexusflow.contracts.planning.ActionType
import com.nexusflow.contracts.planning.ModelRunMetadata
import com.nexusflow.contracts.planning.OpportunityDomain
import com.nexusflow.contracts.planning.PlanItem
import com.nexusflow.contracts.planning.PlanOption
import com.nexusflow.contracts.planning.PlanProposal
import com.nexusflow.contracts.planning.SourceReference
import com.nexusflow.contracts.task.TaskEvent
import com.nexusflow.contracts.task.TaskEventType
import com.nexusflow.contracts.task.TaskStatus
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Read-only boundary; tool implementations never receive write capabilities here. */
fun interface OpportunityProvider {
    fun findCandidates(task: TaskAggregate): List<OpportunitySnapshot>
}

/** Allows the in-process stub to be replaced by a remote AI service without changing orchestration. */
fun interface PlanningEngine {
    fun plan(context: PlanningContext): PlanningResult
}

class InProcessPlanningEngine(private val planner: Planner = Planner()) : PlanningEngine {
    override fun plan(context: PlanningContext): PlanningResult = planner.plan(context)
}

/**
 * Consumes a queued task after an outbox/Kafka delivery. It is deliberately
 * idempotent: a replay of an already progressed task does no work.
 */
class TaskPlanningWorker(
    private val repository: TaskRepository,
    private val opportunities: OpportunityProvider,
    private val planningEngine: PlanningEngine,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun process(taskId: String, correlationId: String): TaskStatus? {
        var task = repository.findById(taskId) ?: return null
        if (task.status != TaskStatus.QUEUED) return task.status

        task = transition(task, TaskStatus.GATHERING_CONTEXT, TaskEventType.CONTEXT_GATHERING_STARTED, correlationId)
        val candidates = opportunities.findCandidates(task)
        task = transition(task, TaskStatus.PLANNING, TaskEventType.PLAN_GENERATION_STARTED, correlationId)

        return when (val result = planningEngine.plan(
            PlanningContext(
                taskId = task.id,
                request = task.request.requestText,
                timezone = task.request.timezone,
                opportunities = candidates,
            ),
        )) {
            is PlanningResult.Accepted -> {
                val proposal = result.proposal.toContract(task.id, candidates, clock.instant())
                val beforeValidation = task
                task = beforeValidation.attachProposal(proposal).transitionTo(TaskStatus.VALIDATING, clock.instant())
                task = repository.updateWithOutbox(
                    expectedVersion = beforeValidation.version,
                    task = task,
                    event = taskEvent(task, TaskEventType.PLAN_PROPOSED, correlationId),
                )
                val beforeValidationExit = task
                task = beforeValidationExit.afterValidation(clock.instant())
                repository.updateWithOutbox(
                    expectedVersion = beforeValidationExit.version,
                    task = task,
                    event = taskEvent(
                        task,
                        if (task.status == TaskStatus.AWAITING_APPROVAL) TaskEventType.APPROVAL_REQUIRED else TaskEventType.TASK_COMPLETED,
                        correlationId,
                    ),
                ).status
            }
            is PlanningResult.Rejected -> {
                transition(
                    task,
                    TaskStatus.FAILED,
                    TaskEventType.TASK_FAILED,
                    correlationId,
                    mapOf("violations" to result.violations.joinToString(",") { it.code.name }),
                ).status
            }
        }
    }

    private fun transition(
        task: TaskAggregate,
        next: TaskStatus,
        eventType: TaskEventType,
        correlationId: String,
        payload: Map<String, String> = emptyMap(),
    ): TaskAggregate {
        val updated = task.transitionTo(next, clock.instant())
        return repository.updateWithOutbox(
            expectedVersion = task.version,
            task = updated,
            event = taskEvent(updated, eventType, correlationId, payload),
        )
    }

    private fun taskEvent(
        task: TaskAggregate,
        type: TaskEventType,
        correlationId: String,
        payload: Map<String, String> = emptyMap(),
    ) = TaskEvent(
        eventId = UUID.randomUUID().toString(),
        taskId = task.id,
        tenantId = task.tenantId,
        type = type,
        occurredAt = task.updatedAt,
        correlationId = correlationId,
        payload = payload + ("taskVersion" to task.version.toString()),
    )
}

private fun com.nexusflow.ai.planner.PlanProposal.toContract(
    taskId: String,
    candidates: List<OpportunitySnapshot>,
    now: Instant,
): PlanProposal = PlanProposal(
    taskId = taskId,
    title = title,
    summary = rationale.joinToString("；"),
    generatedAt = now,
    options = options.mapIndexed { index, option -> option.toContract(index + 1, candidates, now) },
    modelRun = ModelRunMetadata(provider = "stub", model = "deterministic", promptVersion = "v1"),
)

private fun AiPlanOption.toContract(
    rank: Int,
    candidates: List<OpportunitySnapshot>,
    now: Instant,
): PlanOption {
    val referenced = referencedOpportunityIds.mapNotNull { id -> candidates.firstOrNull { it.id == id } }
    val itemCandidates = referenced.ifEmpty {
        listOf(
            OpportunitySnapshot(
                id = "task-context-$rank",
                title = title,
                category = com.nexusflow.ai.planner.OpportunityCategory.OTHER,
                startsAt = now.plus(Duration.ofDays(1)),
                sourceName = "Orbit task context",
                sourceUrl = "https://orbit.local/context",
            ),
        )
    }
    return PlanOption(
        optionId = id,
        rank = rank,
        title = title,
        summary = summary,
        items = itemCandidates.map { candidate ->
            PlanItem(
                itemId = candidate.id,
                title = candidate.title,
                domain = candidate.category.toContractDomain(),
                startAt = candidate.startsAt ?: now.plus(Duration.ofDays(1)),
                endAt = (candidate.startsAt ?: now.plus(Duration.ofDays(1))).plus(Duration.ofHours(2)),
                sources = listOf(
                    SourceReference(
                        sourceId = candidate.sourceName.lowercase().replace(" ", "-"),
                        label = candidate.sourceName,
                        url = candidate.sourceUrl ?: "https://orbit.local/source",
                        retrievedAt = now,
                    ),
                ),
            )
        },
        rationale = listOf(summary),
        actionRequests = requestedActions.mapIndexed { actionIndex, action ->
            ActionRequest(
                actionId = "$id-action-${actionIndex + 1}",
                type = action.type.toContractType(),
                summary = action.displayName,
            )
        },
    )
}

private fun com.nexusflow.ai.planner.OpportunityCategory.toContractDomain(): OpportunityDomain = when (this) {
    com.nexusflow.ai.planner.OpportunityCategory.SPORT -> OpportunityDomain.SPORTS
    com.nexusflow.ai.planner.OpportunityCategory.MOVIE -> OpportunityDomain.MOVIES
    com.nexusflow.ai.planner.OpportunityCategory.LOCAL_EVENT -> OpportunityDomain.LOCAL_EVENTS
    else -> OpportunityDomain.LOCAL_EVENTS
}

private fun RequestedActionType.toContractType(): ActionType = when (this) {
    RequestedActionType.CREATE_CALENDAR_EVENT -> ActionType.CREATE_CALENDAR_EVENT
    RequestedActionType.CREATE_REMINDER -> ActionType.CREATE_REMINDER
    RequestedActionType.OPEN_EXTERNAL_LINK -> ActionType.SEND_NOTIFICATION
}
