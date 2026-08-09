package com.nexusflow.app.feature.auth.data

import com.nexusflow.app.core.time.AppClock
import com.nexusflow.app.feature.auth.domain.AppContextSnapshot
import com.nexusflow.app.feature.auth.domain.AuthRepository
import com.nexusflow.app.feature.auth.domain.AuthSession
import com.nexusflow.contracts.api.AuthSessionResponse

internal class DefaultAuthRepository(
    private val authRemoteDataSource: AuthRemoteDataSource,
    private val clock: AppClock,
) : AuthRepository {
    override suspend fun exchangeGoogleIdToken(idToken: String): Result<AuthSession> =
        authRemoteDataSource.exchangeGoogleIdToken(idToken).map { it.toDomain(clock) }

    override suspend fun refresh(refreshToken: String): Result<AuthSession> =
        authRemoteDataSource.refresh(refreshToken).map { it.toDomain(clock) }

    override suspend fun logout(refreshToken: String): Result<Unit> = authRemoteDataSource.logout(refreshToken)
}

private fun AuthSessionResponse.toDomain(clock: AppClock): AuthSession {
    val now = clock.currentTimeMillis()
    return AuthSession(
        accessToken = accessToken,
        accessTokenExpiresAtMillis = now + accessTokenExpiresInSeconds * 1_000,
        refreshToken = refreshToken,
        refreshTokenExpiresAtMillis = now + refreshTokenExpiresInSeconds * 1_000,
        context = AppContextSnapshot(userId, tenantId),
    )
}
