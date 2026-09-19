package com.nexusflow.backend.feature.conversation.domain

import com.nexusflow.backend.feature.responserun.domain.ConsumeResponseRunResult
import com.nexusflow.backend.feature.responserun.domain.ResponseRunResult
import com.nexusflow.backend.feature.responserun.domain.ResponseRunResultPayload
import java.time.Instant

interface ConversationAnswerResultCommitter {
    suspend fun consumeConversationAnswerResult(command: ConsumeConversationAnswerResultCommand): ConsumeResponseRunResult
}

data class ConsumeConversationAnswerResultCommand(
    val result: ResponseRunResult,
    val payload: ResponseRunResultPayload.ConversationAnswer,
    val now: Instant,
)
