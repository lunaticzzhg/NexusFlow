package com.nexusflow.backend.feature.conversation.domain

import com.nexusflow.backend.feature.task.domain.MessageId
import com.nexusflow.backend.feature.task.domain.Opportunity
import com.nexusflow.backend.feature.task.domain.Plan
import com.nexusflow.backend.feature.task.domain.RequirementKind
import com.nexusflow.backend.feature.task.domain.RequirementWrite
import com.nexusflow.backend.feature.task.domain.TaskId
import com.nexusflow.backend.feature.task.domain.TaskOwner
import java.time.Duration
import java.time.Instant
import java.util.UUID

interface ConversationRepository {
    suspend fun createConversation(command: CreateConversationCommand): CreateConversationResult

    suspend fun findConversationDetail(
        owner: TaskOwner,
        conversationId: ConversationId,
    ): ConversationDetail?

    suspend fun findConversationDetailForResponseRun(responseRunId: ResponseRunId): ConversationDetail?

    suspend fun appendUserMessage(command: AppendConversationUserMessageCommand): AppendConversationUserMessageResult

    suspend fun claimNextResponseRun(command: ClaimNextResponseRunCommand): ClaimedResponseRun?

    suspend fun heartbeatResponseRunLease(command: HeartbeatResponseRunLeaseCommand): Boolean

    suspend fun completeResponseRunAttempt(command: CompleteResponseRunAttemptCommand): Boolean

    suspend fun failResponseRunAttempt(command: FailResponseRunAttemptCommand): Boolean

    suspend fun markResponseRunRetryable(command: MarkResponseRunRetryableCommand): Boolean

    suspend fun findResponseRun(responseRunId: ResponseRunId): ResponseRun?

    suspend fun cancelResponseRun(command: CancelResponseRunCommand): CancelResponseRunResult

    suspend fun retryResponseRun(command: RetryResponseRunCommand): RetryResponseRunResult

    suspend fun storeResponseRunResult(command: StoreResponseRunResultCommand): StoreResponseRunResult

    suspend fun findResponseRunResult(
        responseRunId: ResponseRunId,
        attempt: Int,
    ): ResponseRunResult?

    suspend fun findConsumableResponseRunForResult(result: ResponseRunResult): ResponseRun?

    suspend fun consumeConversationAnswerResult(command: ConsumeConversationAnswerResultCommand): ConsumeResponseRunResult

    suspend fun queuePlanningStageFromResult(command: QueuePlanningStageFromResultCommand): ConsumeResponseRunResult

    suspend fun completePlanningResult(command: CompletePlanningResultCommand): ConsumeResponseRunResult
}

interface PlanningResponseRunRepository {
    suspend fun consumePlanningUnderstanding(command: ConsumePlanningUnderstandingCommand): ConsumeResponseRunResult

    suspend fun consumePlanningResult(command: ConsumePlanningResultCommand): ConsumeResponseRunResult
}

data class CreateConversationCommand(
    val owner: TaskOwner,
    val conversationId: ConversationId,
    val firstMessageId: MessageId,
    val creationRequestId: String,
    val clientMessageId: String,
    val text: String,
    val aiRequestId: String,
    val now: Instant,
    val responseRunId: ResponseRunId = ResponseRunId(UUID.randomUUID()),
    val responseDeadlineAt: Instant = now.plusSeconds(DEFAULT_RESPONSE_RUN_DEADLINE_SECONDS),
    val originTraceId: String? = null,
)

sealed interface CreateConversationResult {
    data class Created(
        val detail: ConversationDetail,
        val message: ConversationMessage,
    ) : CreateConversationResult

    data class Existing(val detail: ConversationDetail) : CreateConversationResult

    data object ConflictingConversation : CreateConversationResult

    data object ConflictingMessage : CreateConversationResult
}

data class AppendConversationUserMessageCommand(
    val owner: TaskOwner,
    val conversationId: ConversationId,
    val messageId: MessageId,
    val clientMessageId: String,
    val text: String,
    val aiRequestId: String,
    val now: Instant,
    val responseRunId: ResponseRunId = ResponseRunId(UUID.randomUUID()),
    val responseDeadlineAt: Instant = now.plusSeconds(DEFAULT_RESPONSE_RUN_DEADLINE_SECONDS),
    val originTraceId: String? = null,
)

sealed interface AppendConversationUserMessageResult {
    data class Appended(
        val detail: ConversationDetail,
        val message: ConversationMessage,
    ) : AppendConversationUserMessageResult

    data class Existing(val detail: ConversationDetail) : AppendConversationUserMessageResult

    data object ConflictingMessage : AppendConversationUserMessageResult

    data object ConversationNotFound : AppendConversationUserMessageResult
}

private const val DEFAULT_RESPONSE_RUN_DEADLINE_SECONDS = 30L * 60L

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

data class ConsumeConversationAnswerResultCommand(
    val result: ResponseRunResult,
    val payload: ResponseRunResultPayload.ConversationAnswer,
    val now: Instant,
)

data class QueuePlanningStageFromResultCommand(
    val result: ResponseRunResult,
    val expectedTaskId: TaskId,
    val expectedTaskRevision: Long,
    val now: Instant,
)

data class CompletePlanningResultCommand(
    val result: ResponseRunResult,
    val now: Instant,
    val failureCategory: ResponseRunFailureCategory? = null,
)

data class ConsumePlanningUnderstandingCommand(
    val result: ResponseRunResult,
    val payload: ResponseRunResultPayload.PlanningUnderstanding,
    val newTaskId: TaskId,
    val requirements: List<RequirementWrite>,
    val removedRequirementKinds: List<RequirementKind>,
    val now: Instant,
)

data class ConsumePlanningResultCommand(
    val result: ResponseRunResult,
    val payload: ResponseRunResultPayload.PlanningResult,
    val opportunities: List<Opportunity>,
    val plans: List<Plan>,
    val failureCategory: ResponseRunFailureCategory?,
    val now: Instant,
)

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
