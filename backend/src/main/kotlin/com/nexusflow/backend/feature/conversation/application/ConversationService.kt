package com.nexusflow.backend.feature.conversation.application

import com.nexusflow.backend.core.identity.ActorContext
import com.nexusflow.backend.core.observability.BackendTraceContext
import com.nexusflow.backend.feature.conversation.domain.AppendConversationUserMessageCommand
import com.nexusflow.backend.feature.conversation.domain.AppendConversationUserMessageResult
import com.nexusflow.backend.feature.conversation.domain.ConversationDetail
import com.nexusflow.backend.feature.conversation.domain.ConversationId
import com.nexusflow.backend.feature.conversation.domain.ConversationRepository
import com.nexusflow.backend.feature.conversation.domain.CreateConversationCommand
import com.nexusflow.backend.feature.conversation.domain.CreateConversationResult
import com.nexusflow.backend.feature.conversation.domain.ResponseRunId
import com.nexusflow.backend.feature.task.application.ConversationAnswerService
import com.nexusflow.backend.feature.task.application.InvalidTaskRequestException
import com.nexusflow.backend.feature.task.application.MissingTaskScopeException
import com.nexusflow.backend.feature.task.application.PlanningOutcome
import com.nexusflow.backend.feature.task.application.PlanningService
import com.nexusflow.backend.feature.task.application.TaskConflictException
import com.nexusflow.backend.feature.task.application.TaskNotFoundException
import com.nexusflow.backend.feature.task.domain.MessageId
import com.nexusflow.backend.feature.task.domain.MessageRole
import com.nexusflow.backend.feature.task.domain.TaskDetail
import com.nexusflow.backend.feature.task.domain.TaskOwner
import com.nexusflow.backend.feature.task.domain.TaskRepository
import com.nexusflow.backend.feature.task.domain.TenantId
import com.nexusflow.backend.feature.task.domain.UserId
import com.nexusflow.contracts.backendai.understanding.UserMessageUnderstanding
import com.nexusflow.observability.StructuredLogger
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.ZoneId
import java.util.UUID

class ConversationService(
    private val conversationRepository: ConversationRepository,
    private val taskRepository: TaskRepository,
    @Suppress("UNUSED_PARAMETER") planningService: PlanningService,
    @Suppress("UNUSED_PARAMETER") understanding: UserMessageUnderstanding? = null,
    @Suppress("UNUSED_PARAMETER") conversationAnswerService: ConversationAnswerService? = null,
    @Suppress("UNUSED_PARAMETER") logger: StructuredLogger? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val uuidFactory: () -> UUID = UUID::randomUUID,
    private val responseRunMaxDuration: Duration = DEFAULT_RESPONSE_RUN_MAX_DURATION,
) {
    suspend fun createConversation(
        actor: ActorContext,
        clientRequestId: String,
        message: String,
        timeZoneId: String,
    ): ConversationMutationResult {
        actor.requireScope(WRITE_SCOPE)
        val owner = actor.taskOwner()
        val requestId = clientRequestId.requireBounded("clientRequestId", MAX_ID_LENGTH)
        val trimmedMessage = message.requireBounded("message", MAX_MESSAGE_LENGTH)
        timeZoneId.requireBounded("timeZoneId", MAX_TIME_ZONE_LENGTH).requireValidTimeZoneId()
        val aiRequestId = "understand-${uuidFactory()}"
        val now = clock.instant()
        val originTraceId = BackendTraceContext.currentTraceId()?.value
        val detail = when (
            val result = conversationRepository.createConversation(
                CreateConversationCommand(
                    owner = owner,
                    conversationId = ConversationId(uuidFactory()),
                    firstMessageId = MessageId(uuidFactory()),
                    creationRequestId = requestId,
                    clientMessageId = requestId,
                    text = trimmedMessage,
                    aiRequestId = aiRequestId,
                    responseRunId = ResponseRunId(uuidFactory()),
                    responseDeadlineAt = responseDeadlineAt(now),
                    originTraceId = originTraceId,
                    now = now,
                ),
            )
        ) {
            is CreateConversationResult.Created -> result.detail
            is CreateConversationResult.Existing -> {
                return continueExistingRequestIfNeeded(owner, result.detail, requestId, trimmedMessage)
            }
            CreateConversationResult.ConflictingConversation,
            CreateConversationResult.ConflictingMessage,
            -> throw TaskConflictException()
        }

        return ConversationMutationResult(
            detail = detail,
            currentTask = taskRepository.findCurrentTaskForConversation(owner, detail.conversation.id),
        )
    }

    suspend fun sendMessage(
        actor: ActorContext,
        conversationId: String,
        clientMessageId: String,
        text: String,
        timeZoneId: String,
    ): ConversationMutationResult {
        actor.requireScope(WRITE_SCOPE)
        val owner = actor.taskOwner()
        val parsedConversationId = conversationId.toConversationId()
        val parsedClientMessageId = clientMessageId.requireBounded("clientMessageId", MAX_ID_LENGTH)
        val trimmedText = text.requireBounded("text", MAX_MESSAGE_LENGTH)
        timeZoneId.requireBounded("timeZoneId", MAX_TIME_ZONE_LENGTH).requireValidTimeZoneId()
        val aiRequestId = "understand-${uuidFactory()}"
        val now = clock.instant()
        val originTraceId = BackendTraceContext.currentTraceId()?.value
        val detail = when (
            val result = conversationRepository.appendUserMessage(
                AppendConversationUserMessageCommand(
                    owner = owner,
                    conversationId = parsedConversationId,
                    messageId = MessageId(uuidFactory()),
                    clientMessageId = parsedClientMessageId,
                    text = trimmedText,
                    aiRequestId = aiRequestId,
                    responseRunId = ResponseRunId(uuidFactory()),
                    responseDeadlineAt = responseDeadlineAt(now),
                    originTraceId = originTraceId,
                    now = now,
                ),
            )
        ) {
            is AppendConversationUserMessageResult.Appended -> result.detail
            is AppendConversationUserMessageResult.Existing -> {
                return continueExistingRequestIfNeeded(owner, result.detail, parsedClientMessageId, trimmedText)
            }
            AppendConversationUserMessageResult.ConflictingMessage -> throw TaskConflictException()
            AppendConversationUserMessageResult.ConversationNotFound -> throw TaskNotFoundException()
        }

        return ConversationMutationResult(
            detail = detail,
            currentTask = taskRepository.findCurrentTaskForConversation(owner, detail.conversation.id),
        )
    }

    suspend fun getConversation(
        actor: ActorContext,
        conversationId: String,
    ): ConversationMutationResult {
        actor.requireScope(READ_SCOPE)
        val owner = actor.taskOwner()
        val parsedConversationId = conversationId.toConversationId()
        val detail = conversationRepository.findConversationDetail(owner, parsedConversationId) ?: throw TaskNotFoundException()
        return ConversationMutationResult(
            detail = detail,
            currentTask = taskRepository.findCurrentTaskForConversation(owner, parsedConversationId),
        )
    }

    private suspend fun continueExistingRequestIfNeeded(
        owner: TaskOwner,
        detail: ConversationDetail,
        clientMessageId: String,
        text: String,
    ): ConversationMutationResult {
        detail.messages.firstOrNull { message ->
            message.role == MessageRole.User &&
                message.clientMessageId == clientMessageId &&
                message.content == text
        } ?: throw TaskConflictException()
        val currentTask = taskRepository.findCurrentTaskForConversation(owner, detail.conversation.id)
        return ConversationMutationResult(detail = detail, currentTask = currentTask)
    }

    private fun String.toConversationId(): ConversationId = ConversationId(toUuid("conversationId"))

    private fun ActorContext.taskOwner(): TaskOwner =
        TaskOwner(
            tenantId = TenantId(tenantId.toUuid("tenantId")),
            userId = UserId(userId.toUuid("userId")),
        )

    private fun ActorContext.requireScope(scope: String) {
        if (!hasScope(scope)) throw MissingTaskScopeException()
    }

    private fun String.toUuid(fieldName: String): UUID =
        try {
            UUID.fromString(this)
        } catch (_: IllegalArgumentException) {
            throw InvalidTaskRequestException("$fieldName is invalid")
        }

    private fun String.requireBounded(
        fieldName: String,
        maxLength: Int,
    ): String {
        val trimmed = trim()
        if (trimmed.isBlank() || trimmed.length > maxLength) {
            throw InvalidTaskRequestException("$fieldName is invalid")
        }
        return trimmed
    }

    private fun String.requireValidTimeZoneId(): String =
        try {
            ZoneId.of(this).id
        } catch (_: DateTimeException) {
            throw InvalidTaskRequestException("timeZoneId is invalid")
        }

    private fun responseDeadlineAt(now: java.time.Instant): java.time.Instant =
        now.plus(responseRunMaxDuration)
}

data class ConversationMutationResult(
    val detail: ConversationDetail,
    val currentTask: TaskDetail? = null,
    val planningOutcome: PlanningOutcome = PlanningOutcome.NotAttempted,
    val terminalKind: ConversationTurnTerminalKind = planningOutcome.toTerminalKind(),
    val planningTriggered: Boolean = planningOutcome != PlanningOutcome.NotAttempted,
)

enum class ConversationTurnTerminalKind {
    None,
    Planning,
    Clarification,
    AssistantMessage,
    InformationUnavailable,
    InvalidTurn,
}

private fun PlanningOutcome.toTerminalKind(): ConversationTurnTerminalKind =
    when (this) {
        PlanningOutcome.NotAttempted -> ConversationTurnTerminalKind.None
        PlanningOutcome.Ready,
        PlanningOutcome.NoCandidates,
        PlanningOutcome.NoFeasiblePlan,
        PlanningOutcome.Unavailable,
        PlanningOutcome.Superseded,
        -> ConversationTurnTerminalKind.Planning
    }

private const val READ_SCOPE = "orbit.tasks.read"
private const val WRITE_SCOPE = "orbit.tasks.write"
private const val MAX_ID_LENGTH = 128
private const val MAX_MESSAGE_LENGTH = 4_000
private const val MAX_TIME_ZONE_LENGTH = 128
private val DEFAULT_RESPONSE_RUN_MAX_DURATION: Duration = Duration.ofMinutes(30)
