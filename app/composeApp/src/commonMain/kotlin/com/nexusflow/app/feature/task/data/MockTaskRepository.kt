package com.nexusflow.app.feature.task.data

import com.nexusflow.app.feature.task.domain.CreateTaskCommand
import com.nexusflow.app.feature.task.domain.TaskId
import com.nexusflow.app.feature.task.domain.TaskReference
import com.nexusflow.app.feature.task.domain.TaskRepository
import com.nexusflow.app.feature.task.domain.TaskSummary

class MockTaskRepository(
    private val summaryFixture: TaskSummaryFixture = TaskSummaryFixture.Success,
    private val createFailure: Throwable? = null,
) : TaskRepository {
    override suspend fun loadTaskSummaries(): Result<List<TaskSummary>> =
        when (summaryFixture) {
            TaskSummaryFixture.Success -> Result.success(TaskFixtures.success)
            TaskSummaryFixture.Empty -> Result.success(emptyList())
            TaskSummaryFixture.Failure -> Result.failure(MockTaskRepositoryException)
        }

    override suspend fun createTask(command: CreateTaskCommand): Result<TaskReference> {
        createFailure?.let { return Result.failure(it) }
        return Result.success(
            TaskReference(
                id = TaskId("task-created-demo"),
                title = command.requestText.trim(),
            ),
        )
    }
}

object MockTaskRepositoryException : IllegalStateException("Mock task data is unavailable")
