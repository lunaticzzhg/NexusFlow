package com.nexusflow.backend.feature.conversation.domain

import java.util.UUID

@JvmInline
value class MessageId(val value: UUID)

enum class MessageRole {
    User,
    Assistant,
}
