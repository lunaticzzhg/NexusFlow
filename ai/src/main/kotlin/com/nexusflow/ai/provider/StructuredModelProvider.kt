package com.nexusflow.ai.provider

import kotlinx.serialization.json.JsonObject

fun interface StructuredModelProvider {
    suspend fun generate(request: StructuredModelRequest): StructuredModelResult
}

data class StructuredModelRequest(
    val systemPrompt: String,
    val userPayload: JsonObject,
    val outputSchema: StructuredOutputSchema,
    val metadata: StructuredModelRequestMetadata,
)

data class StructuredOutputSchema(
    val name: String,
    val schema: JsonObject,
    val strict: Boolean = true,
)

data class StructuredModelRequestMetadata(
    val requestId: String,
    val promptVersion: String,
    val capability: StructuredModelCapability,
    val attemptNumber: Int,
    val diagnostics: StructuredModelRequestDiagnostics = StructuredModelRequestDiagnostics(),
)

enum class StructuredModelCapability {
    UserMessageUnderstanding,
    PlanComposition,
    PlanExplanation,
}

data class StructuredModelRequestDiagnostics(
    val availableContextDefinitionCount: Int = 0,
    val selectedContextKeyCount: Int = 0,
    val resolvedContextBlockCount: Int = 0,
    val includedContextBlockCount: Int = 0,
    val omittedContextBlockCount: Int = 0,
    val optionalContextSerializedChars: Int = 0,
    val contextDefinitionsSerializedChars: Int = 0,
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

data class StructuredModelResult(
    val outputText: String,
    val metadata: StructuredModelResultMetadata,
)

data class StructuredModelResultMetadata(
    val provider: String,
    val model: String,
    val providerRequestId: String?,
    val attemptCount: Int,
    val usage: StructuredModelUsage? = null,
    val finishCategory: StructuredModelFinishCategory = StructuredModelFinishCategory.Complete,
    val requestDiagnostics: StructuredModelRequestDiagnostics = StructuredModelRequestDiagnostics(),
)

data class StructuredModelUsage(
    val inputTokens: Int?,
    val outputTokens: Int?,
    val totalTokens: Int?,
)

enum class StructuredModelFinishCategory {
    Complete,
    Length,
    Refusal,
    Unknown,
}

enum class StructuredModelFailureCategory {
    ProviderUnauthorized,
    ProviderRateLimited,
    ProviderUnavailable,
    ProviderTimeout,
    ProviderRefused,
    InvalidStructuredOutput,
    InvalidPlanProposal,
    ExplanationInvalid,
}

sealed class StructuredModelException(
    val category: StructuredModelFailureCategory,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

class ProviderUnauthorizedException(cause: Throwable? = null) : StructuredModelException(
    StructuredModelFailureCategory.ProviderUnauthorized,
    "Structured model provider rejected credentials",
    cause,
)

class ProviderRateLimitedException(cause: Throwable? = null) : StructuredModelException(
    StructuredModelFailureCategory.ProviderRateLimited,
    "Structured model provider rate limited the request",
    cause,
)

class ProviderUnavailableException(cause: Throwable? = null) : StructuredModelException(
    StructuredModelFailureCategory.ProviderUnavailable,
    "Structured model provider is unavailable",
    cause,
)

class ProviderTimeoutException(cause: Throwable? = null) : StructuredModelException(
    StructuredModelFailureCategory.ProviderTimeout,
    "Structured model provider timed out",
    cause,
)

class ProviderRefusedException : StructuredModelException(
    StructuredModelFailureCategory.ProviderRefused,
    "Structured model provider refused the request",
)

class InvalidStructuredOutputException(message: String, cause: Throwable? = null) : StructuredModelException(
    StructuredModelFailureCategory.InvalidStructuredOutput,
    message,
    cause,
)

class InvalidPlanProposalException(message: String, cause: Throwable? = null) : StructuredModelException(
    StructuredModelFailureCategory.InvalidPlanProposal,
    message,
    cause,
)

class ExplanationInvalidException(message: String, cause: Throwable? = null) : StructuredModelException(
    StructuredModelFailureCategory.ExplanationInvalid,
    message,
    cause,
)
