package com.nexusflow.backend.feature.conversation.application

import com.nexusflow.backend.core.identity.ActorContext
import com.nexusflow.backend.feature.responserun.domain.CancelResponseRunCommand
import com.nexusflow.backend.feature.responserun.domain.CancelResponseRunResult
import com.nexusflow.backend.feature.conversation.domain.ConversationDetail
import com.nexusflow.backend.feature.conversation.domain.ConversationId
import com.nexusflow.backend.feature.conversation.domain.ConversationRepository
import com.nexusflow.backend.feature.responserun.domain.ResponseRun
import com.nexusflow.backend.feature.responserun.domain.ResponseRunId
import com.nexusflow.backend.feature.responserun.domain.ResponseRunStatus
import com.nexusflow.backend.feature.responserun.domain.ResponseRunStore
import com.nexusflow.backend.feature.responserun.domain.RetryResponseRunCommand
import com.nexusflow.backend.feature.responserun.domain.RetryResponseRunResult
import com.nexusflow.backend.feature.task.application.InvalidTaskOperationException
import com.nexusflow.backend.feature.task.application.InvalidTaskRequestException
import com.nexusflow.backend.feature.task.application.MissingTaskScopeException
import com.nexusflow.backend.feature.task.application.TaskNotFoundException
import com.nexusflow.backend.feature.task.domain.TaskDetail
import com.nexusflow.backend.feature.task.domain.TaskOwner
import com.nexusflow.backend.feature.task.domain.TaskRepository
import com.nexusflow.backend.feature.task.domain.TenantId
import com.nexusflow.backend.feature.task.domain.UserId
import kotlinx.coroutines.flow.Flow
import java.time.Clock
import java.util.UUID

class ResponseRunService(
    private val conversationRepository: ConversationRepository,
    private val responseRunStore: ResponseRunStore,
    private val taskRepository: TaskRepository,
    private val realtimeHub: ResponseRunRealtimeHub,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun snapshot(
        actor: ActorContext,
        conversationId: String,
        responseRunId: String,
    ): ResponseRunSnapshot {
        actor.requireScope(READ_SCOPE)
        val runId = responseRunId.toResponseRunId()
        val detail = loadAuthorizedDetail(actor.taskOwner(), conversationId.toConversationId(), runId)
        val run = detail.responseRuns.singleOrNull { it.id == runId } ?: throw TaskNotFoundException()
        return buildSnapshot(detail, run)
    }

    suspend fun cancel(
        actor: ActorContext,
        conversationId: String,
        responseRunId: String,
    ): ResponseRunSnapshot {
        actor.requireScope(WRITE_SCOPE)
        val runId = responseRunId.toResponseRunId()
        val expectedConversationId = conversationId.toConversationId()
        loadAuthorizedDetail(actor.taskOwner(), expectedConversationId, runId)
        val run = when (val result = responseRunStore.cancelResponseRun(CancelResponseRunCommand(runId, clock.instant()))) {
            is CancelResponseRunResult.Cancelled -> result.run.also { realtimeHub.recordRun(it) }
            is CancelResponseRunResult.Existing -> result.run
            CancelResponseRunResult.NotCancellable -> throw InvalidTaskOperationException()
            CancelResponseRunResult.NotFound -> throw TaskNotFoundException()
        }
        val detail = loadAuthorizedDetail(actor.taskOwner(), expectedConversationId, runId)
        return buildSnapshot(detail, run)
    }

    suspend fun retry(
        actor: ActorContext,
        conversationId: String,
        responseRunId: String,
    ): ResponseRunSnapshot {
        actor.requireScope(WRITE_SCOPE)
        val runId = responseRunId.toResponseRunId()
        val expectedConversationId = conversationId.toConversationId()
        loadAuthorizedDetail(actor.taskOwner(), expectedConversationId, runId)
        val run = when (val result = responseRunStore.retryResponseRun(RetryResponseRunCommand(runId, clock.instant()))) {
            is RetryResponseRunResult.Queued -> result.run.also { realtimeHub.beginAttempt(it) }
            RetryResponseRunResult.NotRetryable -> throw InvalidTaskOperationException()
            RetryResponseRunResult.NotFound -> throw TaskNotFoundException()
        }
        val detail = loadAuthorizedDetail(actor.taskOwner(), expectedConversationId, runId)
        return buildSnapshot(detail, run)
    }

    fun events(
        responseRunId: String,
        afterSeq: Long? = null,
    ): Flow<ResponseRunEvent> =
        realtimeHub.events(responseRunId.toResponseRunId(), afterSeq)

    private suspend fun buildSnapshot(
        detail: ConversationDetail,
        run: ResponseRun,
    ): ResponseRunSnapshot {
        val realtimeSnapshot = realtimeHub.snapshot(run)
        return ResponseRunSnapshot(
            run = run,
            detail = detail,
            currentTask = taskRepository.findCurrentTaskForConversation(detail.conversation.owner, detail.conversation.id),
            streamAttempt = realtimeSnapshot?.attempt ?: run.attempt,
            lastSeq = realtimeSnapshot?.lastSeq ?: 0,
            partialText = realtimeSnapshot?.partialText.orEmpty(),
            activities = realtimeSnapshot?.activities.orEmpty(),
            realtimeSnapshotAvailable = realtimeSnapshot != null,
        )
    }

    private suspend fun loadAuthorizedDetail(
        owner: TaskOwner,
        conversationId: ConversationId,
        responseRunId: ResponseRunId,
    ): ConversationDetail {
        val detail = conversationRepository.findConversationDetailForResponseRun(responseRunId) ?: throw TaskNotFoundException()
        if (detail.conversation.owner != owner || detail.conversation.id != conversationId) throw TaskNotFoundException()
        return detail
    }

    private fun ActorContext.taskOwner(): TaskOwner =
        TaskOwner(
            tenantId = TenantId(tenantId.toUuid("tenantId")),
            userId = UserId(userId.toUuid("userId")),
        )

    private fun ActorContext.requireScope(scope: String) {
        if (!hasScope(scope)) throw MissingTaskScopeException()
    }

    private fun String.toResponseRunId(): ResponseRunId =
        ResponseRunId(toUuid("responseRunId"))

    private fun String.toConversationId(): ConversationId =
        ConversationId(toUuid("conversationId"))

    private fun String.toUuid(fieldName: String): UUID =
        try {
            UUID.fromString(this)
        } catch (_: IllegalArgumentException) {
            throw InvalidTaskRequestException("$fieldName is invalid")
        }
}

data class ResponseRunSnapshot(
    val run: ResponseRun,
    val detail: ConversationDetail,
    val currentTask: TaskDetail?,
    val streamAttempt: Int,
    val lastSeq: Long,
    val partialText: String,
    val activities: List<ResponseRunActivity>,
    val realtimeSnapshotAvailable: Boolean,
)

private const val READ_SCOPE = "orbit.tasks.read"
private const val WRITE_SCOPE = "orbit.tasks.write"
