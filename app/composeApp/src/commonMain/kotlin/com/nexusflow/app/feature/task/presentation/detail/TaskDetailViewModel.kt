package com.nexusflow.app.feature.task.presentation.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexusflow.app.core.error.AppException
import com.nexusflow.app.core.network.realtime.RealtimeSseRequest
import com.nexusflow.app.core.network.realtime.RealtimeSseSessionFactory
import com.nexusflow.app.core.network.realtime.RealtimeSseTransport
import com.nexusflow.app.core.network.realtime.RealtimeSseTransportEvent
import com.nexusflow.app.core.observability.AppLogger
import com.nexusflow.app.core.observability.AppTraceManager
import com.nexusflow.app.core.observability.PassthroughAppTraceManager
import com.nexusflow.app.feature.task.data.newTaskClientId
import com.nexusflow.app.feature.task.domain.ConversationDetail
import com.nexusflow.app.feature.task.domain.PlanId
import com.nexusflow.app.feature.task.domain.RemoveRequirementCommand
import com.nexusflow.app.feature.task.domain.RequirementId
import com.nexusflow.app.feature.task.domain.ResponseRun
import com.nexusflow.app.feature.task.domain.ResponseRunId
import com.nexusflow.app.feature.task.domain.ResponseRunStatus
import com.nexusflow.app.feature.task.domain.SelectPlanCommand
import com.nexusflow.app.feature.task.domain.SendConversationMessageCommand
import com.nexusflow.app.feature.task.domain.TaskDetail
import com.nexusflow.app.feature.task.domain.TaskRepository
import com.nexusflow.app.feature.task.domain.isExpiredAt
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

internal class TaskDetailViewModel(
    private val detailIdentity: TaskDetailIdentity,
    private val repository: TaskRepository,
    realtimeSseSessionFactory: RealtimeSseSessionFactory = NoopRealtimeSseSessionFactory,
    private val traceManager: AppTraceManager = PassthroughAppTraceManager,
    appLogger: AppLogger? = null,
    private val clientMessageIdFactory: () -> String = ::newTaskClientId,
    private val timeZoneIdProvider: () -> String = { TimeZone.currentSystemDefault().id },
    private val nowProvider: () -> Instant = { Clock.System.now() },
) : ViewModel() {
    private val _state = MutableStateFlow(TaskDetailUiState())
    val state: StateFlow<TaskDetailUiState> = _state.asStateFlow()
    private val streamController =
        ResponseRunStreamController(
            repository = repository,
            sseSessionFactory = realtimeSseSessionFactory,
            scope = viewModelScope,
            logger = appLogger,
        )

    init {
        viewModelScope.launch {
            streamController.state.collect { streamState ->
                applyStreamState(streamState)
            }
        }
        viewModelScope.launch {
            streamController.events.collect { event ->
                when (event) {
                    is ResponseRunControllerEvent.SnapshotResolved -> {
                        val current = _state.value.content as? TaskDetailContent.Success ?: return@collect
                        _state.value =
                            TaskDetailUiState(
                                detailContent(
                                    detail = event.snapshot.conversation,
                                    draft = current.draft,
                                    operation =
                                        if (event.snapshot.run.status.isStreamOpen()) {
                                            current.operation
                                        } else {
                                            TaskDetailOperation.Idle
                                        },
                                    operationFailure = current.operationFailure,
                                    streamState = streamController.state.value,
                                ),
                            )
                    }
                }
            }
        }
    }

    fun onAction(action: TaskDetailAction) {
        when (action) {
            TaskDetailAction.Load -> {
                if (_state.value.content is TaskDetailContent.Uninitialized) load()
            }
            TaskDetailAction.RetryLoad -> load()
            is TaskDetailAction.DraftChanged -> updateDraft(action.text)
            TaskDetailAction.SendMessage -> sendMessage()
            TaskDetailAction.RetryMessage -> retryMessage()
            is TaskDetailAction.CancelResponseRun -> cancelResponseRun(action.runId)
            is TaskDetailAction.RetryResponseRun -> retryResponseRun(action.runId)
            is TaskDetailAction.RemoveRequirement -> removeRequirement(action.requirementId)
            is TaskDetailAction.SelectPlan -> selectPlan(action.planId)
            is TaskDetailAction.RetryOperation -> retryOperation(action.target)
        }
    }

    private fun load() {
        val current = _state.value.content as? TaskDetailContent.Success
        if (current == null) {
            _state.value = TaskDetailUiState(TaskDetailContent.Loading)
        }
        viewModelScope.launch {
            loadDetail().fold(
                onSuccess = { detail ->
                    startVisibleResponseRun(detail)
                    _state.value =
                        TaskDetailUiState(
                            detailContent(
                                detail = detail,
                                draft = current?.draft.orEmpty(),
                                streamState = streamController.state.value,
                            ),
                        )
                },
                onFailure = {
                    _state.value =
                        if (current == null) {
                            TaskDetailUiState(TaskDetailContent.Failure)
                        } else {
                            TaskDetailUiState(current.copy(operation = TaskDetailOperation.Idle))
                        }
                },
            )
        }
    }

    private fun updateDraft(text: String) {
        val current = _state.value.content as? TaskDetailContent.Success ?: return
        _state.value =
            TaskDetailUiState(
                current.copy(
                    draft = text,
                    operationFailure = null,
                ),
            )
    }

    private fun sendMessage() {
        val current = _state.value.content as? TaskDetailContent.Success ?: return
        val text = current.draft.trim()
        if (text.isBlank() || current.operation != TaskDetailOperation.Idle) return
        sendPendingMessage(
            current = current,
            pending = PendingTaskMessage(clientMessageIdFactory(), text),
            clearDraft = true,
        )
    }

    private fun retryMessage() {
        val current = _state.value.content as? TaskDetailContent.Success ?: return
        val pending = current.failedMessage ?: return
        if (current.operation != TaskDetailOperation.Idle) return
        sendPendingMessage(
            current = current,
            pending = pending,
            clearDraft = false,
        )
    }

    private fun sendPendingMessage(
        current: TaskDetailContent.Success,
        pending: PendingTaskMessage,
        clearDraft: Boolean,
    ) {
        _state.value =
            TaskDetailUiState(
                current.copy(
                    draft = if (clearDraft) "" else current.draft,
                    operation = TaskDetailOperation.SendingMessage(pending.clientMessageId),
                    pendingMessage = pending,
                    failedMessage = null,
                    operationFailure = null,
                ),
            )
        viewModelScope.launch {
            traceManager.withNewResultTrace(
                operation = "task_message_send",
                trigger = if (clearDraft) "user" else "retry",
            ) {
                sendDetailMessage(
                    clientMessageId = pending.clientMessageId,
                    text = pending.text,
                )
            }.fold(
                onSuccess = { detail ->
                    val latest = _state.value.content as? TaskDetailContent.Success
                    val acceptedRun =
                        startAcceptedResponseRun(
                            detail = detail,
                            clientMessageId = pending.clientMessageId,
                        )
                    _state.value =
                        TaskDetailUiState(
                            detailContent(
                                detail = detail,
                                draft = latest?.draft.orEmpty(),
                                operation =
                                    if (acceptedRun != null) {
                                        TaskDetailOperation.SendingMessage(pending.clientMessageId)
                                    } else {
                                        TaskDetailOperation.Idle
                                    },
                                streamState = streamController.state.value,
                            ),
                        )
                },
                onFailure = {
                    val latest = _state.value.content as? TaskDetailContent.Success ?: current
                    _state.value =
                        TaskDetailUiState(
                            latest.copy(
                                operation = TaskDetailOperation.Idle,
                                pendingMessage = null,
                                failedMessage = pending,
                                operationFailure =
                                    TaskDetailOperationFailure(
                                        reason = TaskDetailFailureReason.MessageSendFailed,
                                        retryTarget = null,
                                    ),
                            ),
                        )
                },
            )
        }
    }

    private fun removeRequirement(requirementId: RequirementId) {
        val current = _state.value.content as? TaskDetailContent.Success ?: return
        val taskId = current.detail.currentTask?.id ?: return
        if (current.operation != TaskDetailOperation.Idle) return
        _state.value =
            TaskDetailUiState(
                current.copy(
                    operation = TaskDetailOperation.RemovingRequirement(requirementId),
                    operationFailure = null,
                ),
            )
        viewModelScope.launch {
            repository.removeRequirement(RemoveRequirementCommand(taskId, requirementId)).fold(
                onSuccess = { taskDetail ->
                    _state.value =
                        TaskDetailUiState(
                            detailContent(
                                detail = current.detail.withTaskDetail(taskDetail),
                                draft = current.draft,
                                streamState = streamController.state.value,
                            ),
                        )
                },
                onFailure = { _state.value = TaskDetailUiState(current.withRequirementFailure()) },
            )
        }
    }

    private fun cancelResponseRun(runId: ResponseRunId) {
        val current = _state.value.content as? TaskDetailContent.Success ?: return
        viewModelScope.launch {
            repository.cancelResponseRun(current.detail.id, runId).fold(
                onSuccess = { run ->
                    streamController.stop()
                    applyResponseRunUpdate(run)
                },
                onFailure = { applyStreamState(streamController.state.value) },
            )
        }
    }

    private fun retryResponseRun(runId: ResponseRunId) {
        val current = _state.value.content as? TaskDetailContent.Success ?: return
        viewModelScope.launch {
            repository.retryResponseRun(current.detail.id, runId).fold(
                onSuccess = { run ->
                    val latest = _state.value.content as? TaskDetailContent.Success ?: return@fold
                    val nextDetail = latest.detail.withResponseRun(run)
                    streamController.start(nextDetail.id, run)
                    _state.value =
                        TaskDetailUiState(
                            detailContent(
                                detail = nextDetail,
                                draft = latest.draft,
                                operationFailure = latest.operationFailure,
                                streamState = streamController.state.value,
                            ),
                        )
                },
                onFailure = { applyStreamState(streamController.state.value) },
            )
        }
    }

    private fun selectPlan(planId: PlanId) {
        val current = _state.value.content as? TaskDetailContent.Success ?: return
        val taskId = current.detail.currentTask?.id ?: return
        if (current.operation != TaskDetailOperation.Idle) return
        _state.value = TaskDetailUiState(current.copy(operation = TaskDetailOperation.SelectingPlan(planId), operationFailure = null))
        viewModelScope.launch {
            repository.selectPlan(SelectPlanCommand(taskId, planId)).fold(
                onSuccess = { taskDetail ->
                    _state.value =
                        TaskDetailUiState(
                            detailContent(
                                detail = current.detail.withTaskDetail(taskDetail),
                                draft = current.draft,
                                streamState = streamController.state.value,
                            ),
                        )
                },
                onFailure = { error ->
                    if (error is AppException.Conflict) {
                        reloadAfterSelectionConflict(current)
                    } else {
                        _state.value =
                            TaskDetailUiState(
                                current.copy(
                                    operation = TaskDetailOperation.Idle,
                                    operationFailure =
                                        TaskDetailOperationFailure(
                                            reason = TaskDetailFailureReason.SelectionFailed,
                                            retryTarget = TaskDetailRetryTarget.SelectPlan(planId),
                                        ),
                                ),
                            )
                    }
                },
            )
        }
    }

    private suspend fun reloadAfterSelectionConflict(current: TaskDetailContent.Success) {
        val taskId = current.detail.currentTask?.id ?: return
        val failure =
            TaskDetailOperationFailure(
                reason = TaskDetailFailureReason.SelectionConflict,
                retryTarget = null,
            )
        repository.loadTaskDetail(taskId).fold(
            onSuccess = { taskDetail ->
                _state.value =
                    TaskDetailUiState(
                        detailContent(
                            detail = current.detail.withTaskDetail(taskDetail),
                            draft = current.draft,
                            operationFailure = failure,
                            streamState = streamController.state.value,
                        ),
                    )
            },
            onFailure = {
                _state.value =
                    TaskDetailUiState(
                        current.copy(
                            operation = TaskDetailOperation.Idle,
                            operationFailure = failure,
                        ),
                    )
            },
        )
    }

    private fun retryOperation(target: TaskDetailRetryTarget) {
        when (target) {
            is TaskDetailRetryTarget.SelectPlan -> selectPlan(target.planId)
        }
    }

    private suspend fun loadDetail(): Result<ConversationDetail> =
        when (val identity = detailIdentity) {
            is TaskDetailIdentity.Conversation -> repository.loadConversationDetail(identity.id)
        }

    private suspend fun sendDetailMessage(
        clientMessageId: String,
        text: String,
    ): Result<ConversationDetail> =
        when (val identity = detailIdentity) {
            is TaskDetailIdentity.Conversation ->
                repository.sendConversationMessage(
                    SendConversationMessageCommand(
                        conversationId = identity.id,
                        clientMessageId = clientMessageId,
                        text = text,
                        timeZoneId = timeZoneIdProvider(),
                    ),
                )
        }

    private fun detailContent(
        detail: ConversationDetail,
        draft: String = "",
        operation: TaskDetailOperation = TaskDetailOperation.Idle,
        operationFailure: TaskDetailOperationFailure? = null,
        streamState: ResponseRunStreamState = streamController.state.value,
    ): TaskDetailContent.Success =
        TaskDetailContent.Success(
            detail = detail,
            draft = draft,
            operation = operation,
            activeResponse = detail.activeResponseUiState(streamState),
            operationFailure = operationFailure,
            expiredPlanIds =
                detail.currentTask
                    ?.plans
                    .orEmpty()
                    .filter { it.isExpiredAt(nowProvider()) }
                    .map { it.id }
                    .toSet(),
        )

    private fun applyStreamState(streamState: ResponseRunStreamState) {
        val current = _state.value.content as? TaskDetailContent.Success ?: return
        _state.value =
            TaskDetailUiState(
                current.copy(
                    activeResponse = current.detail.activeResponseUiState(streamState),
                ),
            )
    }

    private fun applyResponseRunUpdate(run: ResponseRun) {
        val current = _state.value.content as? TaskDetailContent.Success ?: return
        val nextDetail = current.detail.withResponseRun(run)
        _state.value =
            TaskDetailUiState(
                detailContent(
                    detail = nextDetail,
                    draft = current.draft,
                    operationFailure = current.operationFailure,
                    streamState = streamController.state.value,
                ),
            )
    }

    private fun startVisibleResponseRun(detail: ConversationDetail) {
        val run = detail.visibleResponseRun(streamController.state.value) ?: return
        if (run.status.isStreamOpen()) {
            streamController.start(detail.id, run)
        }
    }

    private fun startAcceptedResponseRun(
        detail: ConversationDetail,
        clientMessageId: String,
    ): ResponseRun? {
        val userMessage =
            detail.messages.firstOrNull { message ->
                message.clientMessageId == clientMessageId
            }
        val run =
            userMessage
                ?.let { message -> detail.responseRuns.firstOrNull { it.userMessageId == message.id } }
                ?: detail.visibleResponseRun(streamController.state.value)
                ?: return null
        streamController.start(detail.id, run)
        return run
    }
}

private fun ConversationDetail.withTaskDetail(taskDetail: TaskDetail): ConversationDetail =
    copy(
        currentTask =
            taskDetail.copy(
                messages = emptyList(),
            ),
    )

private fun ConversationDetail.withResponseRun(run: ResponseRun): ConversationDetail =
    copy(
        responseRuns =
            if (responseRuns.any { it.id == run.id }) {
                responseRuns.map { existing -> if (existing.id == run.id) run else existing }
            } else {
                responseRuns + run
            },
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
                terminalStatus == ResponseRunStatus.FailedRetryable || terminalStatus == ResponseRunStatus.Failed ->
                    ActiveResponseStatus.Failed
                partialText.isNotBlank() -> ActiveResponseStatus.Streaming
                streamBelongsToRun && streamState.activities.isNotEmpty() -> ActiveResponseStatus.Thinking
                else -> ActiveResponseStatus.Queued
            },
        partialText = partialText,
        activities = if (streamBelongsToRun) streamState.activities else emptyList(),
        canCancel = run.status.isStreamOpen(),
        canRetry =
            run.status == ResponseRunStatus.FailedRetryable ||
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

private fun TaskDetailContent.Success.withRequirementFailure(): TaskDetailContent.Success =
    copy(
        operation = TaskDetailOperation.Idle,
        operationFailure =
            TaskDetailOperationFailure(
                reason = TaskDetailFailureReason.RequirementMutationFailed,
                retryTarget = null,
            ),
    )

private val NoopRealtimeSseSessionFactory =
    RealtimeSseSessionFactory(
        transport =
            object : RealtimeSseTransport {
                override suspend fun collect(
                    request: RealtimeSseRequest,
                    bearerToken: String,
                    onEvent: suspend (RealtimeSseTransportEvent) -> Unit,
                ) {
                    onEvent(RealtimeSseTransportEvent.Opened)
                    awaitCancellation()
                }
            },
        sessionProvider = { null },
    )
