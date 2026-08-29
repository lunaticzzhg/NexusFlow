package com.nexusflow.app.feature.auth.domain

data class AppContextSnapshot(
    val userId: String,
    val tenantId: String,
) {
    val contextId: String = "$userId:$tenantId"
}

data class AuthSession(
    val accessToken: String,
    val accessTokenExpiresAtMillis: Long,
    val refreshToken: String,
    val refreshTokenExpiresAtMillis: Long,
    val context: AppContextSnapshot,
)

interface AuthRepository {
    suspend fun exchangeGoogleIdToken(idToken: String): Result<AuthSession>

    suspend fun devLogin(
        email: String,
        password: String,
    ): Result<AuthSession>

    suspend fun refresh(refreshToken: String): Result<AuthSession>

    suspend fun logout(refreshToken: String): Result<Unit>
}
