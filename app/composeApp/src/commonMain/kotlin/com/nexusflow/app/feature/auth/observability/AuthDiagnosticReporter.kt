package com.nexusflow.app.feature.auth.observability

import com.nexusflow.app.core.observability.AppLogger
import com.nexusflow.app.core.observability.LogFields
import com.nexusflow.app.core.observability.LogTag
import com.nexusflow.app.core.observability.logFields

sealed interface AuthDiagnosticEvent {
    data class SessionRestore(
        val outcome: RestoreOutcome,
    ) : AuthDiagnosticEvent

    data class SessionRefresh(
        val outcome: RefreshOutcome,
    ) : AuthDiagnosticEvent

    data class GoogleSignIn(
        val outcome: GoogleSignInOutcome,
    ) : AuthDiagnosticEvent

    data class DevLogin(
        val outcome: DevLoginOutcome,
    ) : AuthDiagnosticEvent

    data class Logout(
        val outcome: LogoutOutcome,
    ) : AuthDiagnosticEvent
}

enum class RestoreOutcome {
    NO_STORED_SESSION,
    STORED_ACCESS_TOKEN_VALID,
    STORED_REFRESH_TOKEN_EXPIRED,
    STORAGE_UNAVAILABLE,
    UNEXPECTED_FAILURE,
}

enum class RefreshOutcome {
    SUCCEEDED,
    STORAGE_UNAVAILABLE,
    UNAUTHENTICATED,
    UNAVAILABLE,
}

enum class GoogleSignInOutcome {
    REQUESTED,
    CONFIGURATION_UNAVAILABLE,
    EXCHANGE_SUCCEEDED,
    CANCELLED,
    PLATFORM_UNAVAILABLE,
    PLATFORM_FAILED,
    STORAGE_UNAVAILABLE,
    UNAUTHENTICATED,
    UNAVAILABLE,
}

enum class DevLoginOutcome {
    REQUESTED,
    EXCHANGE_SUCCEEDED,
    INVALID_CREDENTIAL,
    STORAGE_UNAVAILABLE,
    UNAVAILABLE,
}

enum class LogoutOutcome {
    SUCCEEDED,
    REMOTE_UNAVAILABLE,
    STORAGE_UNAVAILABLE,
}

interface AuthDiagnosticReporter {
    fun report(event: AuthDiagnosticEvent)
}

class AppLoggerAuthDiagnosticReporter(
    private val logger: AppLogger,
) : AuthDiagnosticReporter {
    override fun report(event: AuthDiagnosticEvent) {
        runCatching {
            when (event) {
                is AuthDiagnosticEvent.SessionRestore -> logRestore(event.outcome)
                is AuthDiagnosticEvent.SessionRefresh -> logRefresh(event.outcome)
                is AuthDiagnosticEvent.GoogleSignIn -> logGoogleSignIn(event.outcome)
                is AuthDiagnosticEvent.DevLogin -> logDevLogin(event.outcome)
                is AuthDiagnosticEvent.Logout -> logLogout(event.outcome)
            }
        }
    }

    private fun logRestore(outcome: RestoreOutcome) {
        val fields = fields(outcome)
        when (outcome) {
            RestoreOutcome.NO_STORED_SESSION,
            RestoreOutcome.STORED_ACCESS_TOKEN_VALID,
            RestoreOutcome.STORED_REFRESH_TOKEN_EXPIRED,
            -> logger.info(authLogTag, "auth_session_restore", fields)
            RestoreOutcome.STORAGE_UNAVAILABLE,
            RestoreOutcome.UNEXPECTED_FAILURE,
            -> logger.error(authLogTag, "auth_session_restore", fields)
        }
    }

    private fun logRefresh(outcome: RefreshOutcome) {
        val fields = fields(outcome)
        when (outcome) {
            RefreshOutcome.SUCCEEDED -> logger.info(authLogTag, "auth_session_refresh", fields)
            RefreshOutcome.STORAGE_UNAVAILABLE,
            RefreshOutcome.UNAUTHENTICATED,
            RefreshOutcome.UNAVAILABLE,
            -> logger.error(authLogTag, "auth_session_refresh", fields)
        }
    }

    private fun logGoogleSignIn(outcome: GoogleSignInOutcome) {
        val fields = fields(outcome)
        when (outcome) {
            GoogleSignInOutcome.REQUESTED,
            GoogleSignInOutcome.EXCHANGE_SUCCEEDED,
            GoogleSignInOutcome.CANCELLED,
            -> logger.info(authLogTag, "auth_google_sign_in", fields)
            GoogleSignInOutcome.CONFIGURATION_UNAVAILABLE,
            GoogleSignInOutcome.PLATFORM_UNAVAILABLE,
            GoogleSignInOutcome.PLATFORM_FAILED,
            GoogleSignInOutcome.STORAGE_UNAVAILABLE,
            GoogleSignInOutcome.UNAUTHENTICATED,
            GoogleSignInOutcome.UNAVAILABLE,
            -> logger.error(authLogTag, "auth_google_sign_in", fields)
        }
    }

    private fun logDevLogin(outcome: DevLoginOutcome) {
        val fields = fields(outcome)
        when (outcome) {
            DevLoginOutcome.REQUESTED,
            DevLoginOutcome.EXCHANGE_SUCCEEDED,
            -> logger.info(authLogTag, "auth_dev_login", fields)
            DevLoginOutcome.INVALID_CREDENTIAL,
            DevLoginOutcome.STORAGE_UNAVAILABLE,
            DevLoginOutcome.UNAVAILABLE,
            -> logger.error(authLogTag, "auth_dev_login", fields)
        }
    }

    private fun logLogout(outcome: LogoutOutcome) {
        val fields = fields(outcome)
        when (outcome) {
            LogoutOutcome.SUCCEEDED -> logger.info(authLogTag, "auth_logout", fields)
            LogoutOutcome.REMOTE_UNAVAILABLE,
            LogoutOutcome.STORAGE_UNAVAILABLE,
            -> logger.error(authLogTag, "auth_logout", fields)
        }
    }

    private fun fields(outcome: Enum<*>): LogFields =
        logFields {
            "outcome" value outcome.name.lowercase()
        }
}

private val authLogTag = LogTag.of("AuthSession")
