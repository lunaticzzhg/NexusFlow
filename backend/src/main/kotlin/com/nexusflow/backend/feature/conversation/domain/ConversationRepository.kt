package com.nexusflow.backend.feature.conversation.domain

import com.nexusflow.backend.feature.responserun.domain.ResponseRunId
import com.nexusflow.backend.feature.conversation.domain.MessageId
import com.nexusflow.backend.feature.task.domain.TaskOwner
import java.time.Instant
import java.util.UUID

interface ConversationRepository {
    suspend fun findConversationDetail(
        owner: TaskOwner,
        conversationId: ConversationId,
    ): ConversationDetail?

    suspend fun findConversationDetailForResponseRun(responseRunId: ResponseRunId): ConversationDetail?
}

interface ConversationTurnStartCommitter {
    suspend fun createConversation(command: CreateConversationCommand): CreateConversationResult

    suspend fun appendUserMessage(command: AppendConversationUserMessageCommand): AppendConversationUserMessageResult
}

data class CreateConversationCommand(
    val owner: TaskOwner,
    val conversationId: ConversationId,
    val firstMessageId: MessageId,
    val creationRequestId: String,
    val clientMessageId: String,
    val text: String,
    val aiRequestId: String,
    val now: Instant,
    val responseRunId: ResponseRunId = ResponseRunId(UUID.randomUUID()),
    val responseDeadlineAt: Instant = now.plusSeconds(DEFAULT_RESPONSE_RUN_DEADLINE_SECONDS),
    val timeZoneId: String = "UTC",
    val originTraceId: String? = null,
)

sealed interface CreateConversationResult {
    data class Created(
        val detail: ConversationDetail,
        val message: ConversationMessage,
    ) : CreateConversationResult

    data class Existing(val detail: ConversationDetail) : CreateConversationResult

    data object ConflictingConversation : CreateConversationResult

    data object ConflictingMessage : CreateConversationResult
}

data class AppendConversationUserMessageCommand(
    val owner: TaskOwner,
    val conversationId: ConversationId,
    val messageId: MessageId,
    val clientMessageId: String,
    val text: String,
    val aiRequestId: String,
    val now: Instant,
    val responseRunId: ResponseRunId = ResponseRunId(UUID.randomUUID()),
    val responseDeadlineAt: Instant = now.plusSeconds(DEFAULT_RESPONSE_RUN_DEADLINE_SECONDS),
    val timeZoneId: String = "UTC",
    val originTraceId: String? = null,
)

sealed interface AppendConversationUserMessageResult {
    data class Appended(
        val detail: ConversationDetail,
        val message: ConversationMessage,
    ) : AppendConversationUserMessageResult

    data class Existing(val detail: ConversationDetail) : AppendConversationUserMessageResult

    data object ConflictingMessage : AppendConversationUserMessageResult

    data object ConversationNotFound : AppendConversationUserMessageResult
}

private const val DEFAULT_RESPONSE_RUN_DEADLINE_SECONDS = 30L * 60L
