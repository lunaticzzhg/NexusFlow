package com.nexusflow.app.feature.task.domain

interface TaskRepository {
    suspend fun loadTaskSummaries(): Result<List<TaskSummary>>

    suspend fun createConversation(command: CreateConversationCommand): Result<ConversationDetail>

    suspend fun loadConversationDetail(conversationId: ConversationId): Result<ConversationDetail>

    suspend fun loadTaskDetail(taskId: TaskId): Result<TaskDetail>

    suspend fun sendConversationMessage(command: SendConversationMessageCommand): Result<ConversationDetail>

    suspend fun loadResponseRunSnapshot(
        conversationId: ConversationId,
        responseRunId: ResponseRunId,
    ): Result<ResponseRunSnapshot>

    suspend fun cancelResponseRun(
        conversationId: ConversationId,
        responseRunId: ResponseRunId,
    ): Result<ResponseRun>

    suspend fun retryResponseRun(
        conversationId: ConversationId,
        responseRunId: ResponseRunId,
    ): Result<ResponseRun>

    suspend fun updateRequirement(command: UpdateRequirementCommand): Result<TaskDetail>

    suspend fun removeRequirement(command: RemoveRequirementCommand): Result<TaskDetail>

    suspend fun selectPlan(command: SelectPlanCommand): Result<TaskDetail>
}
