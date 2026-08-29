package com.nexusflow.app.feature.task.presentation.detail

import com.nexusflow.app.feature.task.domain.PlanId
import com.nexusflow.app.feature.task.domain.TaskDetail

data class TaskDetailUiState(
    val content: TaskDetailContent = TaskDetailContent.Uninitialized,
)

sealed interface TaskDetailContent {
    data object Uninitialized : TaskDetailContent

    data object Loading : TaskDetailContent

    data class Success(
        val detail: TaskDetail,
        val planning: TaskDetailOperation = TaskDetailOperation.Idle,
        val selectingPlanId: PlanId? = null,
    ) : TaskDetailContent

    data object Failure : TaskDetailContent
}

sealed interface TaskDetailOperation {
    data object Idle : TaskDetailOperation

    data object Loading : TaskDetailOperation

    data object Failed : TaskDetailOperation
}

sealed interface TaskDetailAction {
    data object Load : TaskDetailAction

    data object Retry : TaskDetailAction

    data object GenerateFixturePlan : TaskDetailAction

    data class SelectPlan(
        val planId: PlanId,
    ) : TaskDetailAction
}
