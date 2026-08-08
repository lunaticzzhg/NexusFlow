package com.nexusflow.app.feature.auth.presentation

import com.nexusflow.app.core.systemui.SystemUiRequestId
import com.nexusflow.app.feature.auth.domain.AppContextSnapshot

sealed interface AuthState {
    data object Restoring : AuthState

    data object Unauthenticated : AuthState

    data object AuthenticatingGoogle : AuthState

    data class Authenticated(
        val context: AppContextSnapshot,
    ) : AuthState

    data object Unavailable : AuthState
}

sealed interface AuthIntent {
    data object StartGoogleSignIn : AuthIntent

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
