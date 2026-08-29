package com.nexusflow.app.feature.auth.data

import com.nexusflow.app.core.error.AppException
import com.nexusflow.app.core.network.ApiCallExecutor
import com.nexusflow.app.core.observability.AppLogger
import com.nexusflow.app.core.observability.LogFields
import com.nexusflow.app.core.observability.LogLevel
import com.nexusflow.app.core.observability.LogTag
import com.nexusflow.app.core.time.AppClock
import com.nexusflow.app.feature.auth.domain.AuthException
import com.nexusflow.contracts.api.AuthSessionResponse
import com.nexusflow.contracts.api.DevLoginRequest
import com.nexusflow.contracts.api.GoogleExchangeRequest
import com.nexusflow.contracts.api.KResponse
import com.nexusflow.contracts.api.LogoutRequest
import com.nexusflow.contracts.api.RefreshSessionRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class DefaultAuthRepositoryTest {
    @Test
    fun `maps unauthorized and forbidden failures to unauthenticated`() =
        runBlocking {
            assertIs<AuthException.Unauthenticated>(repository(KResponse(code = 401)).exchangeGoogleIdToken("id-token").exceptionOrNull())
            assertIs<AuthException.Unauthenticated>(repository(KResponse(code = 403)).exchangeGoogleIdToken("id-token").exceptionOrNull())
            Unit
        }

    @Test
    fun `maps unprocessable entity to invalid credential`() =
        runBlocking {
            assertIs<AuthException.InvalidCredential>(repository(KResponse(code = 422)).exchangeGoogleIdToken("id-token").exceptionOrNull())
            Unit
        }

    @Test
    fun `maps dev login unauthorized to invalid credential`() =
        runBlocking {
            assertIs<AuthException.InvalidCredential>(
                repositoryWithDevLogin(KResponse(code = 401))
                    .devLogin("dev@nexusflow.local", "wrong")
                    .exceptionOrNull(),
            )
            Unit
        }

    @Test
    fun `preserves non-auth transport failures`() =
        runBlocking {
            assertIs<AppException.Unavailable>(repository(KResponse(code = 500)).exchangeGoogleIdToken("id-token").exceptionOrNull())
            Unit
        }

    @Test
    fun `preserves cancellation`() =
        runBlocking {
            assertFailsWith<CancellationException> {
                repository { throw CancellationException("cancelled") }.exchangeGoogleIdToken("id-token")
            }
            Unit
        }

    private fun repository(response: suspend () -> KResponse<AuthSessionResponse>): DefaultAuthRepository =
        DefaultAuthRepository(
            authRemoteDataSource = AuthRemoteDataSource(TestAuthApi(response), ApiCallExecutor(NoOpLogger)),
            clock = FixedClock,
        )

    private fun repository(response: KResponse<AuthSessionResponse>): DefaultAuthRepository = repository { response }

    private fun repositoryWithDevLogin(devLoginResponse: KResponse<AuthSessionResponse>): DefaultAuthRepository =
        DefaultAuthRepository(
            authRemoteDataSource =
                AuthRemoteDataSource(
                    TestAuthApi(devLoginResponse = { devLoginResponse }),
                    ApiCallExecutor(NoOpLogger),
                ),
            clock = FixedClock,
        )

    private object FixedClock : AppClock {
        override fun currentTimeMillis(): Long = 1_000
    }

    private class TestAuthApi(
        private val exchangeResponse: suspend () -> KResponse<AuthSessionResponse> = { error("Not used") },
        private val devLoginResponse: suspend () -> KResponse<AuthSessionResponse> = { error("Not used") },
    ) : AuthApi {
        override suspend fun exchangeGoogleIdToken(request: GoogleExchangeRequest): KResponse<AuthSessionResponse> = exchangeResponse()

        override suspend fun devLogin(request: DevLoginRequest): KResponse<AuthSessionResponse> = devLoginResponse()

        override suspend fun refresh(request: RefreshSessionRequest): KResponse<AuthSessionResponse> = error("Not used")

        override suspend fun logout(request: LogoutRequest): KResponse<Unit> = error("Not used")
    }

    private object NoOpLogger : AppLogger {
        override fun log(
            level: LogLevel,
            tag: LogTag,
            event: String,
            fields: LogFields,
            cause: Throwable?,
        ) = Unit
    }
}
