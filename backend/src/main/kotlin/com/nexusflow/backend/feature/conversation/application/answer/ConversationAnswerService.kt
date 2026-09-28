package com.nexusflow.backend.feature.conversation.application.answer

import com.nexusflow.backend.core.observability.addOperationFields
import com.nexusflow.backend.feature.research.application.ReadToolCatalog
import com.nexusflow.backend.feature.research.application.ReadToolExecutor
import com.nexusflow.contracts.backendai.answer.ConversationAnsweringCapability
import com.nexusflow.contracts.backendai.answer.StreamingConversationAnsweringCapability
import com.nexusflow.contracts.backendai.conversation.InformationNeedProposal
import com.nexusflow.contracts.backendai.conversation.ReadOnlyToolDefinitionPayload
import com.nexusflow.observability.StructuredLogger
import com.nexusflow.observability.logFields

class ConversationAnswerService(
    conversationAnswering: ConversationAnsweringCapability?,
    streamingConversationAnswering: StreamingConversationAnsweringCapability? = null,
    private val readToolCatalog: ReadToolCatalog,
    private val readToolExecutor: ReadToolExecutor,
    private val logger: StructuredLogger? = null,
) {
    private val researchCoordinator = ConversationResearchCoordinator(readToolExecutor)
    private val answerStep = ConversationAnswerStep(
        conversationAnswering = conversationAnswering,
        streamingConversationAnswering = streamingConversationAnswering,
    )
    private val validator = ConversationAnswerValidator(
        readToolCatalog = readToolCatalog,
        readToolExecutor = readToolExecutor,
    )

    fun availableReadTools(): List<ReadOnlyToolDefinitionPayload> =
        readToolCatalog.definitions().map { definition ->
            ReadOnlyToolDefinitionPayload(
                toolKey = definition.key.value,
                description = definition.description,
                argumentHint = definition.argumentHint,
            )
        }

    fun maxReadToolCalls(): Int = readToolExecutor.maxCallsPerTurn

    suspend fun answerKnownNeeds(
        request: StandaloneConversationAnswerRequest,
        needs: List<InformationNeedProposal>,
    ): ConversationAnswerResult {
        val turnRequest = request.toTurnRequest()
        logStarted(turnRequest)
        val result = answerKnownNeeds(turnRequest, needs)
        logFinished(turnRequest, result)
        return result
    }

    private suspend fun answerKnownNeeds(
        request: ConversationAnswerTurnRequest,
        needs: List<InformationNeedProposal>,
    ): ConversationAnswerResult {
        val validatedNeeds = validator.validateInformationNeeds(
            com.nexusflow.contracts.backendai.conversation.ConversationDecisionResult(needs),
        ) ?: return ConversationAnswerResult.invalidAiResult(request.assistantMessageId)
        val research = try {
            researchCoordinator.execute(request, validatedNeeds)
        } catch (_: IllegalArgumentException) {
            return ConversationAnswerResult.invalid(request.assistantMessageId)
        }
        // The current decision contract exposes information needs only, so MODEL_ONLY still uses answer synthesis.
        val answer = when (val generated = answerStep.generate(request, validatedNeeds, research)) {
            ConversationAnswerStepResult.AiUnavailable -> return ConversationAnswerResult.aiUnavailable(request.assistantMessageId)
            ConversationAnswerStepResult.InvalidAiResult -> return ConversationAnswerResult.invalidAiResult(request.assistantMessageId)
            is ConversationAnswerStepResult.Success -> generated.answer
        }
        logAnswerGenerated(request, answer.answer.length, research.size)
        return if (validator.validateNeedCoverage(answer, validatedNeeds, research)) {
            validator.resultFromAnswer(request, answer, validatedNeeds, research)
        } else {
            ConversationAnswerResult.invalidAiResult(request.assistantMessageId)
        }
    }

    private fun logStarted(request: ConversationAnswerTurnRequest) {
        logger?.info(
            component = "answer",
            event = "conversation_answer_started",
            fields = logFields {
                addOperationFields(request.operationLogContext, step = "answer_started", outcome = "started")
                "response_run_id" value request.operationLogContext?.operationId
                "conversation_id" value request.conversationId
                "task_id" value request.taskId
            },
        )
    }

    private fun logAnswerGenerated(
        request: ConversationAnswerTurnRequest,
        answerLength: Int,
        researchCount: Int,
    ) {
        logger?.info(
            component = "answer",
            event = "conversation_answer_generated",
            fields = logFields {
                addOperationFields(request.operationLogContext, step = "answer_generated", outcome = "generated")
                "response_run_id" value request.operationLogContext?.operationId
                "conversation_id" value request.conversationId
                "task_id" value request.taskId
                "task_revision" value request.taskRevision
                "answer_length" value answerLength
                "research_need_count" value researchCount
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
                addOperationFields(
                    request.operationLogContext,
                    step = "answer_finished",
                    outcome = result.outcome.name.toSnakeCase(),
                )
                "response_run_id" value request.operationLogContext?.operationId
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
