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
value class MessageId(val value: UUID)

@JvmInline
value class RequirementId(val value: UUID)

@JvmInline
value class ProfilePreferenceId(val value: UUID)

@JvmInline
value class PlanId(val value: UUID)

@JvmInline
value class OpportunityId(val value: UUID)

data class TaskOwner(
    val tenantId: TenantId,
    val userId: UserId,
)

data class Task(
    val id: TaskId,
    val owner: TaskOwner,
    val creationRequestId: String,
    val intent: String,
    val revision: Long,
    val selectedPlanId: PlanId?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val archivedAt: Instant? = null,
)

data class TaskDetail(
    val task: Task,
    val messages: List<TaskMessage>,
    val requirements: List<Requirement>,
    val plans: List<Plan>,
    val selectedContextKeys: List<String> = emptyList(),
)

fun createTaskTitle(intent: String): String {
    val trimmed = intent.trim()
    return if (trimmed.length <= TASK_TITLE_MAX_LENGTH) {
        trimmed
    } else {
        trimmed.take(TASK_TITLE_MAX_LENGTH).trimEnd()
    }
}

private const val TASK_TITLE_MAX_LENGTH = 48
