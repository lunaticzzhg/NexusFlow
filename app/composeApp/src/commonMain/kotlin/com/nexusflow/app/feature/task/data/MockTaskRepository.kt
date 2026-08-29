package com.nexusflow.app.feature.task.data

import com.nexusflow.app.feature.task.domain.CreateTaskCommand
import com.nexusflow.app.feature.task.domain.GeneratePlansCommand
import com.nexusflow.app.feature.task.domain.SelectPlanCommand
import com.nexusflow.app.feature.task.domain.SendTaskMessageCommand
import com.nexusflow.app.feature.task.domain.TaskDetail
import com.nexusflow.app.feature.task.domain.TaskId
import com.nexusflow.app.feature.task.domain.TaskPlan
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

    override suspend fun createTask(command: CreateTaskCommand): Result<TaskDetail> {
        createFailure?.let { return Result.failure(it) }
        return Result.success(TaskFixtures.detail.copy(id = TaskId("task-created-demo")))
    }

    override suspend fun loadTaskDetail(taskId: TaskId): Result<TaskDetail> =
        TaskFixtures.detail
            .takeIf { it.id == taskId }
            ?.let(Result.Companion::success)
            ?: Result.failure(MockTaskRepositoryException)

    override suspend fun sendMessage(command: SendTaskMessageCommand): Result<TaskDetail> = loadTaskDetail(command.taskId)

    override suspend fun generatePlans(command: GeneratePlansCommand): Result<List<TaskPlan>> =
        loadTaskDetail(command.taskId).map { it.plans }

    override suspend fun selectPlan(command: SelectPlanCommand): Result<TaskDetail> = loadTaskDetail(command.taskId)
}

object MockTaskRepositoryException : IllegalStateException("Mock task data is unavailable")
