package com.nexusflow.app.feature.task.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexusflow.app.feature.task.domain.TaskRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TaskHomeViewModel(
    private val repository: TaskRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(TaskHomeUiState(TaskHomeContent.Uninitialized))
    val state: StateFlow<TaskHomeUiState> = _state.asStateFlow()

    fun dispatch(intent: TaskHomeIntent) {
        when (intent) {
            TaskHomeIntent.Load -> {
                if (_state.value.content is TaskHomeContent.Uninitialized) load()
            }
            TaskHomeIntent.Retry -> {
                if (_state.value.content is TaskHomeContent.Failure) load()
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
