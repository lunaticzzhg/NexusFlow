package com.nexusflow.backend.feature.conversation.application.answer

import com.nexusflow.backend.feature.research.application.ReadToolCatalog
import com.nexusflow.backend.feature.research.application.ReadToolExecutor
import com.nexusflow.contracts.backendai.common.AiCapabilityException
import com.nexusflow.contracts.backendai.common.InvalidCapabilityResultException
import com.nexusflow.contracts.backendai.common.StructuredModelRequestDiagnostics
import com.nexusflow.contracts.backendai.conversation.ConversationDecisionCapability
import com.nexusflow.contracts.backendai.conversation.ConversationDecisionRequest
import com.nexusflow.contracts.backendai.conversation.ConversationDecisionResult
import com.nexusflow.contracts.backendai.conversation.ReadOnlyToolDefinitionPayload
import kotlinx.coroutines.CancellationException

internal class ConversationDecisionStep(
    private val conversationDecision: ConversationDecisionCapability?,
    private val readToolCatalog: ReadToolCatalog,
    private val readToolExecutor: ReadToolExecutor,
) {
    suspend fun decide(request: ConversationAnswerTurnRequest): ConversationDecisionStepResult {
        val capability = conversationDecision ?: return ConversationDecisionStepResult.AiUnavailable
        val decision = try {
            capability.decide(request.toDecisionRequest())
        } catch (error: CancellationException) {
            throw error
        } catch (_: InvalidCapabilityResultException) {
            return ConversationDecisionStepResult.InvalidAiResult
        } catch (_: AiCapabilityException) {
            return ConversationDecisionStepResult.AiUnavailable
        }
        return ConversationDecisionStepResult.Success(decision)
    }

    private fun ConversationAnswerTurnRequest.toDecisionRequest(): ConversationDecisionRequest =
        ConversationDecisionRequest(
            aiRequestId = aiRequestId,
            conversationId = conversationId,
            taskId = taskId,
            taskRevision = taskRevision,
            currentMessage = currentMessage,
            recentMessages = recentMessages.map { message -> message.toPayload() },
            referenceTime = referenceTime.toContractInstant(),
            timeZoneId = timeZoneId,
            optionalContext = optionalContext,
            availableReadTools = readToolCatalog.definitions().map { definition ->
                ReadOnlyToolDefinitionPayload(
                    toolKey = definition.key.value,
                    description = definition.description,
                    argumentHint = definition.argumentHint,
                )
            },
            maxReadToolCalls = readToolExecutor.maxCallsPerTurn,
            diagnostics = StructuredModelRequestDiagnostics(
                includedContextBlockCount = optionalContext.size,
                resolvedContextBlockCount = optionalContext.size,
            ),
        )
}

internal sealed interface ConversationDecisionStepResult {
    data class Success(val decision: ConversationDecisionResult) : ConversationDecisionStepResult
    data object AiUnavailable : ConversationDecisionStepResult
    data object InvalidAiResult : ConversationDecisionStepResult
}
