package com.nexusflow.ai.context

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ModelContextBlockPayload(
    @SerialName("key")
    val key: String,
    @SerialName("trust")
    val trust: ModelContextTrustPayload,
    @SerialName("content")
    val content: JsonObject,
)

@Serializable
enum class ModelContextTrustPayload {
    UserProfile,
    TaskDerived,
    BackendAuthoritative,
    BackendDerived,
    ExternalFiltered,
}

@Serializable
data class SelectableContextDefinitionPayload(
    @SerialName("key")
    val key: String,
    @SerialName("description")
    val description: String,
    @SerialName("selectionHint")
    val selectionHint: String,
)
