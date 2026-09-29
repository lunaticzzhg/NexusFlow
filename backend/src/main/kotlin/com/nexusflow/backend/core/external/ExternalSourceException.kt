package com.nexusflow.backend.core.external

sealed class ExternalSourceException(
    message: String,
    cause: Throwable? = null,
    val provider: String,
    val operation: String,
    val failureCategory: String,
    val httpStatus: Int? = null,
    val contentType: String? = null,
) : RuntimeException(message, cause)

class ExternalSourceTimeoutException(
    provider: String,
    operation: String,
    cause: Throwable? = null,
) : ExternalSourceException("$provider $operation timed out", cause, provider, operation, "timeout")

class ExternalSourceRateLimitedException(
    provider: String,
    operation: String,
    contentType: String? = null,
) : ExternalSourceException("$provider $operation was rate limited", provider = provider, operation = operation, failureCategory = "remote_rate_limited", httpStatus = 429, contentType = contentType)

class ExternalSourcePolicyRateLimitedException(provider: String, operation: String) :
    ExternalSourceException("$provider $operation was limited by local policy", provider = provider, operation = operation, failureCategory = "local_policy_rate_limited")

class ExternalSourceDisabledException(provider: String, operation: String) :
    ExternalSourceException("$provider $operation is disabled because EXTERNAL_SOURCE_USER_AGENT needs a contact", provider = provider, operation = operation, failureCategory = "disabled_invalid_user_agent")

class ExternalSourceUnauthorizedException(
    provider: String,
    operation: String,
    httpStatus: Int? = null,
    contentType: String? = null,
) : ExternalSourceException("$provider $operation is unauthorized or misconfigured", provider = provider, operation = operation, failureCategory = "unauthorized", httpStatus = httpStatus, contentType = contentType)

class ExternalSourceUnavailableException(
    provider: String,
    operation: String,
    cause: Throwable? = null,
    httpStatus: Int? = null,
    contentType: String? = null,
) : ExternalSourceException("$provider $operation is unavailable", cause, provider, operation, "unavailable", httpStatus, contentType)

class ExternalSourceInvalidPayloadException(
    provider: String,
    operation: String,
    cause: Throwable? = null,
    httpStatus: Int? = null,
    contentType: String? = null,
) : ExternalSourceException("$provider $operation returned an invalid payload", cause, provider, operation, "invalid_payload", httpStatus, contentType)
