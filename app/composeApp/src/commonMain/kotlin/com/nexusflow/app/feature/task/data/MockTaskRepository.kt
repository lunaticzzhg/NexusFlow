package com.nexusflow.app.feature.task.data

import com.nexusflow.app.feature.task.domain.ConversationDetail
import com.nexusflow.app.feature.task.domain.ConversationId
import com.nexusflow.app.feature.task.domain.CreateConversationCommand
import com.nexusflow.app.feature.task.domain.MessageRole
import com.nexusflow.app.feature.task.domain.RemoveRequirementCommand
import com.nexusflow.app.feature.task.domain.ResponseRun
import com.nexusflow.app.feature.task.domain.ResponseRunId
import com.nexusflow.app.feature.task.domain.ResponseRunSnapshot
import com.nexusflow.app.feature.task.domain.SelectPlanCommand
import com.nexusflow.app.feature.task.domain.SendConversationMessageCommand
import com.nexusflow.app.feature.task.domain.TaskDetail
import com.nexusflow.app.feature.task.domain.TaskId
import com.nexusflow.app.feature.task.domain.TaskMessage
import com.nexusflow.app.feature.task.domain.TaskRepository
import com.nexusflow.app.feature.task.domain.TaskSummary
import com.nexusflow.app.feature.task.domain.UpdateRequirementCommand

class MockTaskRepository(
    private val summaryFixture: TaskSummaryFixture = TaskSummaryFixture.Success,
    private val createFailure: Throwable? = null,
) : TaskRepository {
    private val conversations =
        mutableMapOf(
            TaskFixtures.conversation.id to TaskFixtures.conversation,
        )

    override suspend fun loadTaskSummaries(): Result<List<TaskSummary>> =
        when (summaryFixture) {
            TaskSummaryFixture.Success -> Result.success(TaskFixtures.success)
            TaskSummaryFixture.Empty -> Result.success(emptyList())
            TaskSummaryFixture.Failure -> Result.failure(MockTaskRepositoryException)
        }

    override suspend fun createConversation(command: CreateConversationCommand): Result<ConversationDetail> {
        createFailure?.let { return Result.failure(it) }
        val created =
            TaskFixtures.conversation.copy(
                id = ConversationId("conversation-created-demo"),
                messages =
                    TaskFixtures.conversation.messages.map { message ->
                        message.copy(content = command.requestText)
                    },
                currentTask = TaskFixtures.conversation.currentTask?.copy(intent = command.requestText),
            )
        conversations[created.id] = created
        return Result.success(created)
    }

    override suspend fun sendConversationMessage(command: SendConversationMessageCommand): Result<ConversationDetail> {
        val detail = conversations[command.conversationId] ?: return Result.failure(MockTaskRepositoryException)
        val updated =
            detail.copy(
                messages =
                    detail.messages +
                        TaskMessage(
                            id = "message-${detail.messages.size + 1}",
                            role = MessageRole.User,
                            content = command.text,
                            clientMessageId = command.clientMessageId,
                            understoodAt = null,
                        ),
            )
        conversations[updated.id] = updated
        return Result.success(updated)
    }

    override suspend fun loadConversationDetail(conversationId: ConversationId): Result<ConversationDetail> =
        conversations[conversationId]
            ?.let(Result.Companion::success)
            ?: Result.failure(MockTaskRepositoryException)

    override suspend fun loadTaskDetail(taskId: TaskId): Result<TaskDetail> =
        TaskFixtures.detail
            .takeIf { it.id == taskId }
            ?.let(Result.Companion::success)
            ?: Result.failure(MockTaskRepositoryException)

    override suspend fun loadResponseRunSnapshot(
        conversationId: ConversationId,
        responseRunId: ResponseRunId,
    ): Result<ResponseRunSnapshot> = Result.failure(MockTaskRepositoryException)

    override suspend fun cancelResponseRun(
        conversationId: ConversationId,
        responseRunId: ResponseRunId,
    ): Result<ResponseRun> = Result.failure(MockTaskRepositoryException)

    override suspend fun retryResponseRun(
        conversationId: ConversationId,
        responseRunId: ResponseRunId,
    ): Result<ResponseRun> = Result.failure(MockTaskRepositoryException)

    override suspend fun updateRequirement(command: UpdateRequirementCommand): Result<TaskDetail> = loadTaskDetail(command.taskId)

    override suspend fun removeRequirement(command: RemoveRequirementCommand): Result<TaskDetail> =
        loadTaskDetail(command.taskId).map { detail ->
            detail.copy(requirements = detail.requirements.filterNot { it.id == command.requirementId })
        }

    override suspend fun selectPlan(command: SelectPlanCommand): Result<TaskDetail> =
        loadTaskDetail(command.taskId).map { detail ->
            detail.copy(selectedPlanId = command.planId)
        }
}

object MockTaskRepositoryException : IllegalStateException("Mock task data is unavailable")
