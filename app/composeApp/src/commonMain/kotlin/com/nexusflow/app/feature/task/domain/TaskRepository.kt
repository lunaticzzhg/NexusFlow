package com.nexusflow.app.feature.task.domain

interface TaskRepository {
    suspend fun loadTaskSummaries(): Result<List<TaskSummary>>

    suspend fun createTask(command: CreateTaskCommand): Result<TaskDetail>

    suspend fun loadTaskDetail(taskId: TaskId): Result<TaskDetail>

    suspend fun sendMessage(command: SendTaskMessageCommand): Result<TaskDetail>

    suspend fun updateRequirement(command: UpdateRequirementCommand): Result<TaskDetail>

    suspend fun removeRequirement(command: RemoveRequirementCommand): Result<TaskDetail>

    suspend fun selectPlan(command: SelectPlanCommand): Result<TaskDetail>
}
