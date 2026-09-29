package com.nexusflow.backend.feature.conversation.application.answer

import com.nexusflow.backend.feature.research.application.ReadToolCatalog
import com.nexusflow.backend.feature.research.application.ReadToolExecutor
import com.nexusflow.backend.feature.task.domain.AssistantMessageWrite
import com.nexusflow.contracts.backendai.answer.AnswerNeedCoveragePayload
import com.nexusflow.contracts.backendai.answer.AnswerNeedCoverageStatus
import com.nexusflow.contracts.backendai.answer.ComposeConversationAnswerResult
import com.nexusflow.contracts.backendai.answer.ResearchIssueType
import com.nexusflow.contracts.backendai.conversation.ConversationDecisionResult
import com.nexusflow.contracts.backendai.conversation.InformationNeedMode
import com.nexusflow.contracts.backendai.conversation.InformationNeedProposal

internal class ConversationAnswerValidator(
    private val readToolCatalog: ReadToolCatalog,
    private val readToolExecutor: ReadToolExecutor,
) {
    fun validateInformationNeeds(decision: ConversationDecisionResult): List<InformationNeedProposal>? {
        val offeredKeys = readToolCatalog.definitions().mapTo(linkedSetOf()) { it.key.value }
        val needs = decision.informationNeeds.map { need ->
            need.copy(
                id = need.id.trim(),
                question = need.question.trim(),
                requestedCapabilityHint = need.requestedCapabilityHint?.trim()?.takeIf(String::isNotBlank),
                toolCalls = need.toolCalls.map { call -> call.copy(toolKey = call.toolKey.trim()) },
            )
        }
        if (needs.isEmpty()) return null
        if (needs.any { it.id.isBlank() || it.question.isBlank() }) return null
        if (needs.map { it.id }.toSet().size != needs.size) return null
        val allToolCalls = needs.flatMap { it.toolCalls }
        if (allToolCalls.distinctBy { it.toolKey to it.arguments }.size > readToolExecutor.maxCallsPerTurn) return null
        if (allToolCalls.any { it.toolKey.isBlank() || it.toolKey !in offeredKeys }) return null
        if (needs.any { need ->
                need.mode == InformationNeedMode.MODEL_ONLY &&
                    (need.toolCalls.isNotEmpty() || need.requestedCapabilityHint != null)
            }
        ) {
            return null
        }
        return needs
    }

    fun validateNeedCoverage(
        answer: ComposeConversationAnswerResult,
        needs: List<InformationNeedProposal>,
        research: List<NeedResearch>,
    ): Boolean {
        if (answer.answer.isBlank()) return false
        val needsById = needs.associateBy { it.id }
        val researchByNeed = research.associateBy { it.needId }
        val coverageIds = answer.coverage.map { it.needId }
        if (coverageIds.toSet() != needsById.keys || coverageIds.size != coverageIds.toSet().size) return false
        val allEvidenceIds = research.flatMap { it.evidence }.mapTo(mutableSetOf()) { it.sourceId }
        return answer.coverage.all { coverage ->
            val need = needsById.getValue(coverage.needId)
            val needEvidenceIds = researchByNeed.getValue(coverage.needId).evidence.mapTo(mutableSetOf()) { it.sourceId }
            val used = coverage.usedEvidenceSourceIds
            when {
                used.any { sourceId -> sourceId !in allEvidenceIds || sourceId !in needEvidenceIds } -> false
                coverage.status == AnswerNeedCoverageStatus.UNRESOLVED && used.isNotEmpty() -> false
                need.mode == InformationNeedMode.TOOL_REQUIRED &&
                    coverage.status == AnswerNeedCoverageStatus.ANSWERED &&
                    used.isEmpty() -> false
                need.mode == InformationNeedMode.TOOL_REQUIRED &&
                    needEvidenceIds.isEmpty() &&
                    coverage.status == AnswerNeedCoverageStatus.ANSWERED -> false
                else -> true
            }
        }
    }

    fun resultFromAnswer(
        request: ConversationAnswerTurnRequest,
        answer: ComposeConversationAnswerResult,
        needs: List<InformationNeedProposal>,
        research: List<NeedResearch>,
    ): ConversationAnswerResult {
        val outcome = classifyAnswerOutcome(answer.coverage, needs, research)
        return ConversationAnswerResult(
            assistantMessage = AssistantMessageWrite(request.assistantMessageId, answer.answer.trim()),
            outcome = outcome,
            unavailableSourceCount = research.sumOf { need ->
                need.issues.count { issue -> issue.type == ResearchIssueType.SOURCE_UNAVAILABLE }
            },
        )
    }

    private fun classifyAnswerOutcome(
        coverage: List<AnswerNeedCoveragePayload>,
        needs: List<InformationNeedProposal>,
        research: List<NeedResearch>,
    ): ConversationAnswerOutcome {
        if (coverage.any { it.status == AnswerNeedCoverageStatus.ANSWERED }) return ConversationAnswerOutcome.Answered
        val allIssues = research.flatMap { it.issues }
        if (allIssues.isNotEmpty() && allIssues.all { it.type == ResearchIssueType.MISSING_INPUT }) {
            return ConversationAnswerOutcome.Clarification
        }
        if (needs.all { it.mode == InformationNeedMode.TOOL_REQUIRED } &&
            allIssues.isNotEmpty() &&
            allIssues.all { it.type == ResearchIssueType.NO_SUITABLE_TOOL }
        ) {
            return ConversationAnswerOutcome.CapabilityUnavailable
        }
        return ConversationAnswerOutcome.ExternalUnavailable
    }
}
