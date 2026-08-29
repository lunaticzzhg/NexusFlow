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
import com.nexusflow.contracts.api.CreateTaskRequest
import com.nexusflow.contracts.api.GeneratePlansRequest
import com.nexusflow.contracts.api.SelectPlanRequest
import com.nexusflow.contracts.api.SendTaskMessageRequest

internal class DefaultTaskRepository(
    private val remoteDataSource: TaskRemoteDataSource,
) : TaskRepository {
    override suspend fun loadTaskSummaries(): Result<List<TaskSummary>> =
        remoteDataSource.listTasks().map { summaries -> summaries.map { it.toDomain() } }

    override suspend fun createTask(command: CreateTaskCommand): Result<TaskDetail> =
        remoteDataSource.createTask(
            CreateTaskRequest(
                clientRequestId = command.creationRequestId,
                goal = command.requestText,
            ),
        ).fold(
            onSuccess = { created ->
                val taskId = TaskId(created.id)
                remoteDataSource.sendMessage(
                    taskId = taskId.value,
                    request =
                        SendTaskMessageRequest(
                            clientMessageId = command.initialMessageId,
                            text = command.requestText,
                            timeZoneId = command.timeZoneId,
                        ),
                ).map { it.toDomain() }
            },
            onFailure = { error -> Result.failure(error) },
        )

    override suspend fun loadTaskDetail(taskId: TaskId): Result<TaskDetail> = remoteDataSource.getTask(taskId.value).map { it.toDomain() }

    override suspend fun sendMessage(command: SendTaskMessageCommand): Result<TaskDetail> =
        remoteDataSource.sendMessage(
            taskId = command.taskId.value,
            request =
                SendTaskMessageRequest(
                    clientMessageId = command.clientMessageId,
                    text = command.text,
                    timeZoneId = command.timeZoneId,
                ),
        ).map { it.toDomain() }

    override suspend fun generatePlans(command: GeneratePlansCommand): Result<List<TaskPlan>> =
        remoteDataSource.generatePlans(
            taskId = command.taskId.value,
            request = GeneratePlansRequest(command.clientRequestId),
        ).map { response -> response.plans.map { it.toDomain() } }

    override suspend fun selectPlan(command: SelectPlanCommand): Result<TaskDetail> =
        remoteDataSource.selectPlan(
            taskId = command.taskId.value,
            request = SelectPlanRequest(command.planId.value),
        ).map { it.toDomain() }
}

internal fun newTaskClientId(): String =
    "task-${kotlinx.datetime.Clock.System.now().toEpochMilliseconds()}-${kotlin.random.Random.nextInt(0, Int.MAX_VALUE).toString(16)}"
