package com.nexusflow.contracts.api

import kotlinx.serialization.Serializable

/** The path prefix used by every externally consumable HTTP endpoint. */
object ApiVersion {
    const val V1 = "v1"
}

/** Standard, versioned error body returned by all API services. */
@Serializable
data class ApiErrorResponse(
    val code: ApiErrorCode,
    val message: String,
    val traceId: String,
    val details: List<ApiFieldViolation> = emptyList(),
) {
    init {
        require(message.isNotBlank()) { "message must not be blank" }
        require(traceId.isNotBlank()) { "traceId must not be blank" }
    }
}

@Serializable
data class ApiFieldViolation(
    val field: String,
    val reason: String,
)

@Serializable
enum class ApiErrorCode {
    VALIDATION_FAILED,
    UNAUTHENTICATED,
    FORBIDDEN,
    NOT_FOUND,
    CONFLICT,
    IDEMPOTENCY_CONFLICT,
    RATE_LIMITED,
    DEPENDENCY_UNAVAILABLE,
    INTERNAL_ERROR,
}

/** Google-issued ID tokens are exchanged exactly once for a NexusFlow session. */
@Serializable
data class GoogleExchangeRequest(
    val idToken: String,
) {
    init {
        require(idToken.isNotBlank()) { "idToken must not be blank" }
    }
}

@Serializable
data class RefreshSessionRequest(
    val refreshToken: String,
) {
    init {
        require(refreshToken.isNotBlank()) { "refreshToken must not be blank" }
    }
}

@Serializable
data class LogoutRequest(
    val refreshToken: String,
) {
    init {
        require(refreshToken.isNotBlank()) { "refreshToken must not be blank" }
    }
}

@Serializable
data class AuthSessionResponse(
    val accessToken: String,
    val accessTokenExpiresInSeconds: Long,
    val refreshToken: String,
    val refreshTokenExpiresInSeconds: Long,
    val userId: String,
    val tenantId: String,
)
