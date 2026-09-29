package com.nexusflow.backend.feature.conversation.application

import com.nexusflow.backend.core.observability.OperationLogContext
import com.nexusflow.backend.core.observability.addOperationFields
import com.nexusflow.backend.feature.conversation.application.answer.ConversationAnswerResult
import com.nexusflow.backend.feature.conversation.application.answer.ConversationAnswerService
import com.nexusflow.backend.feature.conversation.application.answer.StandaloneConversationAnswerRequest
import com.nexusflow.backend.feature.responserun.domain.ClaimedResponseRun
import com.nexusflow.backend.feature.responserun.domain.ResponseRunResultPayload
import com.nexusflow.backend.feature.responserun.domain.ResponseRunStage
import com.nexusflow.backend.feature.task.application.PlanningService
import com.nexusflow.backend.feature.task.application.PlanningTrigger
import com.nexusflow.backend.feature.task.application.TaskDependencyUnavailableException
import com.nexusflow.backend.feature.conversation.domain.MessageId
import com.nexusflow.backend.feature.conversation.domain.MessageRole
import com.nexusflow.contracts.backendai.conversation.ConversationMessagePayload
import com.nexusflow.contracts.backendai.conversation.ConversationMessageRole
import com.nexusflow.contracts.backendai.conversation.ConversationTurnCapability
import com.nexusflow.contracts.backendai.conversation.ConversationTurnRequest
import com.nexusflow.contracts.backendai.conversation.ConversationTurnResult
import com.nexusflow.contracts.backendai.common.InvalidCapabilityResultException
import com.nexusflow.contracts.backendai.understanding.TurnIntent
import com.nexusflow.contracts.backendai.understanding.UnderstandingMetadata
import com.nexusflow.contracts.backendai.understanding.UnderstandMessageResult as AiUnderstandMessageResult
import com.nexusflow.observability.StructuredLogger
import com.nexusflow.observability.logFields
import java.time.Clock
import java.util.UUID
import kotlinx.datetime.Instant as ContractInstant

internal class ConversationTurnWorkflow(
    private val contextLoader: ConversationTurnContextLoader,
    private val payloadMapper: ConversationTurnPayloadMapper,
    private val conversationTurn: ConversationTurnCapability?,
    private val conversationAnswerService: ConversationAnswerService?,
    private val planningService: PlanningService?,
    private val realtimeHub: ResponseRunRealtimeHub?,
    private val logger: StructuredLogger?,
    private val clock: Clock,
    private val uuidFactory: () -> UUID,
) {
    suspend fun execute(claim: ClaimedResponseRun): ResponseRunResultPayload {
        val context = contextLoader.load(claim)
        if (context.claim.run.stage == ResponseRunStage.Planning) {
            return processPlanningRun(context)
        }
        return executeFastTurn(context)
    }

    private suspend fun executeFastTurn(context: ConversationTurnContext): ResponseRunResultPayload {
        val capability = conversationTurn
            ?: return payloadMapper.conversationAnswerPayload(
                result = ConversationAnswerResult.aiUnavailable(MessageId(uuidFactory())),
                detail = context.detail,
                userMessage = context.userMessage,
            )
        logTurnStep(context, branch = null, step = "turn_started", outcome = "started")
        var streamingStarted = false
        var firstDeltaLogged = false
        val result = try {
            capability.execute(
                request = context.toTurnRequest(conversationAnswerService),
                onAnswerDelta = { delta ->
                    realtimeHub?.let { hub ->
                        if (!streamingStarted) {
                            hub.streamingStarted(context.claim.run)
                            streamingStarted = true
                        }
                        if (!firstDeltaLogged) {
                            logTurnStep(context, branch = CONVERSATION_BRANCH_DIRECT_ANSWER, step = "first_answer_delta", outcome = "streaming")
                            firstDeltaLogged = true
                        }
                        hub.delta(context.claim.run, delta)
                    }
                },
            )
        } catch (error: InvalidCapabilityResultException) {
            logger?.warn(
                component = "conversation_turn",
                event = "conversation_turn_recovered",
                fields = logFields {
                    "response_run_id" value context.claim.run.id.value.toString()
                    "ai_request_id" value context.userMessage.aiRequestId
                    "attempt" value context.claim.run.attempt
                    "failure_category" value "invalid_turn_candidate"
                    "failure_stage" value error.failureStage
                    "recovery_action" value "safe_reply"
                    "recovery_outcome" value "committable_fallback"
                },
            )
            return payloadMapper.conversationAnswerPayload(
                result = ConversationAnswerResult.invalidAiResult(MessageId(uuidFactory())),
                detail = context.detail,
                userMessage = context.userMessage,
            )
        }
        return when (result) {
            is ConversationTurnResult.Answer -> {
                logTurnStep(context, branch = CONVERSATION_BRANCH_DIRECT_ANSWER, step = "turn_answer", outcome = "answer")
                payloadMapper.conversationAnswerPayload(
                    result = ConversationAnswerResult.assistant(MessageId(uuidFactory()), result.answer),
                    detail = context.detail,
                    userMessage = context.userMessage,
                )
            }
            is ConversationTurnResult.Research -> {
                logTurnStep(context, branch = CONVERSATION_BRANCH_RESEARCH, step = "turn_research", outcome = "research", informationNeedCount = result.informationNeeds.size)
                val answerService = conversationAnswerService
                    ?: return payloadMapper.conversationAnswerPayload(
                        result = ConversationAnswerResult.aiUnavailable(MessageId(uuidFactory())),
                        detail = context.detail,
                        userMessage = context.userMessage,
                    )
                payloadMapper.conversationAnswerPayload(
                    result = answerService.answerKnownNeeds(
                        request = context.toStreamingStandaloneAnswerRequest(MessageId(uuidFactory())),
                        needs = result.informationNeeds,
                    ),
                    detail = context.detail,
                    userMessage = context.userMessage,
                )
            }
            is ConversationTurnResult.Planning -> {
                logTurnStep(context, branch = CONVERSATION_BRANCH_PLANNING, step = "turn_planning", outcome = "planning", constraintDeltaCount = result.constraintDeltas.size)
                payloadMapper.planningUnderstandingPayload(
                    detail = context.detail,
                    userMessage = context.userMessage,
                    currentTask = context.currentTask,
                    understanding = result.toUnderstandingResult(),
                    timeZoneId = context.timeZoneId,
                )
            }
        }
    }


    private fun logTurnStep(
        context: ConversationTurnContext,
        branch: String?,
        step: String,
        outcome: String,
        informationNeedCount: Int? = null,
        constraintDeltaCount: Int? = null,
    ) {
        logger?.info(
            component = "conversation_turn",
            event = "conversation_turn_step",
            fields = logFields {
                addOperationFields(context.operationLogContext(branch = branch), step = step, outcome = outcome)
                "response_run_id" value context.claim.run.id.value.toString()
                "conversation_id" value context.detail.conversation.id.value.toString()
                "user_message_id" value context.userMessage.id.value.toString()
                "ai_request_id" value context.userMessage.aiRequestId
                "task_id" value context.currentTask?.task?.id?.value?.toString()
                "task_revision" value context.currentTask?.task?.revision
                "attempt" value context.claim.run.attempt
                "information_need_count" value informationNeedCount
                "constraint_delta_count" value constraintDeltaCount
            },
        )
    }

    private suspend fun processPlanningRun(context: ConversationTurnContext): ResponseRunResultPayload.PlanningResult {
        val taskDetail = context.planningTask
            ?: throw TaskDependencyUnavailableException("Planning task snapshot is unavailable")
        if (context.planningTaskSuperseded) {
            logPlanningFinished(context, taskDetail, outcome = "superseded")
            return payloadMapper.planningResultPayload(
                detail = context.detail,
                userMessage = context.userMessage,
                taskDetail = taskDetail,
                opportunities = emptyList(),
                plans = emptyList(),
                outcome = "Superseded",
            )
        }
        val planning = planningService
            ?: throw TaskDependencyUnavailableException("Planning response run processing is not available")
        val computation = planning.computeIfReady(
            actor = context.actor,
            detail = taskDetail,
            trigger = PlanningTrigger.PlanningInputChanged,
            readToolObserver = context.claim.readToolObserver(),
            operationLogContext = context.operationLogContext(branch = CONVERSATION_BRANCH_PLANNING),
        )
        return payloadMapper.planningResultPayload(
            detail = context.detail,
            userMessage = context.userMessage,
            taskDetail = taskDetail,
            opportunities = computation.opportunities,
            plans = computation.plans,
            outcome = computation.outcome.name,
        )
    }

    private fun ConversationTurnContext.toStandaloneAnswerRequest(assistantMessageId: MessageId): StandaloneConversationAnswerRequest =
        StandaloneConversationAnswerRequest(
            detail = detail,
            userMessage = userMessage,
            aiRequestId = userMessage.aiRequestId ?: "",
            timeZoneId = timeZoneId,
            referenceTime = referenceTime,
            assistantMessageId = assistantMessageId,
            actorTenantId = actor.tenantId,
            actorUserId = actor.userId,
            taskId = currentTask?.task?.id?.value?.toString(),
            taskRevision = currentTask?.task?.revision,
            operationLogContext = operationLogContext(branch = CONVERSATION_BRANCH_CHAT_ANSWER),
            readToolObserver = claim.readToolObserver(),
        )

    private fun ConversationTurnContext.toStreamingStandaloneAnswerRequest(assistantMessageId: MessageId): StandaloneConversationAnswerRequest {
        var streamingStarted = false
        return toStandaloneAnswerRequest(assistantMessageId).copy(
            onAnswerDelta = { delta ->
                realtimeHub?.let { hub ->
                    if (!streamingStarted) {
                        hub.streamingStarted(claim.run)
                        streamingStarted = true
                    }
                    hub.delta(claim.run, delta)
                }
            },
        )
    }

    private fun ConversationTurnContext.toTurnRequest(answerService: ConversationAnswerService?): ConversationTurnRequest =
        ConversationTurnRequest(
            aiRequestId = userMessage.aiRequestId ?: "",
            conversationId = detail.conversation.id.value.toString(),
            taskId = currentTask?.task?.id?.value?.toString(),
            taskRevision = currentTask?.task?.revision,
            currentMessage = userMessage.content,
            recentMessages = detail.messages
                .filter { message -> message.id != userMessage.id && message.turnIndex < userMessage.turnIndex }
                .sortedWith(compareBy({ it.turnIndex }, { it.role.historyOrder() }, { it.createdAt }))
                .takeLast(8)
                .map { message ->
                    ConversationMessagePayload(
                        role = if (message.role == MessageRole.User) {
                            ConversationMessageRole.User
                        } else {
                            ConversationMessageRole.Assistant
                        },
                        content = message.content,
                    )
                },
            referenceTime = referenceTime.toContractInstant(),
            timeZoneId = timeZoneId,
            availableReadTools = answerService?.availableReadTools().orEmpty(),
            maxReadToolCalls = answerService?.maxReadToolCalls() ?: 0,
            activePlanning = currentTask?.let { payloadMapper.activePlanningPayload(it) },
        )

    private fun ConversationTurnResult.Planning.toUnderstandingResult(): AiUnderstandMessageResult =
        AiUnderstandMessageResult(
            turnIntent = TurnIntent.Planning,
            planningGoalPatch = planningGoalPatch,
            constraintDeltas = constraintDeltas,
            clarification = clarification,
            contextSelection = contextSelection,
            metadata = UnderstandingMetadata(
                provider = metadata.provider.orEmpty(),
                model = metadata.model.orEmpty(),
                promptVersion = metadata.promptVersion.orEmpty(),
                providerRequestId = metadata.providerRequestId,
                attemptCount = metadata.attemptCount ?: 1,
                usage = metadata.usage,
                diagnostics = metadata.diagnostics,
            ),
        )

    private fun logPlanningFinished(
        context: ConversationTurnContext,
        taskDetail: com.nexusflow.backend.feature.task.domain.TaskDetail,
        outcome: String,
    ) {
        logger?.info(
            component = "planning",
            event = "planning_finished",
            fields = logFields {
                addOperationFields(
                    context.operationLogContext(branch = CONVERSATION_BRANCH_PLANNING),
                    step = "planning_finished",
                    outcome = outcome,
                )
                "response_run_id" value context.claim.run.id.value.toString()
                "conversation_id" value context.detail.conversation.id.value.toString()
                "user_message_id" value context.userMessage.id.value.toString()
                "ai_request_id" value context.userMessage.aiRequestId
                "task_id" value taskDetail.task.id.value.toString()
                "task_revision" value taskDetail.task.revision
                "planning_decision" value "superseded"
            },
        )
    }

    private fun ClaimedResponseRun.readToolObserver(): ResponseRunReadToolActivityObserver? =
        realtimeHub?.let { hub -> ResponseRunReadToolActivityObserver(hub, run) }
}

internal const val CONVERSATION_BRANCH_DIRECT_ANSWER = "direct_answer"
internal const val CONVERSATION_BRANCH_RESEARCH = "research"

private fun java.time.Instant.toContractInstant(): ContractInstant =
    ContractInstant.fromEpochSeconds(epochSecond, nano.toLong())

internal fun ConversationTurnContext.operationLogContext(branch: String? = null): OperationLogContext =
    OperationLogContext(
        operationType = CONVERSATION_TURN_OPERATION_TYPE,
        operationId = claim.run.id.value.toString(),
        branch = branch,
        stage = claim.run.stage.name.toSnakeCase(),
    )

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

private fun MessageRole.historyOrder(): Int =
    when (this) {
        MessageRole.User -> 0
        MessageRole.Assistant -> 1
    }
