package com.nexusflow.backend.feature.responserun.domain

import com.nexusflow.backend.feature.conversation.domain.ConversationDetail
import java.time.Duration
import java.time.Instant

interface ResponseRunStore {
    suspend fun claimNextResponseRun(command: ClaimNextResponseRunCommand): ClaimedResponseRun?

    suspend fun heartbeatResponseRunLease(command: HeartbeatResponseRunLeaseCommand): Boolean

    suspend fun completeResponseRunAttempt(command: CompleteResponseRunAttemptCommand): Boolean

    suspend fun failResponseRunAttempt(command: FailResponseRunAttemptCommand): Boolean

    suspend fun markResponseRunRetryable(command: MarkResponseRunRetryableCommand): Boolean

    suspend fun findResponseRun(responseRunId: ResponseRunId): ResponseRun?

    suspend fun cancelResponseRun(command: CancelResponseRunCommand): CancelResponseRunResult

    suspend fun retryResponseRun(command: RetryResponseRunCommand): RetryResponseRunResult
}

interface ResponseRunResultStore {
    suspend fun storeResponseRunResult(command: StoreResponseRunResultCommand): StoreResponseRunResult

    suspend fun findResponseRunResult(
        responseRunId: ResponseRunId,
        attempt: Int,
    ): ResponseRunResult?
}

interface ResponseRunRepository : ResponseRunStore, ResponseRunResultStore

data class ClaimNextResponseRunCommand(
    val workerId: String,
    val now: Instant,
    val leaseDuration: Duration,
    val maxAttempts: Int,
)

data class ClaimedResponseRun(
    val run: ResponseRun,
)

data class HeartbeatResponseRunLeaseCommand(
    val responseRunId: ResponseRunId,
    val attempt: Int,
    val workerId: String,
    val now: Instant,
    val leaseDuration: Duration,
)

data class CompleteResponseRunAttemptCommand(
    val responseRunId: ResponseRunId,
    val attempt: Int,
    val now: Instant,
)

data class FailResponseRunAttemptCommand(
    val responseRunId: ResponseRunId,
    val attempt: Int,
    val now: Instant,
    val failureCategory: ResponseRunFailureCategory,
)

data class MarkResponseRunRetryableCommand(
    val responseRunId: ResponseRunId,
    val attempt: Int,
    val now: Instant,
    val retryAt: Instant,
    val failureCategory: ResponseRunFailureCategory,
)

data class CancelResponseRunCommand(
    val responseRunId: ResponseRunId,
    val now: Instant,
)

sealed interface CancelResponseRunResult {
    data class Cancelled(val run: ResponseRun) : CancelResponseRunResult

    data class Existing(val run: ResponseRun) : CancelResponseRunResult

    data object NotFound : CancelResponseRunResult

    data object NotCancellable : CancelResponseRunResult
}

data class RetryResponseRunCommand(
    val responseRunId: ResponseRunId,
    val now: Instant,
)

sealed interface RetryResponseRunResult {
    data class Queued(val run: ResponseRun) : RetryResponseRunResult

    data object NotFound : RetryResponseRunResult

    data object NotRetryable : RetryResponseRunResult
}

data class StoreResponseRunResultCommand(
    val responseRunId: ResponseRunId,
    val attempt: Int,
    val payload: ResponseRunResultPayload,
    val now: Instant,
)

sealed interface StoreResponseRunResult {
    data class Stored(val result: ResponseRunResult) : StoreResponseRunResult

    data class Existing(val result: ResponseRunResult) : StoreResponseRunResult

    data object StaleAttempt : StoreResponseRunResult
}

sealed interface ConsumeResponseRunResult {
    data class Consumed(val detail: ConversationDetail) : ConsumeResponseRunResult

    data class AlreadyConsumed(
        val reason: String = "result_already_consumed",
    ) : ConsumeResponseRunResult

    data class Ignored(
        val reason: ConsumeResponseRunIgnoreReason,
    ) : ConsumeResponseRunResult
}

enum class ConsumeResponseRunIgnoreReason {
    ResultMissing,
    ResultTypeMismatch,
    RunMissing,
    AttemptMismatch,
    RunNotConsumable,
    PayloadConversationMismatch,
    PayloadUserMessageMismatch,
    ConversationMissing,
    UserMessageMissing,
    UserMessageRoleMismatch,
    AiRequestMismatch,
    TurnIndexMismatch,
    AssistantMessageConflict,
    PlanningPreconditionMismatch,
    TaskRevisionMismatch,
    DetailReloadFailed,
}
