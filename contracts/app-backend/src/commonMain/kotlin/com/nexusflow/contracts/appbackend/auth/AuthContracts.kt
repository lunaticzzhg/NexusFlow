package com.nexusflow.contracts.appbackend.auth

import kotlinx.serialization.Serializable

/** App exchanges a Google-issued ID token exactly once for a Backend-owned NexusFlow session. */
@Serializable
data class GoogleExchangeRequest(
    val idToken: String,
) {
    init {
        require(idToken.isNotBlank()) { "idToken must not be blank" }
    }
}

/** App exchanges local debug-only credentials for a Backend-owned NexusFlow session. */
@Serializable
data class DevLoginRequest(
    val email: String,
    val password: String,
) {
    init {
        require(email.isNotBlank()) { "email must not be blank" }
        require(password.isNotBlank()) { "password must not be blank" }
    }
}

/** App asks Backend to rotate a refresh token without exposing session authority to the client. */
@Serializable
data class RefreshSessionRequest(
    val refreshToken: String,
) {
    init {
        require(refreshToken.isNotBlank()) { "refreshToken must not be blank" }
    }
}

/** App asks Backend to revoke a refresh token and terminate the corresponding session. */
@Serializable
data class LogoutRequest(
    val refreshToken: String,
) {
    init {
        require(refreshToken.isNotBlank()) { "refreshToken must not be blank" }
    }
}

/** Backend returns session credentials and verified identity metadata to the App. */
@Serializable
data class AuthSessionResponse(
    val accessToken: String,
    val accessTokenExpiresInSeconds: Long,
    val refreshToken: String,
    val refreshTokenExpiresInSeconds: Long,
    val userId: String,
    val tenantId: String,
)
