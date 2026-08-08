package com.nexusflow.app.core.systemui

data class SystemUiRequestId(val value: String)

data class GoogleSignInRequest(
    val id: SystemUiRequestId,
    val serverClientId: String,
)

sealed interface GoogleSignInResult {
    val id: SystemUiRequestId

    data class Success(
        override val id: SystemUiRequestId,
        val idToken: String,
    ) : GoogleSignInResult

    data class Cancelled(
        override val id: SystemUiRequestId,
    ) : GoogleSignInResult

    data class Unavailable(
        override val id: SystemUiRequestId,
    ) : GoogleSignInResult

    data class Failed(
        override val id: SystemUiRequestId,
    ) : GoogleSignInResult
}

/** A window-scoped bridge for one foreground system UI operation at a time. */
interface SystemUiGateway {
    suspend fun requestGoogleSignIn(request: GoogleSignInRequest): GoogleSignInResult
}
