package com.nexusflow.app.feature.task.domain

import kotlin.jvm.JvmInline

@JvmInline
value class TaskId(
    val value: String,
)

data class TaskSummary(
    val id: TaskId,
    val title: String,
    val status: String,
    val description: String,
)

data class CreateTaskCommand(
    val requestText: String,
)

data class TaskReference(
    val id: TaskId,
    val title: String,
)
