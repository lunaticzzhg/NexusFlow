package com.nexusflow.app.feature.task.data

import com.nexusflow.app.feature.task.domain.CreateTaskCommand
import com.nexusflow.app.feature.task.domain.RemoveRequirementCommand
import com.nexusflow.app.feature.task.domain.SelectPlanCommand
import com.nexusflow.app.feature.task.domain.SendTaskMessageCommand
import com.nexusflow.app.feature.task.domain.TaskDetail
import com.nexusflow.app.feature.task.domain.TaskId
import com.nexusflow.app.feature.task.domain.TaskRepository
import com.nexusflow.app.feature.task.domain.TaskSummary
import com.nexusflow.app.feature.task.domain.UpdateRequirementCommand
import com.nexusflow.contracts.api.CreateTaskRequest
import com.nexusflow.contracts.api.SendTaskMessageRequest
import com.nexusflow.contracts.api.UpdateRequirementRequest

internal class DefaultTaskRepository(
    private val remoteDataSource: TaskRemoteDataSource,
) : TaskRepository {
    override suspend fun loadTaskSummaries(): Result<List<TaskSummary>> =
        remoteDataSource.listTasks().map { summaries -> summaries.map { it.toDomain() } }

    override suspend fun createTask(command: CreateTaskCommand): Result<TaskDetail> =
        remoteDataSource.createTask(
            CreateTaskRequest(
                clientRequestId = command.creationRequestId,
                message = command.requestText,
                timeZoneId = command.timeZoneId,
            ),
        ).map { it.toDomain() }

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

    override suspend fun updateRequirement(command: UpdateRequirementCommand): Result<TaskDetail> =
        remoteDataSource.updateRequirement(
            taskId = command.taskId.value,
            requirementId = command.requirementId.value,
            request =
                UpdateRequirementRequest(
                    kind = command.kind.toContract(),
                    value = command.value.toContract(command.kind),
                    strength = command.strength.toContract(),
                ),
        ).map { it.toDomain() }

    override suspend fun removeRequirement(command: RemoveRequirementCommand): Result<TaskDetail> =
        remoteDataSource.removeRequirement(
            taskId = command.taskId.value,
            requirementId = command.requirementId.value,
        ).map { it.toDomain() }

    override suspend fun selectPlan(command: SelectPlanCommand): Result<TaskDetail> =
        remoteDataSource.selectPlan(
            taskId = command.taskId.value,
            planId = command.planId.value,
        ).map { it.toDomain() }
}

internal fun newTaskClientId(): String =
    "task-${kotlinx.datetime.Clock.System.now().toEpochMilliseconds()}-${kotlin.random.Random.nextInt(0, Int.MAX_VALUE).toString(16)}"
