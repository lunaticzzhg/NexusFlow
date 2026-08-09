package com.nexusflow.app.feature.auth.data

import com.nexusflow.app.core.network.ApiCallExecutor
import com.nexusflow.contracts.api.AuthSessionResponse
import com.nexusflow.contracts.api.GoogleExchangeRequest
import com.nexusflow.contracts.api.KResponse
import com.nexusflow.contracts.api.LogoutRequest
import com.nexusflow.contracts.api.RefreshSessionRequest
import de.jensklingenberg.ktorfit.http.Body
import de.jensklingenberg.ktorfit.http.Headers
import de.jensklingenberg.ktorfit.http.POST

/** Paths owned by the NexusFlow authentication contract. */
internal object AuthEndpoints {
    const val GOOGLE_EXCHANGE = "v1/auth/google/exchange"
    const val REFRESH = "v1/auth/refresh"
    const val LOGOUT = "v1/auth/logout"
}

/** The feature-local Ktorfit API. [AuthRemoteDataSource] consumes the common response envelope. */
internal interface AuthApi {
    @POST(AuthEndpoints.GOOGLE_EXCHANGE)
    @Headers(JSON_CONTENT_TYPE_HEADER)
    suspend fun exchangeGoogleIdToken(
        @Body request: GoogleExchangeRequest,
    ): KResponse<AuthSessionResponse>

    @POST(AuthEndpoints.REFRESH)
    @Headers(JSON_CONTENT_TYPE_HEADER)
    suspend fun refresh(
        @Body request: RefreshSessionRequest,
    ): KResponse<AuthSessionResponse>

    @POST(AuthEndpoints.LOGOUT)
    @Headers(JSON_CONTENT_TYPE_HEADER)
    suspend fun logout(
        @Body request: LogoutRequest,
    ): KResponse<Unit>
}

/** Remote Auth protocol boundary: it only executes feature-local API calls. */
internal class AuthRemoteDataSource(
    private val api: AuthApi,
    private val apiCalls: ApiCallExecutor,
) {
    suspend fun exchangeGoogleIdToken(idToken: String): Result<AuthSessionResponse> =
        apiCalls.execute(AuthEndpoints.GOOGLE_EXCHANGE) {
            api.exchangeGoogleIdToken(GoogleExchangeRequest(idToken))
        }

    suspend fun refresh(refreshToken: String): Result<AuthSessionResponse> =
        apiCalls.execute(AuthEndpoints.REFRESH) {
            api.refresh(RefreshSessionRequest(refreshToken))
        }

    suspend fun logout(refreshToken: String): Result<Unit> =
        apiCalls.executeUnit(AuthEndpoints.LOGOUT) {
            api.logout(LogoutRequest(refreshToken))
        }
}

private const val JSON_CONTENT_TYPE_HEADER = "Content-Type: application/json"
