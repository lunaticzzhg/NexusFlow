package com.nexusflow.app.feature.task.presentation.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexusflow.app.feature.task.domain.CreateTaskCommand
import com.nexusflow.app.feature.task.domain.TaskRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TaskCreateViewModel(
    private val repository: TaskRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(TaskCreateUiState())
    private val _effects = MutableSharedFlow<TaskCreateEffect>()

    val state: StateFlow<TaskCreateUiState> = _state.asStateFlow()
    val effects: SharedFlow<TaskCreateEffect> = _effects.asSharedFlow()

    fun dispatch(intent: TaskCreateIntent) {
        when (intent) {
            is TaskCreateIntent.RequestChanged -> {
                if (_state.value.submission !is TaskSubmission.Submitting) {
                    _state.value = _state.value.copy(requestText = intent.text, submission = TaskSubmission.Idle)
                }
            }
            TaskCreateIntent.Submit,
            TaskCreateIntent.RetrySubmit,
            -> submit()
        }
    }

    private fun submit() {
        val current = _state.value
        if (!current.canSubmit) return

        _state.value = current.copy(submission = TaskSubmission.Submitting)
        viewModelScope.launch {
            repository.createTask(CreateTaskCommand(current.requestText)).fold(
                onSuccess = { task -> _effects.emit(TaskCreateEffect.OpenTask(task.id, task.title)) },
                onFailure = { _state.value = _state.value.copy(submission = TaskSubmission.Failed) },
            )
        }
    }
}
