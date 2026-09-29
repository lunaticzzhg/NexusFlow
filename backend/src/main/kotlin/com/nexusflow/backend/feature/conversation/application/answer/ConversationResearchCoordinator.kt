package com.nexusflow.backend.feature.conversation.application.answer

import com.nexusflow.backend.feature.research.application.ReadToolCall
import com.nexusflow.backend.feature.research.application.ReadToolEvidence
import com.nexusflow.backend.feature.research.application.ReadToolExecution
import com.nexusflow.backend.feature.research.application.ReadToolExecutionContext
import com.nexusflow.backend.feature.research.application.ReadToolExecutor
import com.nexusflow.backend.feature.research.application.ReadToolKey
import com.nexusflow.backend.feature.research.application.ReadToolOutcome
import com.nexusflow.contracts.backendai.answer.ResearchIssuePayload
import com.nexusflow.contracts.backendai.answer.ResearchIssueType
import com.nexusflow.contracts.backendai.conversation.InformationNeedProposal
import com.nexusflow.contracts.backendai.conversation.InformationNeedMode

internal class ConversationResearchCoordinator(
    private val readToolExecutor: ReadToolExecutor,
) {
    suspend fun execute(
        request: ConversationAnswerTurnRequest,
        needs: List<InformationNeedProposal>,
    ): List<NeedResearch> {
        val needCalls = needs.flatMap { need ->
            need.toolCalls.map { proposal ->
                NeedReadToolCall(
                    needId = need.id,
                    call = ReadToolCall(ReadToolKey(proposal.toolKey), proposal.arguments),
                )
            }
        }
        val uniqueCalls = needCalls.map { it.call }.distinct()
        val executions = if (uniqueCalls.isEmpty()) {
            emptyList()
        } else {
            readToolExecutor.execute(
                calls = uniqueCalls,
                context = ReadToolExecutionContext(
                    referenceTime = request.referenceTime,
                    timeZoneId = request.timeZoneId,
                    responseRunId = request.operationLogContext?.operationId,
                    actorTenantId = request.actorTenantId,
                    actorUserId = request.actorUserId,
                    conversationId = request.conversationId,
                    taskId = request.taskId,
                ),
                observer = request.readToolObserver,
            )
        }
        val executionsByCall = executions.associateBy(ReadToolExecution::call)
        return needs.map { need ->
            val callsForNeed = needCalls.filter { it.needId == need.id }
            val evidence = mutableListOf<ReadToolEvidence>()
            val issues = mutableListOf<ResearchIssuePayload>()
            if (callsForNeed.isEmpty() && need.mode == InformationNeedMode.TOOL_REQUIRED) {
                issues += ResearchIssuePayload(ResearchIssueType.NO_SUITABLE_TOOL)
            }
            callsForNeed.forEach { needCall ->
                val execution = executionsByCall.getValue(needCall.call)
                when (val outcome = execution.outcome) {
                    is ReadToolOutcome.Success -> evidence += outcome.payload.evidence
                    ReadToolOutcome.Empty -> issues += ResearchIssuePayload(ResearchIssueType.EMPTY_RESULT)
                    is ReadToolOutcome.MissingInput -> issues += ResearchIssuePayload(
                        type = ResearchIssueType.MISSING_INPUT,
                        missingInputs = outcome.fields.sorted(),
                    )
                    is ReadToolOutcome.InvalidArguments -> issues += ResearchIssuePayload(ResearchIssueType.INVALID_ARGUMENTS)
                    is ReadToolOutcome.Unavailable -> issues += ResearchIssuePayload(ResearchIssueType.SOURCE_UNAVAILABLE)
                }
            }
            NeedResearch(
                needId = need.id,
                evidence = evidence.distinctBy { it.sourceId },
                issues = issues,
            )
        }
    }
}

private data class NeedReadToolCall(
    val needId: String,
    val call: ReadToolCall,
)

internal data class NeedResearch(
    val needId: String,
    val evidence: List<ReadToolEvidence>,
    val issues: List<ResearchIssuePayload>,
)
