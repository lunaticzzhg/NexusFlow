package com.nexusflow.contracts.task

import java.time.Instant

/** Persisted task states. Only the orchestration domain may advance this state machine. */
enum class TaskStatus {
    QUEUED,
    GATHERING_CONTEXT,
    PLANNING,
    VALIDATING,
    AWAITING_APPROVAL,
    EXECUTING,
    COMPLETED,
    RETRYING,
    FAILED,
    CANCELLED,
}

object TaskLifecycle {
    private val transitions: Map<TaskStatus, Set<TaskStatus>> = mapOf(
        TaskStatus.QUEUED to setOf(TaskStatus.GATHERING_CONTEXT, TaskStatus.CANCELLED, TaskStatus.FAILED),
        TaskStatus.GATHERING_CONTEXT to setOf(TaskStatus.PLANNING, TaskStatus.RETRYING, TaskStatus.FAILED, TaskStatus.CANCELLED),
        TaskStatus.PLANNING to setOf(TaskStatus.VALIDATING, TaskStatus.RETRYING, TaskStatus.FAILED, TaskStatus.CANCELLED),
        TaskStatus.VALIDATING to setOf(TaskStatus.AWAITING_APPROVAL, TaskStatus.COMPLETED, TaskStatus.RETRYING, TaskStatus.FAILED, TaskStatus.CANCELLED),
        TaskStatus.AWAITING_APPROVAL to setOf(TaskStatus.EXECUTING, TaskStatus.CANCELLED, TaskStatus.FAILED),
        TaskStatus.EXECUTING to setOf(TaskStatus.COMPLETED, TaskStatus.RETRYING, TaskStatus.FAILED),
        TaskStatus.RETRYING to setOf(TaskStatus.GATHERING_CONTEXT, TaskStatus.PLANNING, TaskStatus.EXECUTING, TaskStatus.FAILED, TaskStatus.CANCELLED),
        TaskStatus.COMPLETED to emptySet(),
        TaskStatus.FAILED to emptySet(),
        TaskStatus.CANCELLED to emptySet(),
    )

    fun canTransition(from: TaskStatus, to: TaskStatus): Boolean = to in transitions.getValue(from)

    fun requireTransition(from: TaskStatus, to: TaskStatus) {
        require(canTransition(from, to)) { "Illegal task transition: $from -> $to" }
    }
}

enum class TaskEventType {
    TASK_CREATED,
    CONTEXT_GATHERING_STARTED,
    CONTEXT_GATHERED,
    PLAN_GENERATION_STARTED,
    PLAN_PROPOSED,
    APPROVAL_REQUIRED,
    APPROVAL_DECIDED,
    EXECUTION_STARTED,
    TASK_COMPLETED,
    RETRY_SCHEDULED,
    TASK_FAILED,
    TASK_CANCELLED,
}

/**
 * Envelope published through the outbox and event stream. Payload values stay strings so the
 * envelope remains transport-neutral; event-specific details and execution idempotency belong in
 * the referenced task aggregate, not in AI-facing contracts.
 */
data class TaskEvent(
    val eventId: String,
    val eventVersion: Int = 1,
    val taskId: String,
    val tenantId: String,
    val type: TaskEventType,
    val occurredAt: Instant,
    val correlationId: String,
    val causationId: String? = null,
    val payload: Map<String, String> = emptyMap(),
) {
    init {
        require(eventId.isNotBlank()) { "eventId must not be blank" }
        require(eventVersion > 0) { "eventVersion must be positive" }
        require(taskId.isNotBlank()) { "taskId must not be blank" }
        require(tenantId.isNotBlank()) { "tenantId must not be blank" }
        require(correlationId.isNotBlank()) { "correlationId must not be blank" }
    }
}
