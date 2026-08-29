package com.nexusflow.backend.feature.task.domain

import java.time.Instant
import java.util.UUID

@JvmInline
value class TenantId(val value: UUID)

@JvmInline
value class UserId(val value: UUID)

@JvmInline
value class TaskId(val value: UUID)

@JvmInline
value class ConversationId(val value: UUID)

@JvmInline
value class MessageId(val value: UUID)

@JvmInline
value class ConstraintId(val value: UUID)

@JvmInline
value class PlanningRunId(val value: UUID)

@JvmInline
value class PlanId(val value: UUID)

data class TaskOwner(
    val tenantId: TenantId,
    val userId: UserId,
)

enum class TaskState {
    Draft,
    CollectingConstraints,
    Planning,
    WaitingForApproval,
    Executing,
    NeedsAttention,
    Completed,
    Cancelled,
}

fun TaskState.canTransitionTo(next: TaskState): Boolean =
    when (this) {
        TaskState.Draft -> next == TaskState.CollectingConstraints || next == TaskState.Planning
        TaskState.CollectingConstraints -> next == TaskState.CollectingConstraints || next == TaskState.Planning
        TaskState.Planning -> next == TaskState.CollectingConstraints || next == TaskState.WaitingForApproval
        TaskState.WaitingForApproval,
        TaskState.Executing,
        TaskState.NeedsAttention,
        TaskState.Completed,
        TaskState.Cancelled,
        -> false
    }

data class Task(
    val id: TaskId,
    val owner: TaskOwner,
    val creationRequestId: String,
    val initialGoal: String,
    val currentGoal: String,
    val title: String,
    val state: TaskState,
    val version: Long,
    val selectedPlanId: PlanId?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class TaskDetail(
    val task: Task,
    val conversation: Conversation,
    val messages: List<ConversationMessage>,
    val constraints: List<TaskConstraint>,
    val planningRuns: List<PlanningRun>,
    val plans: List<Plan>,
)

fun createTaskTitle(goal: String): String {
    val trimmed = goal.trim()
    return if (trimmed.length <= TASK_TITLE_MAX_LENGTH) {
        trimmed
    } else {
        trimmed.take(TASK_TITLE_MAX_LENGTH).trimEnd()
    }
}

private const val TASK_TITLE_MAX_LENGTH = 48
