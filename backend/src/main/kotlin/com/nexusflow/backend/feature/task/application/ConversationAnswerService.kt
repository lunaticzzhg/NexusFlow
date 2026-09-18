package com.nexusflow.backend.feature.task.application

import com.nexusflow.backend.core.readtool.ReadToolCall
import com.nexusflow.backend.core.readtool.ReadToolCatalog
import com.nexusflow.backend.core.readtool.ReadToolEvidence
import com.nexusflow.backend.core.readtool.ReadToolExecution
import com.nexusflow.backend.core.readtool.ReadToolExecutionContext
import com.nexusflow.backend.core.readtool.ReadToolExecutionObserver
import com.nexusflow.backend.core.readtool.ReadToolFact
import com.nexusflow.backend.core.readtool.ReadToolFactKind
import com.nexusflow.backend.core.readtool.ReadToolFactValue
import com.nexusflow.backend.core.readtool.ReadToolExecutor
import com.nexusflow.backend.core.readtool.ReadToolKey
import com.nexusflow.backend.core.readtool.ReadToolOutcome
import com.nexusflow.backend.core.readtool.ReadToolSourceAuthority
import com.nexusflow.contracts.backendai.answer.AnswerEvidenceAuthorityPayload
import com.nexusflow.contracts.backendai.answer.AnswerEvidenceFactKindPayload
import com.nexusflow.contracts.backendai.answer.AnswerEvidenceFactPayload
import com.nexusflow.contracts.backendai.answer.AnswerEvidenceFactValuePayload
import com.nexusflow.backend.feature.conversation.domain.ConversationDetail
import com.nexusflow.backend.feature.conversation.domain.ConversationMessage
import com.nexusflow.backend.feature.task.domain.AssistantMessageWrite
import com.nexusflow.backend.feature.task.domain.MessageId
import com.nexusflow.backend.feature.task.domain.MessageRole
import com.nexusflow.contracts.backendai.answer.AnswerEvidencePayload
import com.nexusflow.contracts.backendai.answer.AnswerInformationNeedPayload
import com.nexusflow.contracts.backendai.answer.AnswerNeedCoveragePayload
import com.nexusflow.contracts.backendai.answer.AnswerNeedCoverageStatus
import com.nexusflow.contracts.backendai.answer.ComposeConversationAnswerRequest
import com.nexusflow.contracts.backendai.answer.ComposeConversationAnswerResult
import com.nexusflow.contracts.backendai.answer.ConversationAnsweringCapability
import com.nexusflow.contracts.backendai.answer.ResearchIssuePayload
import com.nexusflow.contracts.backendai.answer.ResearchIssueType
import com.nexusflow.contracts.backendai.answer.StreamingConversationAnsweringCapability
import com.nexusflow.contracts.backendai.common.AiCapabilityException
import com.nexusflow.contracts.backendai.common.InvalidCapabilityResultException
import com.nexusflow.contracts.backendai.common.ModelContextBlockPayload
import com.nexusflow.contracts.backendai.common.StructuredModelRequestDiagnostics
import com.nexusflow.contracts.backendai.conversation.ConversationDecisionCapability
import com.nexusflow.contracts.backendai.conversation.ConversationDecisionRequest
import com.nexusflow.contracts.backendai.conversation.ConversationDecisionResult
import com.nexusflow.contracts.backendai.conversation.ConversationMessagePayload
import com.nexusflow.contracts.backendai.conversation.ConversationMessageRole
import com.nexusflow.contracts.backendai.conversation.InformationNeedMode
import com.nexusflow.contracts.backendai.conversation.InformationNeedProposal
import com.nexusflow.contracts.backendai.conversation.ReadOnlyToolDefinitionPayload
import com.nexusflow.observability.StructuredLogger
import com.nexusflow.observability.logFields
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant as ContractInstant
import java.time.Instant

class ConversationAnswerService(
    private val conversationDecision: ConversationDecisionCapability?,
    private val conversationAnswering: ConversationAnsweringCapability?,
    private val streamingConversationAnswering: StreamingConversationAnsweringCapability? = null,
    private val readToolCatalog: ReadToolCatalog,
    private val readToolExecutor: ReadToolExecutor,
    private val logger: StructuredLogger? = null,
) {
    suspend fun answer(request: StandaloneConversationAnswerRequest): ConversationAnswerResult =
        answer(request.toTurnRequest())

    private suspend fun answer(request: ConversationAnswerTurnRequest): ConversationAnswerResult {
        logStarted(request)
        val result = if (conversationDecision == null) {
            ConversationAnswerResult.aiUnavailable(request.assistantMessageId)
        } else {
            decideAndAnswer(request)
        }
        logFinished(request, result)
        return result
    }

    private suspend fun decideAndAnswer(request: ConversationAnswerTurnRequest): ConversationAnswerResult {
        val decision = try {
            conversationDecision!!.decide(request.toDecisionRequest())
        } catch (error: CancellationException) {
            throw error
        } catch (_: InvalidCapabilityResultException) {
            return ConversationAnswerResult.invalidAiResult(request.assistantMessageId)
        } catch (_: AiCapabilityException) {
            return ConversationAnswerResult.aiUnavailable(request.assistantMessageId)
        }

        val needs = validateInformationNeeds(decision)
            ?: return ConversationAnswerResult.invalidAiResult(request.assistantMessageId)
        val research = try {
            executeNeedResearch(request, needs)
        } catch (_: IllegalArgumentException) {
            return ConversationAnswerResult.invalid(request.assistantMessageId)
        }
        val answerRequest = request.toAnswerRequest(needs, research)
        val usesStreamingAnswerer = streamingConversationAnswering != null
        val answer = try {
            when (val streamingAnswerer = streamingConversationAnswering) {
                null -> {
                    val answerer = conversationAnswering ?: return ConversationAnswerResult.aiUnavailable(request.assistantMessageId)
                    answerer.answer(answerRequest)
                }
                else -> streamingAnswerer.answer(answerRequest, request.onAnswerDelta)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: InvalidCapabilityResultException) {
            return ConversationAnswerResult.invalidAiResult(request.assistantMessageId)
        } catch (error: AiCapabilityException) {
            if (usesStreamingAnswerer) throw error
            return ConversationAnswerResult.aiUnavailable(request.assistantMessageId)
        }
        return if (validateNeedCoverage(answer, needs, research)) {
            request.resultFromAnswer(answer, needs, research)
        } else {
            ConversationAnswerResult.invalidAiResult(request.assistantMessageId)
        }
    }

    private fun validateInformationNeeds(decision: ConversationDecisionResult): List<InformationNeedProposal>? {
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
        if (needs.any { need ->
                need.mode == InformationNeedMode.TOOL_REQUIRED &&
                    need.toolCalls.isEmpty() &&
                    need.requestedCapabilityHint == null
            }
        ) {
            return null
        }
        return needs
    }

    private suspend fun executeNeedResearch(
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
            if (callsForNeed.isEmpty() && need.requestedCapabilityHint != null) {
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

    private fun ConversationAnswerTurnRequest.toAnswerRequest(
        needs: List<InformationNeedProposal>,
        research: List<NeedResearch>,
    ): ComposeConversationAnswerRequest {
        val researchByNeed = research.associateBy { it.needId }
        val evidence = research.flatMap { it.evidence }.distinctBy { it.sourceId }
        return ComposeConversationAnswerRequest(
            aiRequestId = "answer-$aiRequestId",
            conversationId = conversationId,
            taskId = taskId,
            taskRevision = taskRevision,
            question = userMessage.content,
            recentMessages = recentMessages.map { message -> message.toPayload() },
            referenceTime = referenceTime.toContractInstant(),
            timeZoneId = timeZoneId,
            informationNeeds = needs.map { need ->
                val needResearch = researchByNeed.getValue(need.id)
                AnswerInformationNeedPayload(
                    id = need.id,
                    question = need.question,
                    mode = need.mode,
                    evidenceSourceIds = needResearch.evidence.map { it.sourceId },
                    issues = needResearch.issues,
                )
            },
            evidence = evidence.map { item ->
                AnswerEvidencePayload(
                    sourceId = item.sourceId,
                    sourceUrl = item.sourceUrl,
                    sourceKey = item.sourceKey,
                    sourceUpdatedAt = item.sourceUpdatedAt?.toContractInstant(),
                    authority = item.authority.toPayload(),
                    facts = item.facts.map { fact -> fact.toPayload() },
                )
            },
            optionalContext = optionalContext,
        )
    }

    private fun validateNeedCoverage(
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

    private fun ConversationAnswerTurnRequest.resultFromAnswer(
        answer: ComposeConversationAnswerResult,
        needs: List<InformationNeedProposal>,
        research: List<NeedResearch>,
    ): ConversationAnswerResult {
        val outcome = classifyAnswerOutcome(answer.coverage, needs, research)
        return ConversationAnswerResult(
            assistantMessage = AssistantMessageWrite(assistantMessageId, answer.answer.trim()),
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

    private fun StandaloneConversationAnswerRequest.toTurnRequest(): ConversationAnswerTurnRequest =
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
            readToolObserver = readToolObserver,
            onAnswerDelta = onAnswerDelta,
        )

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

    private fun ConversationTurnMessage.toPayload(): ConversationMessagePayload =
        ConversationMessagePayload(
            role = if (role == MessageRole.User) ConversationMessageRole.User else ConversationMessageRole.Assistant,
            content = content,
        )

    private fun ConversationMessage.toConversationTurnMessage(): ConversationTurnMessage =
        ConversationTurnMessage(id = id, role = role, content = content, createdAt = createdAt)

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

    private fun Instant.toContractInstant(): ContractInstant =
        ContractInstant.fromEpochSeconds(epochSecond, nano.toLong())
}

private fun ReadToolSourceAuthority.toPayload(): AnswerEvidenceAuthorityPayload =
    when (this) {
        ReadToolSourceAuthority.StructuredPrimary -> AnswerEvidenceAuthorityPayload.STRUCTURED_PRIMARY
        ReadToolSourceAuthority.StructuredSecondary -> AnswerEvidenceAuthorityPayload.STRUCTURED_SECONDARY
        ReadToolSourceAuthority.OfficialWeb -> AnswerEvidenceAuthorityPayload.OFFICIAL_WEB
        ReadToolSourceAuthority.GeneralWeb -> AnswerEvidenceAuthorityPayload.GENERAL_WEB
    }

private fun ReadToolFact.toPayload(): AnswerEvidenceFactPayload =
    AnswerEvidenceFactPayload(
        kind = kind.toPayload(),
        value = value.toPayload(),
    )

private fun ReadToolFactKind.toPayload(): AnswerEvidenceFactKindPayload =
    when (this) {
        ReadToolFactKind.TITLE -> AnswerEvidenceFactKindPayload.TITLE
        ReadToolFactKind.ORIGINAL_TITLE -> AnswerEvidenceFactKindPayload.ORIGINAL_TITLE
        ReadToolFactKind.SUMMARY -> AnswerEvidenceFactKindPayload.SUMMARY
        ReadToolFactKind.TYPE -> AnswerEvidenceFactKindPayload.TYPE
        ReadToolFactKind.START_TIME -> AnswerEvidenceFactKindPayload.START_TIME
        ReadToolFactKind.END_TIME -> AnswerEvidenceFactKindPayload.END_TIME
        ReadToolFactKind.DATE -> AnswerEvidenceFactKindPayload.DATE
        ReadToolFactKind.RELEASE_DATE -> AnswerEvidenceFactKindPayload.RELEASE_DATE
        ReadToolFactKind.LOCATION_NAME -> AnswerEvidenceFactKindPayload.LOCATION_NAME
        ReadToolFactKind.PRICE -> AnswerEvidenceFactKindPayload.PRICE
        ReadToolFactKind.AVAILABILITY -> AnswerEvidenceFactKindPayload.AVAILABILITY
        ReadToolFactKind.ACTIVITY_MODE -> AnswerEvidenceFactKindPayload.ACTIVITY_MODE
        ReadToolFactKind.TEMPERATURE_CELSIUS -> AnswerEvidenceFactKindPayload.TEMPERATURE_CELSIUS
        ReadToolFactKind.PRECIPITATION_PERCENT -> AnswerEvidenceFactKindPayload.PRECIPITATION_PERCENT
        ReadToolFactKind.WIND_SPEED_KPH -> AnswerEvidenceFactKindPayload.WIND_SPEED_KPH
        ReadToolFactKind.DISTANCE_METERS -> AnswerEvidenceFactKindPayload.DISTANCE_METERS
        ReadToolFactKind.DURATION_MINUTES -> AnswerEvidenceFactKindPayload.DURATION_MINUTES
        ReadToolFactKind.COMMUTE_MINUTES -> AnswerEvidenceFactKindPayload.COMMUTE_MINUTES
        ReadToolFactKind.ELEVATION_GAIN_METERS -> AnswerEvidenceFactKindPayload.ELEVATION_GAIN_METERS
        ReadToolFactKind.HOME_TEAM -> AnswerEvidenceFactKindPayload.HOME_TEAM
        ReadToolFactKind.AWAY_TEAM -> AnswerEvidenceFactKindPayload.AWAY_TEAM
        ReadToolFactKind.COMPETITION -> AnswerEvidenceFactKindPayload.COMPETITION
        ReadToolFactKind.STATUS -> AnswerEvidenceFactKindPayload.STATUS
        ReadToolFactKind.RUNTIME_MINUTES -> AnswerEvidenceFactKindPayload.RUNTIME_MINUTES
        ReadToolFactKind.GENRES -> AnswerEvidenceFactKindPayload.GENRES
        ReadToolFactKind.ARTISTS -> AnswerEvidenceFactKindPayload.ARTISTS
        ReadToolFactKind.COUNTRY_CODE -> AnswerEvidenceFactKindPayload.COUNTRY_CODE
        ReadToolFactKind.POPULARITY -> AnswerEvidenceFactKindPayload.POPULARITY
        ReadToolFactKind.LATITUDE -> AnswerEvidenceFactKindPayload.LATITUDE
        ReadToolFactKind.LONGITUDE -> AnswerEvidenceFactKindPayload.LONGITUDE
        ReadToolFactKind.PROFILE -> AnswerEvidenceFactKindPayload.PROFILE
        ReadToolFactKind.SOURCE_URL -> AnswerEvidenceFactKindPayload.SOURCE_URL
    }

private fun ReadToolFactValue.toPayload(): AnswerEvidenceFactValuePayload =
    when (this) {
        is ReadToolFactValue.Text -> AnswerEvidenceFactValuePayload.Text(value)
        is ReadToolFactValue.Integer -> AnswerEvidenceFactValuePayload.Integer(value)
        is ReadToolFactValue.Decimal -> AnswerEvidenceFactValuePayload.Decimal(value)
        is ReadToolFactValue.Timestamp -> AnswerEvidenceFactValuePayload.Timestamp(value.toAnswerContractInstant())
        is ReadToolFactValue.Money -> AnswerEvidenceFactValuePayload.Money(wholeUnits, currencyCode)
        is ReadToolFactValue.GeoPoint -> AnswerEvidenceFactValuePayload.GeoPoint(latitude, longitude)
    }

private fun Instant.toAnswerContractInstant(): ContractInstant =
    ContractInstant.fromEpochSeconds(epochSecond, nano.toLong())

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
    val readToolObserver: ReadToolExecutionObserver? = null,
    val onAnswerDelta: suspend (String) -> Unit = {},
)

private data class ConversationAnswerTurnRequest(
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
    val readToolObserver: ReadToolExecutionObserver? = null,
    val onAnswerDelta: suspend (String) -> Unit = {},
)

private data class ConversationTurnMessage(
    val id: MessageId,
    val role: MessageRole,
    val content: String,
    val createdAt: Instant,
)

private data class NeedReadToolCall(
    val needId: String,
    val call: ReadToolCall,
)

private data class NeedResearch(
    val needId: String,
    val evidence: List<ReadToolEvidence>,
    val issues: List<ResearchIssuePayload>,
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

private const val MAX_RECENT_MESSAGES = 8
private const val EXTERNAL_UNAVAILABLE_MESSAGE = "暂时无法获取这项实时资料，请稍后再试。"
private const val CAPABILITY_UNAVAILABLE_MESSAGE = "当前还没有可用的信息源来查询这项实时资料。"
private const val AI_UNAVAILABLE_MESSAGE = "暂时无法生成可靠回答，请稍后再试。"
private const val INVALID_AI_RESULT_MESSAGE = "我暂时无法生成可靠回答，请换个说法再试一次。"
private const val INVALID_TOOL_MESSAGE = "我无法安全使用这项资料来源，请换个说法再试一次。"

private fun String.toSnakeCase(): String =
    buildString(length + 4) {
        this@toSnakeCase.forEachIndexed { index, character ->
            if (character.isUpperCase() && index > 0) append('_')
            append(character.lowercaseChar())
        }
    }
