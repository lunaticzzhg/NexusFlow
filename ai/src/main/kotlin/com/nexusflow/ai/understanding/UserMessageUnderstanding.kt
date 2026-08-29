package com.nexusflow.ai.understanding

fun interface UserMessageUnderstanding {
    suspend fun understand(context: UnderstandingContext): UnderstandingOutcome
}

const val UNDERSTAND_USER_MESSAGE_PROMPT_VERSION = "understand-user-message-v1"
