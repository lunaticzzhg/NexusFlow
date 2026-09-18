package com.nexusflow.app.feature.task.data

import com.nexusflow.app.feature.task.domain.ConversationDetail
import com.nexusflow.app.feature.task.domain.ConversationId
import com.nexusflow.app.feature.task.domain.CreateConversationCommand
import com.nexusflow.app.feature.task.domain.RemoveRequirementCommand
import com.nexusflow.app.feature.task.domain.ResponseRun
import com.nexusflow.app.feature.task.domain.ResponseRunId
import com.nexusflow.app.feature.task.domain.ResponseRunSnapshot
import com.nexusflow.app.feature.task.domain.SelectPlanCommand
import com.nexusflow.app.feature.task.domain.SendConversationMessageCommand
import com.nexusflow.app.feature.task.domain.TaskDetail
import com.nexusflow.app.feature.task.domain.TaskId
import com.nexusflow.app.feature.task.domain.TaskRepository
import com.nexusflow.app.feature.task.domain.TaskSummary
import com.nexusflow.app.feature.task.domain.UpdateRequirementCommand
import com.nexusflow.contracts.appbackend.conversation.CreateConversationRequest
import com.nexusflow.contracts.appbackend.conversation.SendConversationMessageRequest
import com.nexusflow.contracts.appbackend.task.UpdateRequirementRequest

internal class DefaultTaskRepository(
    private val remoteDataSource: TaskRemoteDataSource,
) : TaskRepository {
    override suspend fun loadTaskSummaries(): Result<List<TaskSummary>> =
        remoteDataSource.listTasks().map { summaries -> summaries.map { it.toDomain() } }

    override suspend fun createConversation(command: CreateConversationCommand): Result<ConversationDetail> =
        remoteDataSource.createConversation(
            CreateConversationRequest(
                clientRequestId = command.creationRequestId,
                message = command.requestText,
                timeZoneId = command.timeZoneId,
            ),
        ).map { it.toDomain() }

    override suspend fun loadTaskDetail(taskId: TaskId): Result<TaskDetail> = remoteDataSource.getTask(taskId.value).map { it.toDomain() }

    override suspend fun loadConversationDetail(conversationId: ConversationId): Result<ConversationDetail> =
        remoteDataSource.getConversation(conversationId.value).map { it.toDomain() }

    override suspend fun sendConversationMessage(command: SendConversationMessageCommand): Result<ConversationDetail> =
        remoteDataSource.sendConversationMessage(
            conversationId = command.conversationId.value,
            request =
                SendConversationMessageRequest(
                    clientMessageId = command.clientMessageId,
                    text = command.text,
                    timeZoneId = command.timeZoneId,
                ),
        ).map { it.toDomain() }

    override suspend fun loadResponseRunSnapshot(
        conversationId: ConversationId,
        responseRunId: ResponseRunId,
    ): Result<ResponseRunSnapshot> =
        remoteDataSource.getResponseRunSnapshot(
            conversationId = conversationId.value,
            responseRunId = responseRunId.value,
        ).map { it.toDomain() }

    override suspend fun cancelResponseRun(
        conversationId: ConversationId,
        responseRunId: ResponseRunId,
    ): Result<ResponseRun> =
        remoteDataSource.cancelResponseRun(
            conversationId = conversationId.value,
            responseRunId = responseRunId.value,
        ).map { it.toDomain() }

    override suspend fun retryResponseRun(
        conversationId: ConversationId,
        responseRunId: ResponseRunId,
    ): Result<ResponseRun> =
        remoteDataSource.retryResponseRun(
            conversationId = conversationId.value,
            responseRunId = responseRunId.value,
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
