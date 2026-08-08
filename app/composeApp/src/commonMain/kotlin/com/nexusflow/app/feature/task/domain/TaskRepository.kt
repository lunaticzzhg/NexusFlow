package com.nexusflow.app.feature.task.domain

interface TaskRepository {
    suspend fun loadTaskSummaries(): Result<List<TaskSummary>>

    suspend fun createTask(command: CreateTaskCommand): Result<TaskReference>
}
