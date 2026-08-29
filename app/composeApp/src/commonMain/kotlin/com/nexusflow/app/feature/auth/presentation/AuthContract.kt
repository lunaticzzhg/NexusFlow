package com.nexusflow.app.feature.auth.presentation

import com.nexusflow.app.core.systemui.SystemUiRequestId
import com.nexusflow.app.feature.auth.domain.AppContextSnapshot

sealed interface AuthState {
    data object Restoring : AuthState

    data class Unauthenticated(
        val login: AuthLoginUiState = AuthLoginUiState(),
    ) : AuthState

    data object AuthenticatingGoogle : AuthState

    data class Authenticated(
        val context: AppContextSnapshot,
    ) : AuthState

    data object Unavailable : AuthState
}

data class AuthLoginUiState(
    val devLoginEmail: String = DEFAULT_DEV_LOGIN_EMAIL,
    val isDevLoginSubmitting: Boolean = false,
    val showInvalidDevCredential: Boolean = false,
)

sealed interface AuthIntent {
    data object StartGoogleSignIn : AuthIntent

    data class DevLoginEmailChanged(
        val email: String,
    ) : AuthIntent

    data object DevLoginPasswordChanged : AuthIntent

    data class SubmitDevLogin(
        val password: String,
    ) : AuthIntent

    data class GoogleSignInResolved(
        val requestId: SystemUiRequestId,
        val result: GoogleSignInOutcome,
    ) : AuthIntent

    data object Retry : AuthIntent

    data object Logout : AuthIntent
}

sealed interface GoogleSignInOutcome {
    data class Success(
        val idToken: String,
    ) : GoogleSignInOutcome

    data object Cancelled : GoogleSignInOutcome

    data object Unavailable : GoogleSignInOutcome

    data object Failed : GoogleSignInOutcome
}

sealed interface AuthEffect {
    data class RequestGoogleSignIn(
        val requestId: SystemUiRequestId,
        val serverClientId: String,
    ) : AuthEffect
}

internal const val DEFAULT_DEV_LOGIN_EMAIL = "dev@nexusflow.local"
