package com.nexusflow.app.feature.task.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexusflow.app.feature.task.domain.TaskRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TaskHomeViewModel(
    private val repository: TaskRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(TaskHomeUiState(TaskHomeContent.Uninitialized))
    private val _effects = MutableSharedFlow<TaskHomeEffect>()
    val state: StateFlow<TaskHomeUiState> = _state.asStateFlow()
    val effects: SharedFlow<TaskHomeEffect> = _effects.asSharedFlow()

    fun onAction(action: TaskHomeAction) {
        when (action) {
            TaskHomeAction.Load -> {
                if (_state.value.content is TaskHomeContent.Uninitialized) load()
            }
            TaskHomeAction.Retry -> {
                if (_state.value.content is TaskHomeContent.Failure) load()
            }
            is TaskHomeAction.OpenTask -> {
                viewModelScope.launch { _effects.emit(TaskHomeEffect.OpenTask(action.taskId)) }
            }
        }
    }

    private fun load() {
        _state.value = TaskHomeUiState(TaskHomeContent.Loading)
        viewModelScope.launch {
            repository.loadTaskSummaries().fold(
                onSuccess = { summaries ->
                    _state.value =
                        TaskHomeUiState(
                            if (summaries.isEmpty()) TaskHomeContent.Empty else TaskHomeContent.Success(summaries),
                        )
                },
                onFailure = {
                    _state.value = TaskHomeUiState(TaskHomeContent.Failure)
                },
            )
        }
    }
}
