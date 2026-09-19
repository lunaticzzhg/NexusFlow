package com.nexusflow.backend.feature.conversation.domain

import com.nexusflow.backend.feature.responserun.domain.ResponseRun
import com.nexusflow.backend.feature.conversation.domain.MessageId
import com.nexusflow.backend.feature.conversation.domain.MessageRole
import com.nexusflow.backend.feature.task.domain.TaskOwner
import java.time.Instant
import java.util.UUID

@JvmInline
value class ConversationId(val value: UUID)

data class Conversation(
    val id: ConversationId,
    val owner: TaskOwner,
    val creationRequestId: String,
    val nextTurnIndex: Long,
    val createdAt: Instant,
    val updatedAt: Instant,
    val archivedAt: Instant? = null,
)

data class ConversationMessage(
    val id: MessageId,
    val conversationId: ConversationId,
    val role: MessageRole,
    val content: String,
    val clientMessageId: String?,
    val aiRequestId: String?,
    val turnIndex: Long,
    val understoodAt: Instant?,
    val createdAt: Instant,
)

data class ConversationDetail(
    val conversation: Conversation,
    val messages: List<ConversationMessage>,
    val responseRuns: List<ResponseRun> = emptyList(),
)
