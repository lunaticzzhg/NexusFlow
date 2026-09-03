package com.nexusflow.app.feature.task.presentation.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexusflow.app.core.error.AppException
import com.nexusflow.app.feature.task.data.newTaskClientId
import com.nexusflow.app.feature.task.domain.PlanId
import com.nexusflow.app.feature.task.domain.RemoveRequirementCommand
import com.nexusflow.app.feature.task.domain.RequirementId
import com.nexusflow.app.feature.task.domain.SelectPlanCommand
import com.nexusflow.app.feature.task.domain.SendTaskMessageCommand
import com.nexusflow.app.feature.task.domain.TaskId
import com.nexusflow.app.feature.task.domain.TaskRepository
import com.nexusflow.app.feature.task.domain.isExpiredAt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

class TaskDetailViewModel(
    private val taskId: TaskId,
    private val repository: TaskRepository,
    private val clientMessageIdFactory: () -> String = ::newTaskClientId,
    private val timeZoneIdProvider: () -> String = { TimeZone.currentSystemDefault().id },
    private val nowProvider: () -> Instant = { Clock.System.now() },
) : ViewModel() {
    private val _state = MutableStateFlow(TaskDetailUiState())
    val state: StateFlow<TaskDetailUiState> = _state.asStateFlow()

    fun onAction(action: TaskDetailAction) {
        when (action) {
            TaskDetailAction.Load -> {
                if (_state.value.content is TaskDetailContent.Uninitialized) load()
            }
            TaskDetailAction.RetryLoad -> load()
            is TaskDetailAction.DraftChanged -> updateDraft(action.text)
            TaskDetailAction.SendMessage -> sendMessage()
            TaskDetailAction.RetryMessage -> retryMessage()
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
            repository.loadTaskDetail(taskId).fold(
                onSuccess = { detail ->
                    _state.value =
                        TaskDetailUiState(
                            detailContent(
                                detail = detail,
                                draft = current?.draft.orEmpty(),
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
            repository.sendMessage(
                SendTaskMessageCommand(
                    taskId = taskId,
                    clientMessageId = pending.clientMessageId,
                    text = pending.text,
                    timeZoneId = timeZoneIdProvider(),
                ),
            ).fold(
                onSuccess = { detail ->
                    val latest = _state.value.content as? TaskDetailContent.Success
                    _state.value =
                        TaskDetailUiState(
                            detailContent(
                                detail = detail,
                                draft = latest?.draft.orEmpty(),
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
                onSuccess = { detail -> _state.value = TaskDetailUiState(detailContent(detail = detail)) },
                onFailure = { _state.value = TaskDetailUiState(current.withRequirementFailure()) },
            )
        }
    }

    private fun selectPlan(planId: PlanId) {
        val current = _state.value.content as? TaskDetailContent.Success ?: return
        if (current.operation != TaskDetailOperation.Idle) return
        _state.value = TaskDetailUiState(current.copy(operation = TaskDetailOperation.SelectingPlan(planId), operationFailure = null))
        viewModelScope.launch {
            repository.selectPlan(SelectPlanCommand(taskId, planId)).fold(
                onSuccess = { detail -> _state.value = TaskDetailUiState(detailContent(detail = detail)) },
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
        val failure =
            TaskDetailOperationFailure(
                reason = TaskDetailFailureReason.SelectionConflict,
                retryTarget = null,
            )
        repository.loadTaskDetail(taskId).fold(
            onSuccess = { detail ->
                _state.value =
                    TaskDetailUiState(
                        detailContent(
                            detail = detail,
                            draft = current.draft,
                            operationFailure = failure,
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

    private fun detailContent(
        detail: com.nexusflow.app.feature.task.domain.TaskDetail,
        draft: String = "",
        operationFailure: TaskDetailOperationFailure? = null,
    ): TaskDetailContent.Success =
        TaskDetailContent.Success(
            detail = detail,
            draft = draft,
            operationFailure = operationFailure,
            expiredPlanIds =
                detail.plans
                    .filter { it.isExpiredAt(nowProvider()) }
                    .map { it.id }
                    .toSet(),
        )
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
