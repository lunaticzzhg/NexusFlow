package com.nexusflow.backend.feature.responserun.domain

import com.nexusflow.backend.feature.conversation.domain.ConversationId
import com.nexusflow.backend.feature.conversation.domain.MessageId
import com.nexusflow.backend.feature.task.domain.TaskId
import java.time.Instant
import java.util.UUID

@JvmInline
value class ResponseRunId(val value: UUID)

enum class ResponseRunStatus {
    Queued,
    Processing,
    Streaming,
    Completed,
    FailedRetryable,
    Failed,
    TimedOut,
    Cancelled,
}

enum class ResponseRunStage {
    Turn,
    Planning,
}

enum class ResponseRunFailureCategory {
    ProviderTemporary,
    AiInvalidResult,
    WorkerLost,
    RunTimeout,
    InternalInvariant,
}

data class ResponseRun(
    val id: ResponseRunId,
    val conversationId: ConversationId,
    val userMessageId: MessageId,
    val turnIndex: Long,
    val status: ResponseRunStatus,
    val stage: ResponseRunStage,
    val attempt: Int,
    val availableAt: Instant,
    val leaseOwner: String?,
    val leaseExpiresAt: Instant?,
    val deadlineAt: Instant,
    val expectedTaskId: TaskId?,
    val expectedTaskRevision: Long?,
    val assistantMessageId: MessageId?,
    val failureCategory: ResponseRunFailureCategory?,
    val originTraceId: String?,
    val createdAt: Instant,
    val startedAt: Instant?,
    val updatedAt: Instant,
    val completedAt: Instant?,
)
