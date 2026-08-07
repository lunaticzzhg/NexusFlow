package com.nexusflow.backend.domain

import com.nexusflow.contracts.api.CreateTaskRequest
import com.nexusflow.contracts.planning.PlanProposal
import com.nexusflow.contracts.task.TaskLifecycle
import com.nexusflow.contracts.task.TaskStatus
import com.nexusflow.contracts.task.TaskTransitionPolicy
import java.time.Instant

data class TaskAggregate(
    val id: String,
    val tenantId: String,
    val ownerUserId: String,
    val request: CreateTaskRequest,
    val status: TaskStatus,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val idempotencyKey: String,
    val requestFingerprint: String,
    val proposal: PlanProposal? = null,
) {
    fun transitionTo(nextStatus: TaskStatus, at: Instant): TaskAggregate {
        require(!(status == TaskStatus.VALIDATING && nextStatus in setOf(TaskStatus.AWAITING_APPROVAL, TaskStatus.COMPLETED))) {
            "Use afterValidation() so action-bearing proposals cannot bypass approval"
        }
        TaskLifecycle.requireTransition(status, nextStatus)
        return copy(status = nextStatus, version = version + 1, updatedAt = at)
    }

    fun attachProposal(proposal: PlanProposal): TaskAggregate = copy(proposal = proposal)

    /** The only legal path out of validation for a proposal-bearing task. */
    fun afterValidation(at: Instant): TaskAggregate {
        require(status == TaskStatus.VALIDATING) { "Task must be VALIDATING" }
        val nextStatus = TaskTransitionPolicy.afterValidation(requireNotNull(proposal) { "Validated task requires a proposal" })
        TaskLifecycle.requireTransition(status, nextStatus)
        return copy(status = nextStatus, version = version + 1, updatedAt = at)
    }
}
