package com.nexusflow.contracts.api

import com.nexusflow.contracts.planning.PlanProposal
import com.nexusflow.contracts.task.TaskEvent
import com.nexusflow.contracts.task.TaskStatus
import java.time.Instant
import java.time.ZoneId

/** The path prefix used by every externally consumable HTTP endpoint. */
object ApiVersion {
    const val V1 = "v1"
}

/**
 * Identity is deliberately absent: API services derive the tenant and actor from a verified token.
 */
data class CreateTaskRequest(
    val requestText: String,
    val timezone: String,
    val conversationId: String? = null,
    val sourceDiscoveryId: String? = null,
    val constraints: List<TaskConstraintInput> = emptyList(),
) {
    init {
        require(requestText.isNotBlank()) { "requestText must not be blank" }
        require(requestText.length <= MAX_REQUEST_TEXT_LENGTH) { "requestText is too long" }
        require(timezone in ZoneId.getAvailableZoneIds()) { "timezone must be a valid IANA zone ID" }
    }

    companion object {
        const val MAX_REQUEST_TEXT_LENGTH = 4_000
    }
}

data class TaskConstraintInput(
    val key: String,
    val value: String,
    val source: ConstraintSource,
) {
    init {
        require(key.isNotBlank()) { "constraint key must not be blank" }
        require(value.isNotBlank()) { "constraint value must not be blank" }
    }
}

enum class ConstraintSource {
    USER_EXPLICIT,
    USER_CONFIRMED_PROFILE,
}

data class TaskSummaryResponse(
    val taskId: String,
    val status: TaskStatus,
    val title: String,
    val updatedAt: Instant,
)

data class TaskDetailResponse(
    val taskId: String,
    val status: TaskStatus,
    val requestText: String,
    val version: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val proposal: PlanProposal? = null,
    val pendingApproval: PendingApprovalResponse? = null,
)

data class PendingApprovalResponse(
    val approvalId: String,
    val taskVersion: Long,
    val actionCount: Int,
    val expiresAt: Instant?,
)

data class TaskEventsResponse(
    val items: List<TaskEvent>,
    val nextCursor: String? = null,
)

/** Standard, versioned error body returned by all API services. */
data class ApiErrorResponse(
    val code: ApiErrorCode,
    val message: String,
    val traceId: String,
    val details: List<ApiFieldViolation> = emptyList(),
) {
    init {
        require(message.isNotBlank()) { "message must not be blank" }
        require(traceId.isNotBlank()) { "traceId must not be blank" }
    }
}

data class ApiFieldViolation(
    val field: String,
    val reason: String,
)

enum class ApiErrorCode {
    VALIDATION_FAILED,
    UNAUTHENTICATED,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    IDEMPOTENCY_CONFLICT,
    TASK_STATE_CONFLICT,
    RATE_LIMITED,
    DEPENDENCY_UNAVAILABLE,
    INTERNAL_ERROR,
}
