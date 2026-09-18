package com.nexusflow.backend.core.external

sealed class ExternalSourceException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class ExternalSourceTimeoutException(
    provider: String,
    operation: String,
    cause: Throwable? = null,
) : ExternalSourceException("$provider $operation timed out", cause)

class ExternalSourceRateLimitedException(
    provider: String,
    operation: String,
) : ExternalSourceException("$provider $operation was rate limited")

class ExternalSourceUnauthorizedException(
    provider: String,
    operation: String,
) : ExternalSourceException("$provider $operation is unauthorized or misconfigured")

class ExternalSourceUnavailableException(
    provider: String,
    operation: String,
    cause: Throwable? = null,
) : ExternalSourceException("$provider $operation is unavailable", cause)

class ExternalSourceInvalidPayloadException(
    provider: String,
    operation: String,
    cause: Throwable? = null,
) : ExternalSourceException("$provider $operation returned an invalid payload", cause)
