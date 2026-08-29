package com.nexusflow.app.feature.task.data

import com.nexusflow.app.feature.task.domain.TaskDetail
import com.nexusflow.app.feature.task.domain.TaskId
import com.nexusflow.app.feature.task.domain.TaskMessage
import com.nexusflow.app.feature.task.domain.TaskState
import com.nexusflow.app.feature.task.domain.TaskSummary

object TaskFixtures {
    val success =
        listOf(
            TaskSummary(
                id = TaskId("task-liverpool-night"),
                title = "Liverpool match night",
                currentGoal = "Create a calendar event and a pre-match reminder",
                state = TaskState.WaitingForApproval,
            ),
        )

    val detail =
        TaskDetail(
            id = success.first().id,
            title = success.first().title,
            currentGoal = success.first().currentGoal,
            state = success.first().state,
            version = 1,
            constraints = emptyList(),
            messages = listOf(TaskMessage(com.nexusflow.app.feature.task.domain.MessageRole.User, "Watch Liverpool this weekend")),
            plans = emptyList(),
            selectedPlanId = null,
        )
}

enum class TaskSummaryFixture {
    Success,
    Empty,
    Failure,
}
