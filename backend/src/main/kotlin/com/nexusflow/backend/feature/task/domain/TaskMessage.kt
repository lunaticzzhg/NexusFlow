package com.nexusflow.backend.feature.task.domain

import java.time.Instant

enum class MessageRole {
    User,
    Assistant,
}

data class TaskMessage(
    val id: MessageId,
    val taskId: TaskId,
    val role: MessageRole,
    val content: String,
    val clientMessageId: String?,
    val aiRequestId: String?,
    val understoodAt: Instant?,
    val createdAt: Instant,
)
