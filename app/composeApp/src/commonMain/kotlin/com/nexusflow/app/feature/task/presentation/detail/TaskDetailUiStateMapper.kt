package com.nexusflow.app.feature.task.presentation.detail

import com.nexusflow.app.feature.task.domain.ConversationDetail
import com.nexusflow.app.feature.task.domain.MessageRole
import com.nexusflow.app.feature.task.domain.PlanId
import com.nexusflow.app.feature.task.domain.PlanningState
import com.nexusflow.app.feature.task.domain.ResponseRun
import com.nexusflow.app.feature.task.domain.ResponseRunStatus
import com.nexusflow.app.feature.task.domain.TaskMessage
import com.nexusflow.app.feature.task.domain.isExpiredAt
import kotlinx.datetime.Instant

internal class TaskDetailUiStateMapper {
    fun success(
        detail: ConversationDetail,
        composer: TaskComposerState,
        operation: TaskDetailOperation = TaskDetailOperation.Idle,
        operationFailure: TaskDetailOperationFailure? = null,
        streamState: ResponseRunStreamState,
        now: Instant,
    ): TaskDetailContent.Success {
        val activeResponse = detail.activeResponseUiState(streamState)
        val responseAwareOperation = operation.responseAware(activeResponse)
        val expiredPlanIds =
            detail.currentTask
                ?.plans
                .orEmpty()
                .filter { it.isExpiredAt(now) }
                .map { it.id }
                .toSet()
        val transcript =
            TaskTranscriptUiState(
                conversationId = detail.id.value,
                items =
                    detail.toTranscriptItems(
                        pendingMessage = composer.pendingMessage,
                        failedMessage = composer.failedMessage,
                        activeResponse = activeResponse,
                        operation = responseAwareOperation,
                        operationFailure = operationFailure,
                        expiredPlanIds = expiredPlanIds,
                    ),
                followSignal = activeResponse?.followSignal(),
            )
        val composerUiState =
            TaskComposerUiState(
                draft = composer.draft,
                primaryAction = responseAwareOperation.composerPrimaryAction(composer.draft),
            )
        return TaskDetailContent.Success(
            detail = detail,
            draft = composer.draft,
            operation = responseAwareOperation,
            pendingMessage = composer.pendingMessage,
            failedMessage = composer.failedMessage,
            activeResponse = activeResponse,
            operationFailure = operationFailure,
            expiredPlanIds = expiredPlanIds,
            screen = TaskDetailScreenUiState(transcript = transcript, composer = composerUiState),
        )
    }
}

private fun TaskDetailOperation.responseAware(activeResponse: ActiveResponseUiState?): TaskDetailOperation =
    when (this) {
        TaskDetailOperation.Idle ->
            if (activeResponse?.canCancel == true) {
                TaskDetailOperation.ReceivingResponse(activeResponse.runId)
            } else {
                TaskDetailOperation.Idle
            }

        is TaskDetailOperation.ReceivingResponse ->
            if (activeResponse?.runId == runId && activeResponse.canCancel) this else TaskDetailOperation.Idle

        is TaskDetailOperation.CancellingResponse ->
            if (activeResponse?.runId == runId && activeResponse.canCancel) this else TaskDetailOperation.Idle

        is TaskDetailOperation.RetryingResponse -> this

        is TaskDetailOperation.RemovingRequirement,
        is TaskDetailOperation.SelectingPlan,
        is TaskDetailOperation.SendingMessage,
        -> this
    }

private fun TaskDetailOperation.composerPrimaryAction(draft: String): TaskComposerPrimaryAction =
    when (this) {
        TaskDetailOperation.Idle ->
            if (draft.isBlank()) {
                TaskComposerPrimaryAction.Disabled
            } else {
                TaskComposerPrimaryAction.Send
            }

        is TaskDetailOperation.SendingMessage -> TaskComposerPrimaryAction.Sending
        is TaskDetailOperation.ReceivingResponse -> TaskComposerPrimaryAction.Receiving
        is TaskDetailOperation.CancellingResponse -> TaskComposerPrimaryAction.Cancelling
        is TaskDetailOperation.RetryingResponse -> TaskComposerPrimaryAction.Retrying
        is TaskDetailOperation.RemovingRequirement,
        is TaskDetailOperation.SelectingPlan,
        -> TaskComposerPrimaryAction.Disabled
    }

private fun ConversationDetail.toTranscriptItems(
    pendingMessage: PendingTaskMessage?,
    failedMessage: PendingTaskMessage?,
    activeResponse: ActiveResponseUiState?,
    operation: TaskDetailOperation,
    operationFailure: TaskDetailOperationFailure?,
    expiredPlanIds: Set<PlanId>,
): List<TaskTranscriptItem> =
    buildList {
        operationFailure
            ?.takeUnless { it.reason == TaskDetailFailureReason.MessageSendFailed }
            ?.let { add(TaskTranscriptItem.OperationFailure(it)) }
        messages.sortedForTranscript().forEachIndexed { index, message ->
            add(TaskTranscriptItem.Message(message = message, index = index))
            if (activeResponse?.userMessageId == message.id) {
                add(TaskTranscriptItem.ActiveResponse(activeResponse))
            }
        }
        currentTask?.planningState?.noticeOrNull()?.let { add(TaskTranscriptItem.PlanningNotice(it)) }
        pendingMessage?.let { add(TaskTranscriptItem.PendingMessage(it)) }
        failedMessage?.let { add(TaskTranscriptItem.FailedMessage(it)) }
        currentTask?.takeIf { it.plans.isNotEmpty() }?.let { task ->
            add(
                TaskTranscriptItem.Planning(
                    plans = task.plans,
                    selectedPlanId = task.selectedPlanId,
                    expiredPlanIds = task.plans.mapNotNull { plan -> plan.id.takeIf { it in expiredPlanIds } }.toSet(),
                ),
            )
        }
    }

private fun List<TaskMessage>.sortedForTranscript(): List<TaskMessage> =
    sortedWith(
        compareBy<TaskMessage> { it.turnIndex ?: Long.MAX_VALUE }
            .thenBy { if (it.role == MessageRole.User) 0 else 1 }
            .thenBy { it.id },
    )

private fun PlanningState.noticeOrNull(): PlanningState? =
    when (this) {
        PlanningState.NoCandidates,
        PlanningState.NoFeasiblePlan,
        PlanningState.Unavailable,
        -> this
        PlanningState.Idle,
        PlanningState.Ready,
        -> null
    }

private fun ActiveResponseUiState.followSignal(): TaskTranscriptFollowSignal =
    TaskTranscriptFollowSignal(
        runId = runId,
        status = status,
        partialTextHash = partialText.hashCode(),
        activityHash = activities.hashCode(),
    )

private fun ConversationDetail.activeResponseUiState(streamState: ResponseRunStreamState): ActiveResponseUiState? {
    val run = visibleResponseRun(streamState) ?: return null
    if (run.status == ResponseRunStatus.Completed && run.assistantMessageId != null) return null
    val streamBelongsToRun = streamState.runId == run.id
    val terminalStatus = streamState.terminalStatus.takeIf { streamBelongsToRun } ?: run.status
    val partialText = streamState.partialText.takeIf { streamBelongsToRun }.orEmpty()
    return ActiveResponseUiState(
        runId = run.id,
        userMessageId = run.userMessageId,
        turnIndex = run.turnIndex,
        status =
            when {
                terminalStatus == ResponseRunStatus.Cancelled -> ActiveResponseStatus.Cancelled
                terminalStatus == ResponseRunStatus.TimedOut -> ActiveResponseStatus.TimedOut
                terminalStatus == ResponseRunStatus.Failed ->
                    ActiveResponseStatus.Failed
                run.status == ResponseRunStatus.FailedRetryable -> ActiveResponseStatus.Thinking
                partialText.isNotBlank() -> ActiveResponseStatus.Streaming
                streamBelongsToRun && streamState.activities.isNotEmpty() -> ActiveResponseStatus.Thinking
                else -> ActiveResponseStatus.Queued
            },
        partialText = partialText,
        activities = if (streamBelongsToRun) streamState.activities else emptyList(),
        canCancel = run.status.isStreamOpen(),
        canRetry =
            run.status == ResponseRunStatus.Failed ||
                run.status == ResponseRunStatus.TimedOut,
    )
}

private fun ConversationDetail.visibleResponseRun(streamState: ResponseRunStreamState): ResponseRun? =
    streamState.runId
        ?.let { activeRunId -> responseRuns.firstOrNull { it.id == activeRunId } }
        ?.takeUnless { it.status == ResponseRunStatus.Completed && it.assistantMessageId != null }
        ?: responseRuns
            .sortedWith(compareBy<ResponseRun> { it.turnIndex }.thenBy { it.createdAt })
            .lastOrNull { run ->
                run.status != ResponseRunStatus.Completed ||
                    run.assistantMessageId == null
            }

private fun ResponseRunStatus.isStreamOpen(): Boolean =
    when (this) {
        ResponseRunStatus.Queued,
        ResponseRunStatus.Processing,
        ResponseRunStatus.Streaming,
        -> true
        ResponseRunStatus.Completed,
        ResponseRunStatus.FailedRetryable,
        ResponseRunStatus.Failed,
        ResponseRunStatus.TimedOut,
        ResponseRunStatus.Cancelled,
        -> false
    }
