package com.nexusflow.app.feature.task.presentation.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nexusflow.app.feature.task.data.newTaskClientId
import com.nexusflow.app.feature.task.domain.GeneratePlansCommand
import com.nexusflow.app.feature.task.domain.SelectPlanCommand
import com.nexusflow.app.feature.task.domain.TaskId
import com.nexusflow.app.feature.task.domain.TaskRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TaskDetailViewModel(
    private val taskId: TaskId,
    private val repository: TaskRepository,
    private val clientRequestIdFactory: () -> String = ::newTaskClientId,
) : ViewModel() {
    private val _state = MutableStateFlow(TaskDetailUiState())
    val state: StateFlow<TaskDetailUiState> = _state.asStateFlow()

    fun onAction(action: TaskDetailAction) {
        when (action) {
            TaskDetailAction.Load -> {
                if (_state.value.content is TaskDetailContent.Uninitialized) load()
            }
            TaskDetailAction.Retry -> load()
            TaskDetailAction.GenerateFixturePlan -> generateFixturePlan()
            is TaskDetailAction.SelectPlan -> selectPlan(action.planId)
        }
    }

    private fun load() {
        _state.value = TaskDetailUiState(TaskDetailContent.Loading)
        viewModelScope.launch {
            repository.loadTaskDetail(taskId).fold(
                onSuccess = { detail -> _state.value = TaskDetailUiState(TaskDetailContent.Success(detail)) },
                onFailure = { _state.value = TaskDetailUiState(TaskDetailContent.Failure) },
            )
        }
    }

    private fun generateFixturePlan() {
        val current = _state.value.content as? TaskDetailContent.Success ?: return
        _state.value = TaskDetailUiState(current.copy(planning = TaskDetailOperation.Loading))
        viewModelScope.launch {
            repository.generatePlans(GeneratePlansCommand(taskId, clientRequestIdFactory())).fold(
                onSuccess = {
                    repository.loadTaskDetail(taskId).fold(
                        onSuccess = { detail -> _state.value = TaskDetailUiState(TaskDetailContent.Success(detail)) },
                        onFailure = { _state.value = TaskDetailUiState(current.copy(planning = TaskDetailOperation.Failed)) },
                    )
                },
                onFailure = { _state.value = TaskDetailUiState(current.copy(planning = TaskDetailOperation.Failed)) },
            )
        }
    }

    private fun selectPlan(planId: com.nexusflow.app.feature.task.domain.PlanId) {
        val current = _state.value.content as? TaskDetailContent.Success ?: return
        _state.value = TaskDetailUiState(current.copy(selectingPlanId = planId))
        viewModelScope.launch {
            repository.selectPlan(SelectPlanCommand(taskId, planId)).fold(
                onSuccess = { detail -> _state.value = TaskDetailUiState(TaskDetailContent.Success(detail)) },
                onFailure = { _state.value = TaskDetailUiState(current.copy(selectingPlanId = null)) },
            )
        }
    }
}
