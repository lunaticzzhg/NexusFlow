package com.nexusflow.app.feature.task.data

import com.nexusflow.app.feature.task.domain.TaskId
import com.nexusflow.app.feature.task.domain.TaskSummary

object TaskFixtures {
    val success =
        listOf(
            TaskSummary(
                id = TaskId("task-liverpool-night"),
                title = "Liverpool match night",
                status = "Awaiting approval",
                description = "Create a calendar event and a pre-match reminder",
            ),
        )
}

enum class TaskSummaryFixture {
    Success,
    Empty,
    Failure,
}
