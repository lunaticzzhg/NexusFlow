package com.nexusflow.backend.feature.conversation.application

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
                "conversation_id" value context.detail.conversation.id.value.toString()
                "task_id" value context.currentTask?.task?.id?.value?.toString()
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
                "conversation_id" value context.detail.conversation.id.value.toString()
                "task_id" value context.currentTask?.task?.id?.value?.toString()
                "turn_intent" value turnIntent.name.toSnakeCase()
            },
        )
    }
}

private fun String.toSnakeCase(): String =
    buildString(length + 4) {
        this@toSnakeCase.forEachIndexed { index, character ->
            if (character.isUpperCase() && index > 0) append('_')
            append(character.lowercaseChar())
        }
    }
