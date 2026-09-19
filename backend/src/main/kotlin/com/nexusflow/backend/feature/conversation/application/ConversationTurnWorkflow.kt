package com.nexusflow.backend.feature.conversation.application

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
import java.time.Clock
import java.util.UUID

internal class ConversationTurnWorkflow(
    private val contextLoader: ConversationTurnContextLoader,
    private val understandingStep: ConversationUnderstandingStep,
    private val payloadMapper: ConversationTurnPayloadMapper,
    private val conversationAnswerService: ConversationAnswerService?,
    private val planningService: PlanningService?,
    private val realtimeHub: ResponseRunRealtimeHub?,
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

    private fun ClaimedResponseRun.readToolObserver(): ResponseRunReadToolActivityObserver? =
        realtimeHub?.let { hub -> ResponseRunReadToolActivityObserver(hub, run) }
}
