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

sealed interface AuthFailure {
    data object Unauthenticated : AuthFailure

    data object InvalidCredential : AuthFailure

    data object Unavailable : AuthFailure
}

interface AuthRepository {
    suspend fun exchangeGoogleIdToken(idToken: String): Result<AuthSession>

    suspend fun refresh(refreshToken: String): Result<AuthSession>

    suspend fun logout(refreshToken: String): Result<Unit>
}
