package com.nexusflow.backend.feature.conversation.application

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
import com.nexusflow.contracts.backendai.understanding.TurnIntent
import com.nexusflow.observability.StructuredLogger
import com.nexusflow.observability.logFields
import java.time.Clock
import java.util.UUID

internal class ConversationTurnWorkflow(
    private val contextLoader: ConversationTurnContextLoader,
    private val understandingStep: ConversationUnderstandingStep,
    private val payloadMapper: ConversationTurnPayloadMapper,
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
        val understanding = understandingStep.execute(context)
        return when (understanding?.turnIntent) {
            null,
            TurnIntent.Conversation,
            -> answerConversation(context)
            TurnIntent.Planning -> payloadMapper.planningUnderstandingPayload(
                detail = context.detail,
                userMessage = context.userMessage,
                currentTask = context.currentTask,
                understanding = understanding,
            )
        }
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

    private suspend fun answerConversation(context: ConversationTurnContext): ResponseRunResultPayload.ConversationAnswer =
        payloadMapper.conversationAnswerPayload(
            result = conversationAnswerResult(context),
            detail = context.detail,
            userMessage = context.userMessage,
        )

    private suspend fun conversationAnswerResult(context: ConversationTurnContext): ConversationAnswerResult {
        var streamingStarted = false
        return conversationAnswerService?.answer(
            StandaloneConversationAnswerRequest(
                detail = context.detail,
                userMessage = context.userMessage,
                aiRequestId = context.userMessage.aiRequestId ?: "",
                timeZoneId = context.timeZoneId,
                referenceTime = clock.instant(),
                assistantMessageId = MessageId(uuidFactory()),
                actorTenantId = context.actor.tenantId,
                actorUserId = context.actor.userId,
                taskId = context.currentTask?.task?.id?.value?.toString(),
                taskRevision = context.currentTask?.task?.revision,
                operationLogContext = context.operationLogContext(branch = CONVERSATION_BRANCH_CHAT_ANSWER),
                readToolObserver = context.claim.readToolObserver(),
                onAnswerDelta = { delta ->
                    realtimeHub?.let { hub ->
                        if (!streamingStarted) {
                            hub.streamingStarted(context.claim.run)
                            streamingStarted = true
                        }
                        hub.delta(context.claim.run, delta)
                    }
                },
            ),
        ) ?: ConversationAnswerResult.aiUnavailable(MessageId(uuidFactory()))
    }

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
