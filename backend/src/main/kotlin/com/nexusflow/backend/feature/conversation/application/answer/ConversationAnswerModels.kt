package com.nexusflow.backend.feature.conversation.application.answer

import com.nexusflow.backend.core.observability.OperationLogContext
import com.nexusflow.backend.feature.research.application.ReadToolExecutionObserver
import com.nexusflow.backend.feature.conversation.domain.ConversationDetail
import com.nexusflow.backend.feature.conversation.domain.ConversationMessage
import com.nexusflow.backend.feature.task.domain.AssistantMessageWrite
import com.nexusflow.backend.feature.conversation.domain.MessageId
import com.nexusflow.backend.feature.conversation.domain.MessageRole
import com.nexusflow.contracts.backendai.common.ModelContextBlockPayload
import com.nexusflow.contracts.backendai.conversation.ConversationMessagePayload
import com.nexusflow.contracts.backendai.conversation.ConversationMessageRole
import kotlinx.datetime.Instant as ContractInstant
import java.time.Instant

data class StandaloneConversationAnswerRequest(
    val detail: ConversationDetail,
    val userMessage: ConversationMessage,
    val aiRequestId: String,
    val timeZoneId: String,
    val referenceTime: Instant,
    val assistantMessageId: MessageId,
    val optionalContext: List<ModelContextBlockPayload> = emptyList(),
    val actorTenantId: String? = null,
    val actorUserId: String? = null,
    val taskId: String? = null,
    val taskRevision: Long? = null,
    val operationLogContext: OperationLogContext? = null,
    val readToolObserver: ReadToolExecutionObserver? = null,
    val onAnswerDelta: suspend (String) -> Unit = {},
)

internal data class ConversationAnswerTurnRequest(
    val aiRequestId: String,
    val conversationId: String?,
    val taskId: String?,
    val taskRevision: Long?,
    val currentMessage: String,
    val userMessage: ConversationTurnMessage,
    val recentMessages: List<ConversationTurnMessage>,
    val timeZoneId: String,
    val referenceTime: Instant,
    val assistantMessageId: MessageId,
    val optionalContext: List<ModelContextBlockPayload> = emptyList(),
    val actorTenantId: String? = null,
    val actorUserId: String? = null,
    val operationLogContext: OperationLogContext? = null,
    val readToolObserver: ReadToolExecutionObserver? = null,
    val onAnswerDelta: suspend (String) -> Unit = {},
)

internal data class ConversationTurnMessage(
    val id: MessageId,
    val role: MessageRole,
    val content: String,
    val createdAt: Instant,
)

data class ConversationAnswerResult(
    val assistantMessage: AssistantMessageWrite,
    val outcome: ConversationAnswerOutcome,
    val unavailableSourceCount: Int = 0,
) {
    companion object {
        fun assistant(
            messageId: MessageId,
            text: String,
            unavailableSourceCount: Int = 0,
        ): ConversationAnswerResult =
            ConversationAnswerResult(
                assistantMessage = AssistantMessageWrite(messageId, text),
                outcome = ConversationAnswerOutcome.Answered,
                unavailableSourceCount = unavailableSourceCount,
            )

        fun clarification(
            messageId: MessageId,
            text: String,
        ): ConversationAnswerResult =
            ConversationAnswerResult(
                assistantMessage = AssistantMessageWrite(messageId, text),
                outcome = ConversationAnswerOutcome.Clarification,
            )

        fun externalUnavailable(messageId: MessageId): ConversationAnswerResult =
            ConversationAnswerResult(
                assistantMessage = AssistantMessageWrite(messageId, EXTERNAL_UNAVAILABLE_MESSAGE),
                outcome = ConversationAnswerOutcome.ExternalUnavailable,
            )

        fun unavailable(messageId: MessageId): ConversationAnswerResult =
            externalUnavailable(messageId)

        fun capabilityUnavailable(messageId: MessageId): ConversationAnswerResult =
            ConversationAnswerResult(
                assistantMessage = AssistantMessageWrite(messageId, CAPABILITY_UNAVAILABLE_MESSAGE),
                outcome = ConversationAnswerOutcome.CapabilityUnavailable,
            )

        fun aiUnavailable(messageId: MessageId): ConversationAnswerResult =
            ConversationAnswerResult(
                assistantMessage = AssistantMessageWrite(messageId, AI_UNAVAILABLE_MESSAGE),
                outcome = ConversationAnswerOutcome.AiUnavailable,
            )

        fun invalidAiResult(messageId: MessageId): ConversationAnswerResult =
            ConversationAnswerResult(
                assistantMessage = AssistantMessageWrite(messageId, INVALID_AI_RESULT_MESSAGE),
                outcome = ConversationAnswerOutcome.InvalidAiResult,
            )

        fun invalid(messageId: MessageId): ConversationAnswerResult =
            ConversationAnswerResult(
                assistantMessage = AssistantMessageWrite(messageId, INVALID_TOOL_MESSAGE),
                outcome = ConversationAnswerOutcome.InvalidToolRequest,
            )
    }
}

enum class ConversationAnswerOutcome {
    Answered,
    Clarification,
    ExternalUnavailable,
    CapabilityUnavailable,
    AiUnavailable,
    InvalidAiResult,
    InvalidToolRequest,
}

internal fun StandaloneConversationAnswerRequest.toTurnRequest(): ConversationAnswerTurnRequest =
    ConversationAnswerTurnRequest(
        aiRequestId = aiRequestId,
        conversationId = detail.conversation.id.value.toString(),
        taskId = taskId,
        taskRevision = taskRevision,
        currentMessage = userMessage.content,
        userMessage = userMessage.toConversationTurnMessage(),
        recentMessages = detail.messages
            .filter { message -> message.id != userMessage.id && message.createdAt.isBefore(userMessage.createdAt) }
            .sortedBy { it.createdAt }
            .takeLast(MAX_RECENT_MESSAGES)
            .map { it.toConversationTurnMessage() },
        timeZoneId = timeZoneId,
        referenceTime = referenceTime,
        assistantMessageId = assistantMessageId,
        optionalContext = optionalContext,
        actorTenantId = actorTenantId,
        actorUserId = actorUserId,
        operationLogContext = operationLogContext,
        readToolObserver = readToolObserver,
        onAnswerDelta = onAnswerDelta,
    )

internal fun ConversationTurnMessage.toPayload(): ConversationMessagePayload =
    ConversationMessagePayload(
        role = if (role == MessageRole.User) ConversationMessageRole.User else ConversationMessageRole.Assistant,
        content = content,
    )

internal fun Instant.toContractInstant(): ContractInstant =
    ContractInstant.fromEpochSeconds(epochSecond, nano.toLong())

private fun ConversationMessage.toConversationTurnMessage(): ConversationTurnMessage =
    ConversationTurnMessage(id = id, role = role, content = content, createdAt = createdAt)

private const val MAX_RECENT_MESSAGES = 8
private const val EXTERNAL_UNAVAILABLE_MESSAGE = "暂时无法获取这项实时资料，请稍后再试。"
private const val CAPABILITY_UNAVAILABLE_MESSAGE = "当前还没有可用的信息源来查询这项实时资料。"
private const val AI_UNAVAILABLE_MESSAGE = "暂时无法生成可靠回答，请稍后再试。"
private const val INVALID_AI_RESULT_MESSAGE = "我暂时无法生成可靠回答，请换个说法再试一次。"
private const val INVALID_TOOL_MESSAGE = "我无法安全使用这项资料来源，请换个说法再试一次。"
