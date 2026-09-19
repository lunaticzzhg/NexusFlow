package com.nexusflow.backend.feature.conversation.application.answer

import com.nexusflow.backend.feature.research.application.ReadToolCatalog
import com.nexusflow.backend.feature.research.application.ReadToolExecutor
import com.nexusflow.contracts.backendai.answer.ConversationAnsweringCapability
import com.nexusflow.contracts.backendai.answer.StreamingConversationAnsweringCapability
import com.nexusflow.contracts.backendai.conversation.ConversationDecisionCapability
import com.nexusflow.observability.StructuredLogger
import com.nexusflow.observability.logFields

class ConversationAnswerService(
    conversationDecision: ConversationDecisionCapability?,
    conversationAnswering: ConversationAnsweringCapability?,
    streamingConversationAnswering: StreamingConversationAnsweringCapability? = null,
    readToolCatalog: ReadToolCatalog,
    readToolExecutor: ReadToolExecutor,
    private val logger: StructuredLogger? = null,
) {
    private val decisionStep = ConversationDecisionStep(
        conversationDecision = conversationDecision,
        readToolCatalog = readToolCatalog,
        readToolExecutor = readToolExecutor,
    )
    private val researchCoordinator = ConversationResearchCoordinator(readToolExecutor)
    private val answerStep = ConversationAnswerStep(
        conversationAnswering = conversationAnswering,
        streamingConversationAnswering = streamingConversationAnswering,
    )
    private val validator = ConversationAnswerValidator(
        readToolCatalog = readToolCatalog,
        readToolExecutor = readToolExecutor,
    )

    suspend fun answer(request: StandaloneConversationAnswerRequest): ConversationAnswerResult =
        answer(request.toTurnRequest())

    private suspend fun answer(request: ConversationAnswerTurnRequest): ConversationAnswerResult {
        logStarted(request)
        val result = when (val decision = decisionStep.decide(request)) {
            ConversationDecisionStepResult.AiUnavailable -> ConversationAnswerResult.aiUnavailable(request.assistantMessageId)
            ConversationDecisionStepResult.InvalidAiResult -> ConversationAnswerResult.invalidAiResult(request.assistantMessageId)
            is ConversationDecisionStepResult.Success -> answerDecision(request, decision)
        }
        logFinished(request, result)
        return result
    }

    private suspend fun answerDecision(
        request: ConversationAnswerTurnRequest,
        decision: ConversationDecisionStepResult.Success,
    ): ConversationAnswerResult {
        val needs = validator.validateInformationNeeds(decision.decision)
            ?: return ConversationAnswerResult.invalidAiResult(request.assistantMessageId)
        val research = try {
            researchCoordinator.execute(request, needs)
        } catch (_: IllegalArgumentException) {
            return ConversationAnswerResult.invalid(request.assistantMessageId)
        }
        // The current decision contract exposes information needs only, so MODEL_ONLY still uses answer synthesis.
        val answer = when (val generated = answerStep.generate(request, needs, research)) {
            ConversationAnswerStepResult.AiUnavailable -> return ConversationAnswerResult.aiUnavailable(request.assistantMessageId)
            ConversationAnswerStepResult.InvalidAiResult -> return ConversationAnswerResult.invalidAiResult(request.assistantMessageId)
            is ConversationAnswerStepResult.Success -> generated.answer
        }
        return if (validator.validateNeedCoverage(answer, needs, research)) {
            validator.resultFromAnswer(request, answer, needs, research)
        } else {
            ConversationAnswerResult.invalidAiResult(request.assistantMessageId)
        }
    }

    private fun logStarted(request: ConversationAnswerTurnRequest) {
        logger?.info(
            component = "answer",
            event = "conversation_answer_started",
            fields = logFields {
                "conversation_id" value request.conversationId
                "task_id" value request.taskId
            },
        )
    }

    private fun logFinished(
        request: ConversationAnswerTurnRequest,
        result: ConversationAnswerResult,
    ) {
        logger?.info(
            component = "answer",
            event = "conversation_answer_finished",
            fields = logFields {
                "conversation_id" value request.conversationId
                "task_id" value request.taskId
                "answer_outcome" value result.outcome.name.toSnakeCase()
                "unavailable_source_count" value result.unavailableSourceCount
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
