package com.nexusflow.backend.feature.task.domain

import java.time.Instant

data class Conversation(
    val id: ConversationId,
    val taskId: TaskId,
    val createdAt: Instant,
)

enum class MessageRole {
    User,
    Assistant,
}

data class ConversationMessage(
    val id: MessageId,
    val conversationId: ConversationId,
    val role: MessageRole,
    val content: String,
    val clientMessageId: String?,
    val aiRequestId: String?,
    val understoodAt: Instant?,
    val createdAt: Instant,
)
