package com.nexusflow.backend.feature.conversation.application

import com.nexusflow.backend.core.identity.ActorContext
import com.nexusflow.backend.feature.conversation.domain.ConversationRepository
import com.nexusflow.backend.feature.responserun.domain.ClaimedResponseRun
import com.nexusflow.backend.feature.responserun.domain.ResponseRunStage
import com.nexusflow.backend.feature.task.application.TaskDependencyUnavailableException
import com.nexusflow.backend.feature.conversation.domain.MessageRole
import com.nexusflow.backend.feature.task.domain.TaskRepository

internal class ConversationTurnContextLoader(
    private val conversationRepository: ConversationRepository,
    private val taskRepository: TaskRepository,
    private val timeZoneId: String,
) {
    suspend fun load(claim: ClaimedResponseRun): ConversationTurnContext {
        val detail = conversationRepository.findConversationDetailForResponseRun(claim.run.id)
            ?: throw TaskDependencyUnavailableException("Conversation snapshot is unavailable")
        val userMessage = detail.messages.singleOrNull {
            it.id == claim.run.userMessageId && it.role == MessageRole.User
        } ?: throw TaskDependencyUnavailableException("Conversation user message is unavailable")
        val owner = detail.conversation.owner
        val actor = ActorContext(
            tenantId = owner.tenantId.value.toString(),
            userId = owner.userId.value.toString(),
            scopes = setOf(READ_SCOPE, WRITE_SCOPE),
        )
        val currentTask = if (claim.run.stage == ResponseRunStage.Planning) {
            null
        } else {
            taskRepository.findCurrentTaskForConversation(owner, detail.conversation.id)
        }
        val planningTask = if (claim.run.stage == ResponseRunStage.Planning) {
            val taskId = claim.run.expectedTaskId
                ?: throw TaskDependencyUnavailableException("Planning run is missing expected task id")
            taskRepository.findTaskDetail(owner, taskId)
                ?: throw TaskDependencyUnavailableException("Planning task snapshot is unavailable")
        } else {
            null
        }
        return ConversationTurnContext(
            claim = claim,
            detail = detail,
            userMessage = userMessage,
            actor = actor,
            currentTask = currentTask,
            planningTask = planningTask,
            planningTaskSuperseded = planningTask?.task?.revision != claim.run.expectedTaskRevision,
            timeZoneId = timeZoneId,
        )
    }
}

private const val READ_SCOPE = "orbit.tasks.read"
private const val WRITE_SCOPE = "orbit.tasks.write"
