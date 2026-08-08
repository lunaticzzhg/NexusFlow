package com.nexusflow.app.feature.auth.data

import com.nexusflow.app.core.config.RuntimeConfig
import com.nexusflow.app.feature.auth.domain.AuthFailure
import com.nexusflow.contracts.api.AuthSessionResponse
import com.nexusflow.contracts.api.GoogleExchangeRequest
import com.nexusflow.contracts.api.LogoutRequest
import com.nexusflow.contracts.api.RefreshSessionRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException

class AuthApi(
    private val httpClient: HttpClient,
    private val runtimeConfig: RuntimeConfig,
) {
    suspend fun exchangeGoogleIdToken(idToken: String): Result<AuthSessionResponse> =
        postSession(
            path = "/v1/auth/google/exchange",
            body = GoogleExchangeRequest(idToken),
        )

    suspend fun refresh(refreshToken: String): Result<AuthSessionResponse> =
        postSession(
            path = "/v1/auth/refresh",
            body = RefreshSessionRequest(refreshToken),
        )

    suspend fun logout(refreshToken: String): Result<Unit> =
        try {
            val response =
                httpClient.post(runtimeConfig.apiBaseUrl.trimEnd('/') + "/v1/auth/logout") {
                    contentType(ContentType.Application.Json)
                    setBody(LogoutRequest(refreshToken))
                }
            when {
                response.status == HttpStatusCode.NoContent -> Result.success(Unit)
                response.status == HttpStatusCode.Unauthorized -> throw AuthApiException(AuthFailure.Unauthenticated)
                else -> throw AuthApiException(AuthFailure.Unavailable)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Result.failure(exception)
        }

    private suspend fun postSession(
        path: String,
        body: Any,
    ): Result<AuthSessionResponse> =
        try {
            val response =
                httpClient.post(runtimeConfig.apiBaseUrl.trimEnd('/') + path) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            when {
                response.status.isSuccess() -> Result.success(response.body())
                response.status == HttpStatusCode.Unauthorized -> throw AuthApiException(AuthFailure.Unauthenticated)
                response.status == HttpStatusCode.UnprocessableEntity -> {
                    throw AuthApiException(AuthFailure.InvalidCredential)
                }
                else -> throw AuthApiException(AuthFailure.Unavailable)
            }
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            Result.failure(exception)
        }
}

class AuthApiException(
    val failure: AuthFailure,
) : IllegalStateException()
