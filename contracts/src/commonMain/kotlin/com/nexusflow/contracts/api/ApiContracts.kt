package com.nexusflow.contracts.api

import kotlinx.serialization.Serializable

/** The path prefix used by every externally consumable HTTP endpoint. */
object ApiVersion {
    const val V1 = "v1"
}

/** Standard, versioned response body returned by all JSON API services. */
@Serializable
data class KResponse<T>(
    val code: Int,
    val message: String? = null,
    val data: T? = null,
)

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
