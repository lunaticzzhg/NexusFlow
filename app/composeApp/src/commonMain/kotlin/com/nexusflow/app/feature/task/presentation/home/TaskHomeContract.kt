package com.nexusflow.app.feature.task.presentation.home

import com.nexusflow.app.feature.task.domain.TaskSummary

data class TaskHomeUiState(
    val content: TaskHomeContent = TaskHomeContent.Uninitialized,
)

sealed interface TaskHomeContent {
    data object Uninitialized : TaskHomeContent

    data object Loading : TaskHomeContent

    data class Success(
        val summaries: List<TaskSummary>,
    ) : TaskHomeContent

    data object Empty : TaskHomeContent

    data object Failure : TaskHomeContent
}

sealed interface TaskHomeIntent {
    data object Load : TaskHomeIntent

    data object Retry : TaskHomeIntent
}
