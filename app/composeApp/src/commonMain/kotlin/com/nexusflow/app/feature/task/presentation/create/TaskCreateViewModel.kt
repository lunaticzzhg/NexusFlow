package com.nexusflow.app.feature.task.presentation.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexusflow.app.feature.task.data.newTaskClientId
import com.nexusflow.app.feature.task.domain.CreateTaskCommand
import com.nexusflow.app.feature.task.domain.TaskRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone

class TaskCreateViewModel(
    private val repository: TaskRepository,
    private val clientIdFactory: () -> String = ::newTaskClientId,
    private val timeZoneIdProvider: () -> String = { TimeZone.currentSystemDefault().id },
) : ViewModel() {
    private val _state = MutableStateFlow(TaskCreateUiState())
    private val _effects = MutableSharedFlow<TaskCreateEffect>()

    val state: StateFlow<TaskCreateUiState> = _state.asStateFlow()
    val effects: SharedFlow<TaskCreateEffect> = _effects.asSharedFlow()
    private var activeCreateIdentity: TaskCreateOperationIdentity? = null

    fun onAction(action: TaskCreateAction) {
        when (action) {
            is TaskCreateAction.RequestChanged -> {
                if (_state.value.submission !is TaskSubmission.Submitting) {
                    activeCreateIdentity = newCreateIdentity()
                    _state.value = _state.value.copy(requestText = action.text, submission = TaskSubmission.Idle)
                }
            }
            TaskCreateAction.Submit,
            TaskCreateAction.RetrySubmit,
            -> submit()
        }
    }

    private fun submit() {
        val current = _state.value
        if (!current.canSubmit) return

        _state.value = current.copy(submission = TaskSubmission.Submitting)
        val identity = activeCreateIdentity ?: newCreateIdentity().also { activeCreateIdentity = it }
        viewModelScope.launch {
            repository.createTask(
                CreateTaskCommand(
                    creationRequestId = identity.creationRequestId,
                    initialMessageId = identity.initialMessageId,
                    requestText = current.requestText,
                    timeZoneId = timeZoneIdProvider(),
                ),
            ).fold(
                onSuccess = { task -> _effects.emit(TaskCreateEffect.OpenTask(task.id)) },
                onFailure = { _state.value = _state.value.copy(submission = TaskSubmission.Failed) },
            )
        }
    }

    private fun newCreateIdentity(): TaskCreateOperationIdentity =
        TaskCreateOperationIdentity(
            creationRequestId = clientIdFactory(),
            initialMessageId = clientIdFactory(),
        )
}

private data class TaskCreateOperationIdentity(
    val creationRequestId: String,
    val initialMessageId: String,
)
