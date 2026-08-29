package com.nexusflow.app.feature.task.presentation.create

import com.nexusflow.app.feature.task.domain.TaskId

data class TaskCreateUiState(
    val requestText: String = "",
    val submission: TaskSubmission = TaskSubmission.Idle,
) {
    val canSubmit: Boolean
        get() = requestText.trim().isNotEmpty() && submission !is TaskSubmission.Submitting
}

sealed interface TaskSubmission {
    data object Idle : TaskSubmission

    data object Submitting : TaskSubmission

    data object Failed : TaskSubmission
}

sealed interface TaskCreateAction {
    data class RequestChanged(
        val text: String,
    ) : TaskCreateAction

    data object Submit : TaskCreateAction

    data object RetrySubmit : TaskCreateAction
}

sealed interface TaskCreateEffect {
    data class OpenTask(
        val taskId: TaskId,
    ) : TaskCreateEffect
}
