package com.nexusflow.app.feature.task.domain

interface TaskRepository {
    suspend fun loadTaskSummaries(): Result<List<TaskSummary>>

    suspend fun createTask(command: CreateTaskCommand): Result<TaskDetail>

    suspend fun loadTaskDetail(taskId: TaskId): Result<TaskDetail>

    suspend fun sendMessage(command: SendTaskMessageCommand): Result<TaskDetail>

    suspend fun generatePlans(command: GeneratePlansCommand): Result<List<TaskPlan>>

    suspend fun selectPlan(command: SelectPlanCommand): Result<TaskDetail>
}
