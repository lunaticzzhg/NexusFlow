package com.nexusflow.app.feature.auth.data

import com.nexusflow.app.core.error.AppException
import com.nexusflow.app.core.time.AppClock
import com.nexusflow.app.feature.auth.domain.AppContextSnapshot
import com.nexusflow.app.feature.auth.domain.AuthException
import com.nexusflow.app.feature.auth.domain.AuthRepository
import com.nexusflow.app.feature.auth.domain.AuthSession
import com.nexusflow.contracts.api.AuthSessionResponse

internal class DefaultAuthRepository(
    private val authRemoteDataSource: AuthRemoteDataSource,
    private val clock: AppClock,
) : AuthRepository {
    override suspend fun exchangeGoogleIdToken(idToken: String): Result<AuthSession> =
        authRemoteDataSource.exchangeGoogleIdToken(idToken)
            .map { it.toDomain(clock) }
            .mapAuthFailure()

    override suspend fun devLogin(
        email: String,
        password: String,
    ): Result<AuthSession> =
        authRemoteDataSource.devLogin(email, password)
            .map { it.toDomain(clock) }
            .mapDevLoginFailure()

    override suspend fun refresh(refreshToken: String): Result<AuthSession> =
        authRemoteDataSource.refresh(refreshToken)
            .map { it.toDomain(clock) }
            .mapAuthFailure()

    override suspend fun logout(refreshToken: String): Result<Unit> = authRemoteDataSource.logout(refreshToken).mapAuthFailure()
}

private fun <T> Result<T>.mapAuthFailure(): Result<T> =
    fold(
        onSuccess = Result.Companion::success,
        onFailure = { failure ->
            Result.failure(
                when (failure) {
                    is AppException.Unauthorized,
                    is AppException.Forbidden,
                    -> AuthException.Unauthenticated
                    is AppException.Rejected ->
                        if (failure.code == HTTP_UNPROCESSABLE_ENTITY) {
                            AuthException.InvalidCredential
                        } else {
                            failure
                        }
                    else -> failure
                },
            )
        },
    )

private fun <T> Result<T>.mapDevLoginFailure(): Result<T> =
    fold(
        onSuccess = Result.Companion::success,
        onFailure = { failure ->
            Result.failure(
                when (failure) {
                    is AppException.Unauthorized,
                    is AppException.Forbidden,
                    -> AuthException.InvalidCredential
                    is AppException.Rejected ->
                        if (failure.code == HTTP_UNPROCESSABLE_ENTITY) {
                            AuthException.InvalidCredential
                        } else {
                            failure
                        }
                    else -> failure
                },
            )
        },
    )

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

private const val HTTP_UNPROCESSABLE_ENTITY = 422
