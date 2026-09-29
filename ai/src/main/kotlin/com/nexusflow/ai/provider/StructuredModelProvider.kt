package com.nexusflow.ai.provider

import com.nexusflow.contracts.backendai.common.StructuredModelCapability
import com.nexusflow.contracts.backendai.common.StructuredModelRequestDiagnostics
import com.nexusflow.contracts.backendai.common.StructuredModelUsage
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

enum class StructuredModelFinishCategory {
    Complete,
    Length,
    Refusal,
    Unknown,
}

enum class StructuredModelFailureCategory {
    ProviderUnauthorized,
    ProviderRequest,
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

class ProviderRequestException(
    val httpStatusCode: Int? = null,
    val providerErrorCode: String? = null,
    cause: Throwable? = null,
) : StructuredModelException(
    StructuredModelFailureCategory.ProviderRequest,
    "Structured model provider rejected the request",
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

class InvalidStructuredOutputException(
    message: String,
    cause: Throwable? = null,
    val failureStage: String? = null,
) : StructuredModelException(
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
