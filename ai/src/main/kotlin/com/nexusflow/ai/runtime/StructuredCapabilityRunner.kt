package com.nexusflow.ai.runtime

import com.nexusflow.ai.provider.InvalidStructuredOutputException
import com.nexusflow.ai.provider.ProviderRateLimitedException
import com.nexusflow.ai.provider.ProviderRefusedException
import com.nexusflow.ai.provider.ProviderRequestException
import com.nexusflow.ai.provider.ProviderTimeoutException
import com.nexusflow.ai.provider.ProviderUnauthorizedException
import com.nexusflow.ai.provider.ProviderUnavailableException
import com.nexusflow.ai.provider.StructuredModelException
import com.nexusflow.ai.provider.StructuredModelProvider
import com.nexusflow.ai.provider.StructuredModelRequest
import com.nexusflow.ai.provider.StructuredModelResult
import com.nexusflow.contracts.backendai.common.AiCapabilityException
import com.nexusflow.contracts.backendai.common.CapabilityProviderRequestException
import com.nexusflow.contracts.backendai.common.CapabilityRateLimitedException
import com.nexusflow.contracts.backendai.common.CapabilityRefusedException
import com.nexusflow.contracts.backendai.common.CapabilityTimeoutException
import com.nexusflow.contracts.backendai.common.CapabilityUnauthorizedException
import com.nexusflow.contracts.backendai.common.CapabilityUnavailableException
import com.nexusflow.contracts.backendai.common.InvalidCapabilityResultException
import com.nexusflow.observability.StructuredLogger
import com.nexusflow.observability.logFields
import kotlinx.coroutines.CancellationException

class StructuredCapabilityRunner(
    private val provider: StructuredModelProvider,
    private val logger: StructuredLogger? = null,
) {
    suspend fun <Output> execute(
        operation: StructuredCapabilityOperation,
        request: (attempt: Int) -> StructuredModelRequest,
        decode: (StructuredModelResult) -> Output,
    ): Output {
        var attempt = 1
        while (true) {
            try {
                val result = invokeProvider(request(attempt), operation)
                return decode(result)
            } catch (error: StructuredCapabilityInvalidOutputException) {
                if (attempt == operation.maxAttempts) {
                    throw InvalidCapabilityResultException(
                        message = error.message ?: "Structured capability output is invalid",
                        cause = error,
                        failureStage = error.failureStage,
                    )
                }
                logRetry(
                    operation = operation,
                    nextAttempt = attempt + 1,
                    failureStage = error.failureStage,
                )
                attempt += 1
            }
        }
    }

    private suspend fun invokeProvider(
        request: StructuredModelRequest,
        operation: StructuredCapabilityOperation,
    ): StructuredModelResult =
        try {
            provider.generate(request)
        } catch (error: CancellationException) {
            throw error
        } catch (error: StructuredModelException) {
            throw error.toCapabilityException(operation)
        }

    private fun logRetry(
        operation: StructuredCapabilityOperation,
        nextAttempt: Int,
        failureStage: String?,
    ) {
        logger?.warn(
            component = "ai",
            event = "ai_request_retry",
            fields =
                logFields {
                    "operation" value operation.name
                    "next_attempt" value nextAttempt
                    "failure_category" value operation.invalidFailureCategory
                    failureStage?.let { stage -> "failure_stage" value stage }
                },
        )
    }

    private fun StructuredModelException.toCapabilityException(
        operation: StructuredCapabilityOperation,
    ): AiCapabilityException =
        when (this) {
            is ProviderUnauthorizedException -> CapabilityUnauthorizedException(this)
            is ProviderRequestException -> CapabilityProviderRequestException(this)
            is ProviderRateLimitedException -> CapabilityRateLimitedException(this)
            is ProviderTimeoutException -> CapabilityTimeoutException(this)
            is ProviderRefusedException -> CapabilityRefusedException()
            is ProviderUnavailableException -> CapabilityUnavailableException(this)
            is InvalidStructuredOutputException -> InvalidCapabilityResultException(
                message = message ?: operation.providerInvalidOutputFallbackMessage,
                cause = this,
                failureStage = failureStage ?: operation.providerInvalidOutputFailureStage,
            )
            else -> CapabilityUnavailableException(this)
        }
}

data class StructuredCapabilityOperation(
    val name: String,
    val invalidFailureCategory: String,
    val maxAttempts: Int = 2,
    val providerInvalidOutputFailureStage: String? = null,
    val providerInvalidOutputFallbackMessage: String = "Invalid structured output",
) {
    init {
        require(name.isNotBlank()) { "name must not be blank" }
        require(invalidFailureCategory.isNotBlank()) { "invalidFailureCategory must not be blank" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }
}

open class StructuredCapabilityInvalidOutputException(
    message: String,
    cause: Throwable? = null,
    val failureStage: String? = null,
) : RuntimeException(message, cause)
