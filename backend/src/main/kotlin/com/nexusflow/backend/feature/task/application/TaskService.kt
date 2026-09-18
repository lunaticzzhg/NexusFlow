package com.nexusflow.backend.feature.task.application

import com.nexusflow.backend.core.identity.ActorContext
import com.nexusflow.backend.feature.task.domain.DeleteRequirementCommand
import com.nexusflow.backend.feature.task.domain.RequirementId
import com.nexusflow.backend.feature.task.domain.RequirementKind
import com.nexusflow.backend.feature.task.domain.RequirementMutationResult
import com.nexusflow.backend.feature.task.domain.RequirementStrength
import com.nexusflow.backend.feature.task.domain.RequirementValue
import com.nexusflow.backend.feature.task.domain.TaskDetail
import com.nexusflow.backend.feature.task.domain.TaskId
import com.nexusflow.backend.feature.task.domain.TaskOwner
import com.nexusflow.backend.feature.task.domain.TaskRepository
import com.nexusflow.backend.feature.task.domain.TenantId
import com.nexusflow.backend.feature.task.domain.UpdateRequirementCommand
import com.nexusflow.backend.feature.task.domain.UserId
import java.time.Clock
import java.util.UUID

class TaskService(
    private val repository: TaskRepository,
    private val planningService: PlanningService,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun listTasks(actor: ActorContext): List<TaskDetail> {
        actor.requireScope(READ_SCOPE)
        return repository.listTaskSummaries(actor.taskOwner())
    }

    suspend fun getTask(
        actor: ActorContext,
        taskId: String,
    ): TaskDetail {
        actor.requireScope(READ_SCOPE)
        val owner = actor.taskOwner()
        return repository.findTaskDetail(owner, taskId.toTaskId()) ?: throw TaskNotFoundException()
    }

    suspend fun updateRequirement(
        actor: ActorContext,
        taskId: String,
        requirementId: String,
        kind: RequirementKind,
        value: RequirementValue,
        strength: RequirementStrength,
    ): TaskMutationResult {
        actor.requireScope(WRITE_SCOPE)
        val owner = actor.taskOwner()
        val detail = when (
            val result = repository.updateRequirement(
                UpdateRequirementCommand(
                    owner = owner,
                    taskId = taskId.toTaskId(),
                    requirementId = requirementId.toRequirementId(),
                    kind = kind,
                    value = value,
                    strength = strength,
                    now = clock.instant(),
                ),
            )
        ) {
            is RequirementMutationResult.Mutated -> result.detail
            RequirementMutationResult.RequirementNotFound,
            RequirementMutationResult.TaskNotFound,
            -> throw TaskNotFoundException()
        }
        return planningService.planIfReady(actor, owner, detail).toMutationResult()
    }

    suspend fun deleteRequirement(
        actor: ActorContext,
        taskId: String,
        requirementId: String,
    ): TaskMutationResult {
        actor.requireScope(WRITE_SCOPE)
        val owner = actor.taskOwner()
        val detail = when (
            val result = repository.deleteRequirement(
                DeleteRequirementCommand(
                    owner = owner,
                    taskId = taskId.toTaskId(),
                    requirementId = requirementId.toRequirementId(),
                    now = clock.instant(),
                ),
            )
        ) {
            is RequirementMutationResult.Mutated -> result.detail
            RequirementMutationResult.RequirementNotFound,
            RequirementMutationResult.TaskNotFound,
            -> throw TaskNotFoundException()
        }
        return planningService.planIfReady(actor, owner, detail).toMutationResult()
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

    private fun String.toRequirementId(): RequirementId = RequirementId(toUuid("requirementId"))

    private fun String.toUuid(fieldName: String): UUID =
        try {
            UUID.fromString(this)
        } catch (_: IllegalArgumentException) {
            throw InvalidTaskRequestException("$fieldName is invalid")
        }
}

data class TaskMutationResult(
    val detail: TaskDetail,
    val planningOutcome: PlanningOutcome = PlanningOutcome.NotAttempted,
    val terminalKind: TaskTurnTerminalKind = planningOutcome.toTerminalKind(),
    val planningTriggered: Boolean = planningOutcome != PlanningOutcome.NotAttempted,
) {
    val task get() = detail.task
    val requirements get() = detail.requirements
    val plans get() = detail.plans
}

enum class TaskTurnTerminalKind {
    None,
    Planning,
}

sealed class TaskServiceException(message: String) : RuntimeException(message)

class MissingTaskScopeException : TaskServiceException("Missing required task scope")

class InvalidTaskRequestException(message: String) : TaskServiceException(message)

class TaskNotFoundException : TaskServiceException("Task was not found")

class TaskConflictException : TaskServiceException("Task request conflicts with existing state")

class InvalidTaskOperationException : TaskServiceException("Task operation is not allowed")

class TaskDependencyUnavailableException(message: String) : TaskServiceException(message)

private const val READ_SCOPE = "orbit.tasks.read"
private const val WRITE_SCOPE = "orbit.tasks.write"

private fun PlanningAttemptResult.toMutationResult(): TaskMutationResult =
    TaskMutationResult(detail, outcome)

private fun PlanningOutcome.toTerminalKind(): TaskTurnTerminalKind =
    when (this) {
        PlanningOutcome.NotAttempted -> TaskTurnTerminalKind.None
        PlanningOutcome.Ready,
        PlanningOutcome.NoCandidates,
        PlanningOutcome.NoFeasiblePlan,
        PlanningOutcome.Unavailable,
        PlanningOutcome.Superseded,
        -> TaskTurnTerminalKind.Planning
    }
