package com.nexusflow.backend.feature.conversation.application

import com.nexusflow.backend.core.identity.ActorContext
import com.nexusflow.backend.feature.conversation.domain.ConversationDetail
import com.nexusflow.backend.feature.conversation.domain.ConversationMessage
import com.nexusflow.backend.feature.responserun.domain.ClaimedResponseRun
import com.nexusflow.backend.feature.task.domain.TaskDetail

internal data class ConversationTurnContext(
    val claim: ClaimedResponseRun,
    val detail: ConversationDetail,
    val userMessage: ConversationMessage,
    val actor: ActorContext,
    val currentTask: TaskDetail?,
    val planningTask: TaskDetail?,
    val planningTaskSuperseded: Boolean,
    val timeZoneId: String,
    val referenceTime: java.time.Instant,
)
