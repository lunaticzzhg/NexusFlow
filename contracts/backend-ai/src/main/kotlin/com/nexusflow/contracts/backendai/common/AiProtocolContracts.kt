package com.nexusflow.contracts.backendai.common

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Backend-approved model capability names shared by context assembly and the AI layer. */
@Serializable
enum class StructuredModelCapability {
    @SerialName("user_message_understanding")
    UnderstandMessage,

    @SerialName("plan_composition")
    CreatePlans,

    @SerialName("plan_explanation")
    ExplainPlans,
}

/**
 * Backend sends one bounded, already-filtered optional context block to an AI capability.
 *
 * Raw external payloads, provider DTOs, prompts, and source-private provenance do not belong in this contract.
 */
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
    @SerialName("user_profile")
    UserProfile,

    @SerialName("task_derived")
    TaskDerived,

    @SerialName("backend_authoritative")
    BackendAuthoritative,

    @SerialName("backend_derived")
    BackendDerived,

    @SerialName("external_filtered")
    ExternalFiltered,
}

/** Backend offers a bounded context definition key that the AI may propose selecting for future task turns. */
@Serializable
data class SelectableContextDefinitionPayload(
    @SerialName("key")
    val key: String,
    @SerialName("description")
    val description: String,
    @SerialName("selectionHint")
    val selectionHint: String,
)

/** Sanitized diagnostics shared across Backend and AI for audit/debug without prompt or raw provider data. */
@Serializable
data class StructuredModelRequestDiagnostics(
    @SerialName("availableContextDefinitionCount")
    val availableContextDefinitionCount: Int = 0,
    @SerialName("selectedContextKeyCount")
    val selectedContextKeyCount: Int = 0,
    @SerialName("resolvedContextBlockCount")
    val resolvedContextBlockCount: Int = 0,
    @SerialName("includedContextBlockCount")
    val includedContextBlockCount: Int = 0,
    @SerialName("omittedContextBlockCount")
    val omittedContextBlockCount: Int = 0,
    @SerialName("optionalContextSerializedChars")
    val optionalContextSerializedChars: Int = 0,
    @SerialName("contextDefinitionsSerializedChars")
    val contextDefinitionsSerializedChars: Int = 0,
    @SerialName("fullUserPayloadSerializedChars")
    val fullUserPayloadSerializedChars: Int = 0,
) {
    init {
        require(availableContextDefinitionCount >= 0) { "availableContextDefinitionCount must be non-negative" }
        require(selectedContextKeyCount >= 0) { "selectedContextKeyCount must be non-negative" }
        require(resolvedContextBlockCount >= 0) { "resolvedContextBlockCount must be non-negative" }
        require(includedContextBlockCount >= 0) { "includedContextBlockCount must be non-negative" }
        require(omittedContextBlockCount >= 0) { "omittedContextBlockCount must be non-negative" }
        require(optionalContextSerializedChars >= 0) { "optionalContextSerializedChars must be non-negative" }
        require(contextDefinitionsSerializedChars >= 0) { "contextDefinitionsSerializedChars must be non-negative" }
        require(fullUserPayloadSerializedChars >= 0) { "fullUserPayloadSerializedChars must be non-negative" }
    }
}

/** Sanitized provider-reported usage exposed to Backend audit without raw provider payloads. */
@Serializable
data class StructuredModelUsage(
    @SerialName("inputTokens")
    val inputTokens: Int?,
    @SerialName("outputTokens")
    val outputTokens: Int?,
    @SerialName("totalTokens")
    val totalTokens: Int?,
)

sealed class AiCapabilityException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class CapabilityUnavailableException(cause: Throwable? = null) :
    AiCapabilityException("AI capability is unavailable", cause)

class CapabilityUnauthorizedException(cause: Throwable? = null) :
    AiCapabilityException("AI capability rejected credentials", cause)

class CapabilityRateLimitedException(cause: Throwable? = null) :
    AiCapabilityException("AI capability rate limited the request", cause)

class CapabilityTimeoutException(cause: Throwable? = null) :
    AiCapabilityException("AI capability timed out", cause)

class CapabilityRefusedException :
    AiCapabilityException("AI capability refused the request")

class InvalidCapabilityResultException(
    message: String,
    cause: Throwable? = null,
    val failureStage: String? = null,
) : AiCapabilityException(message, cause)
