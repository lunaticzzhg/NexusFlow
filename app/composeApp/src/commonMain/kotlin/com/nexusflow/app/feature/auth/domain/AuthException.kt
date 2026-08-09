package com.nexusflow.app.feature.auth.domain

/** Failures whose meaning is specific to the authentication feature. */
sealed class AuthException : IllegalStateException() {
    data object Unauthenticated : AuthException()

    data object InvalidCredential : AuthException()
}
