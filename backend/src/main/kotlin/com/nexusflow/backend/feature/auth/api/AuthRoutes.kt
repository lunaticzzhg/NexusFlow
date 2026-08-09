package com.nexusflow.backend.feature.auth.api

import com.nexusflow.backend.core.http.respondError
import com.nexusflow.backend.core.http.respondSuccess
import com.nexusflow.backend.feature.auth.application.AuthService
import com.nexusflow.backend.feature.auth.application.InvalidSessionException
import com.nexusflow.backend.feature.auth.domain.IssuedSession
import com.nexusflow.backend.feature.auth.infrastructure.InvalidGoogleIdentityException
import com.nexusflow.contracts.api.AuthSessionResponse
import com.nexusflow.contracts.api.GoogleExchangeRequest
import com.nexusflow.contracts.api.LogoutRequest
import com.nexusflow.contracts.api.RefreshSessionRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.routing.Routing
import io.ktor.server.routing.post

fun Routing.authRoutes(authService: AuthService) {
    post("/v1/auth/google/exchange") {
        try {
            call.respondSuccess(authService.exchangeGoogle(call.receive<GoogleExchangeRequest>().idToken).toResponse())
        } catch (error: InvalidGoogleIdentityException) {
            call.respondError(HttpStatusCode.Unauthorized, "Google identity could not be verified")
        } catch (error: IllegalArgumentException) {
            call.respondError(HttpStatusCode.UnprocessableEntity, "Invalid request")
        }
    }

    post("/v1/auth/refresh") {
        try {
            call.respondSuccess(authService.refresh(call.receive<RefreshSessionRequest>().refreshToken).toResponse())
        } catch (error: InvalidSessionException) {
            call.respondError(HttpStatusCode.Unauthorized, "Session is no longer valid")
        } catch (error: IllegalArgumentException) {
            call.respondError(HttpStatusCode.UnprocessableEntity, "Invalid request")
        }
    }

    post("/v1/auth/logout") {
        try {
            authService.logout(call.receive<LogoutRequest>().refreshToken)
            call.respondSuccess<Unit>()
        } catch (error: IllegalArgumentException) {
            call.respondError(HttpStatusCode.UnprocessableEntity, "Invalid request")
        }
    }
}

private fun IssuedSession.toResponse(): AuthSessionResponse = AuthSessionResponse(
    accessToken = accessToken,
    accessTokenExpiresInSeconds = accessTokenExpiresInSeconds,
    refreshToken = refreshToken,
    refreshTokenExpiresInSeconds = refreshTokenExpiresInSeconds,
    userId = principal.userId.toString(),
    tenantId = principal.tenantId.toString(),
)
