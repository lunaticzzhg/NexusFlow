package com.nexusflow.backend.feature.conversation.application

import com.nexusflow.backend.core.observability.OperationLogContext
import com.nexusflow.backend.core.observability.addOperationFields
import com.nexusflow.contracts.backendai.common.AiCapabilityException
import com.nexusflow.contracts.backendai.understanding.TurnIntent
import com.nexusflow.contracts.backendai.understanding.UnderstandMessageResult as AiUnderstandMessageResult
import com.nexusflow.contracts.backendai.understanding.UserMessageUnderstanding
import com.nexusflow.observability.StructuredLogger
import com.nexusflow.observability.logFields
import kotlinx.coroutines.CancellationException

internal class ConversationUnderstandingStep(
    private val understanding: UserMessageUnderstanding?,
    private val payloadMapper: ConversationTurnPayloadMapper,
    private val logger: StructuredLogger?,
) {
    suspend fun execute(context: ConversationTurnContext): AiUnderstandMessageResult? {
        val capability = understanding ?: return null
        logUnderstandingStarted(context)
        val outcome = try {
            capability.understand(
                payloadMapper.aiContext(
                    detail = context.detail,
                    userMessage = context.userMessage,
                    currentTask = context.currentTask,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: AiCapabilityException) {
            return null
        }
        logUnderstandingFinished(context, outcome.turnIntent)
        return outcome
    }

    private fun logUnderstandingStarted(context: ConversationTurnContext) {
        logger?.info(
            component = "conversation",
            event = "turn_understanding_started",
            fields = logFields {
                addOperationFields(context.operationLogContext(), step = "understanding_started", outcome = "started")
                "conversation_id" value context.detail.conversation.id.value.toString()
                "task_id" value context.currentTask?.task?.id?.value?.toString()
                "response_run_id" value context.claim.run.id.value.toString()
                "ai_request_id" value context.userMessage.aiRequestId
            },
        )
    }

    private fun logUnderstandingFinished(
        context: ConversationTurnContext,
        turnIntent: TurnIntent,
    ) {
        logger?.info(
            component = "conversation",
            event = "turn_understanding_finished",
            fields = logFields {
                addOperationFields(
                    context.operationLogContext(branch = turnIntent.operationBranch()),
                    step = "understanding_finished",
                    outcome = turnIntent.operationOutcome(),
                )
                "conversation_id" value context.detail.conversation.id.value.toString()
                "task_id" value context.currentTask?.task?.id?.value?.toString()
                "response_run_id" value context.claim.run.id.value.toString()
                "ai_request_id" value context.userMessage.aiRequestId
                "turn_intent" value turnIntent.name.toSnakeCase()
            },
        )
    }
}

internal fun ConversationTurnContext.operationLogContext(branch: String? = null): OperationLogContext =
    OperationLogContext(
        operationType = CONVERSATION_TURN_OPERATION_TYPE,
        operationId = claim.run.id.value.toString(),
        branch = branch,
        stage = claim.run.stage.name.toSnakeCase(),
    )

internal fun TurnIntent.operationBranch(): String =
    when (this) {
        TurnIntent.Conversation -> CONVERSATION_BRANCH_CHAT_ANSWER
        TurnIntent.Planning -> CONVERSATION_BRANCH_PLANNING
    }

private fun TurnIntent.operationOutcome(): String =
    when (this) {
        TurnIntent.Conversation -> "conversation"
        TurnIntent.Planning -> "planning"
    }

internal const val CONVERSATION_TURN_OPERATION_TYPE = "conversation_turn"
internal const val CONVERSATION_BRANCH_CHAT_ANSWER = "chat_answer"
internal const val CONVERSATION_BRANCH_PLANNING = "planning"

private fun String.toSnakeCase(): String =
    buildString(length + 4) {
        this@toSnakeCase.forEachIndexed { index, character ->
            if (character.isUpperCase() && index > 0) append('_')
            append(character.lowercaseChar())
        }
    }
