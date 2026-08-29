package com.nexusflow.app.feature.auth.presentation

import com.nexusflow.app.core.config.RuntimeConfig
import com.nexusflow.app.core.network.FirstPartyApiSession
import com.nexusflow.app.core.network.FirstPartySessionRefresh
import com.nexusflow.app.core.security.SecureStoreUnavailableException
import com.nexusflow.app.core.systemui.SystemUiRequestId
import com.nexusflow.app.core.time.AppClock
import com.nexusflow.app.feature.auth.data.AuthSessionStore
import com.nexusflow.app.feature.auth.domain.AuthException
import com.nexusflow.app.feature.auth.domain.AuthRepository
import com.nexusflow.app.feature.auth.domain.AuthSession
import com.nexusflow.app.feature.auth.observability.AuthDiagnosticEvent
import com.nexusflow.app.feature.auth.observability.AuthDiagnosticReporter
import com.nexusflow.app.feature.auth.observability.DevLoginOutcome
import com.nexusflow.app.feature.auth.observability.LogoutOutcome
import com.nexusflow.app.feature.auth.observability.RefreshOutcome
import com.nexusflow.app.feature.auth.observability.RestoreOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.nexusflow.app.feature.auth.observability.GoogleSignInOutcome as DiagnosticGoogleSignInOutcome

/** The sole owner of persisted sessions and the observable identity snapshot. */
class AuthSessionController(
    private val repository: AuthRepository,
    private val sessionStore: AuthSessionStore,
    private val runtimeConfig: RuntimeConfig,
    private val clock: AppClock,
    private val diagnosticReporter: AuthDiagnosticReporter = NoOpAuthDiagnosticReporter,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : FirstPartyApiSession {
    private val operationMutex = Mutex()
    private val _state = MutableStateFlow<AuthState>(AuthState.Restoring)
    private val _effects = MutableSharedFlow<AuthEffect>()
    private var pendingRequestId: SystemUiRequestId? = null
    private var activeSession: AuthSession? = null

    val state: StateFlow<AuthState> = _state.asStateFlow()
    val effects: SharedFlow<AuthEffect> = _effects.asSharedFlow()

    fun dispatch(intent: AuthIntent) {
        scope.launch {
            operationMutex.withLock {
                when (intent) {
                    AuthIntent.StartGoogleSignIn -> requestGoogleSignIn()
                    is AuthIntent.DevLoginEmailChanged -> updateDevLoginEmail(intent.email)
                    AuthIntent.DevLoginPasswordChanged -> clearDevLoginCredentialError()
                    is AuthIntent.SubmitDevLogin -> submitDevLogin(intent.password)
                    is AuthIntent.GoogleSignInResolved -> resolveGoogleSignIn(intent)
                    AuthIntent.Retry -> restoreLocked()
                    AuthIntent.Logout -> logout()
                }
            }
        }
    }

    suspend fun restore() {
        operationMutex.withLock {
            restoreLocked()
        }
    }

    override suspend fun currentAccessToken(): String? =
        operationMutex.withLock {
            readActiveSessionLocked()?.accessToken
        }

    override suspend fun refreshAccessTokenIfCurrent(accessToken: String): FirstPartySessionRefresh =
        operationMutex.withLock {
            val session = readActiveSessionLocked() ?: return@withLock FirstPartySessionRefresh.Unauthenticated
            if (session.accessToken != accessToken) {
                return@withLock FirstPartySessionRefresh.TokenAvailable(session.accessToken)
            }

            repository.refresh(session.refreshToken).fold(
                onSuccess = { refreshed ->
                    try {
                        sessionStore.write(refreshed)
                        publish(refreshed)
                        report(AuthDiagnosticEvent.SessionRefresh(RefreshOutcome.SUCCEEDED))
                        FirstPartySessionRefresh.TokenAvailable(refreshed.accessToken)
                    } catch (_: SecureStoreUnavailableException) {
                        _state.value = AuthState.Unavailable
                        report(AuthDiagnosticEvent.SessionRefresh(RefreshOutcome.STORAGE_UNAVAILABLE))
                        FirstPartySessionRefresh.Unavailable
                    }
                },
                onFailure = { failure ->
                    failure.rethrowIfCancellation()
                    if (failure.isUnauthenticated()) {
                        sessionStore.clear()
                        activeSession = null
                        _state.value = AuthState.Unauthenticated()
                        report(AuthDiagnosticEvent.SessionRefresh(RefreshOutcome.UNAUTHENTICATED))
                        FirstPartySessionRefresh.Unauthenticated
                    } else {
                        _state.value = AuthState.Unavailable
                        report(AuthDiagnosticEvent.SessionRefresh(RefreshOutcome.UNAVAILABLE))
                        FirstPartySessionRefresh.Unavailable
                    }
                },
            )
        }

    override suspend fun clearSessionIfCurrent(accessToken: String): Boolean =
        operationMutex.withLock {
            val session = readActiveSessionLocked() ?: return@withLock false
            if (session.accessToken != accessToken) {
                return@withLock false
            }
            sessionStore.clear()
            activeSession = null
            _state.value = AuthState.Unauthenticated()
            true
        }

    private suspend fun restoreLocked() {
        _state.value = AuthState.Restoring
        try {
            val storedSession = sessionStore.read()
            when {
                storedSession == null -> {
                    activeSession = null
                    _state.value = AuthState.Unauthenticated()
                    report(AuthDiagnosticEvent.SessionRestore(RestoreOutcome.NO_STORED_SESSION))
                }
                storedSession.accessTokenExpiresAtMillis > clock.currentTimeMillis() -> {
                    publish(storedSession)
                    report(AuthDiagnosticEvent.SessionRestore(RestoreOutcome.STORED_ACCESS_TOKEN_VALID))
                }
                storedSession.refreshTokenExpiresAtMillis <= clock.currentTimeMillis() -> {
                    sessionStore.clear()
                    activeSession = null
                    _state.value = AuthState.Unauthenticated()
                    report(AuthDiagnosticEvent.SessionRestore(RestoreOutcome.STORED_REFRESH_TOKEN_EXPIRED))
                }
                else -> refreshStoredSession(storedSession)
            }
        } catch (_: SecureStoreUnavailableException) {
            _state.value = AuthState.Unavailable
            report(AuthDiagnosticEvent.SessionRestore(RestoreOutcome.STORAGE_UNAVAILABLE))
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            _state.value = AuthState.Unavailable
            report(AuthDiagnosticEvent.SessionRestore(RestoreOutcome.UNEXPECTED_FAILURE))
        }
    }

    internal suspend fun requestGoogleSignIn() {
        if (_state.value is AuthState.AuthenticatingGoogle) {
            return
        }
        if (currentLoginState().isDevLoginSubmitting) {
            return
        }
        if (runtimeConfig.googleServerClientId.isBlank()) {
            _state.value = AuthState.Unavailable
            report(AuthDiagnosticEvent.GoogleSignIn(DiagnosticGoogleSignInOutcome.CONFIGURATION_UNAVAILABLE))
            return
        }
        val requestId = SystemUiRequestId("google-${clock.currentTimeMillis()}")
        pendingRequestId = requestId
        _state.value = AuthState.AuthenticatingGoogle
        report(AuthDiagnosticEvent.GoogleSignIn(DiagnosticGoogleSignInOutcome.REQUESTED))
        _effects.emit(AuthEffect.RequestGoogleSignIn(requestId, runtimeConfig.googleServerClientId))
    }

    internal suspend fun submitDevLogin(password: String) {
        val login = currentLoginState()
        if (login.isDevLoginSubmitting) {
            return
        }
        _state.value =
            AuthState.Unauthenticated(
                login.copy(isDevLoginSubmitting = true, showInvalidDevCredential = false),
            )
        report(AuthDiagnosticEvent.DevLogin(DevLoginOutcome.REQUESTED))
        activateDevLogin(repository.devLogin(login.devLoginEmail, password), login)
    }

    internal fun updateDevLoginEmail(email: String) {
        val login = currentLoginState()
        if (login.isDevLoginSubmitting) {
            return
        }
        _state.value =
            AuthState.Unauthenticated(
                login.copy(devLoginEmail = email, showInvalidDevCredential = false),
            )
    }

    internal fun clearDevLoginCredentialError() {
        val login = currentLoginState()
        if (login.isDevLoginSubmitting || !login.showInvalidDevCredential) {
            return
        }
        _state.value =
            AuthState.Unauthenticated(
                login.copy(showInvalidDevCredential = false),
            )
    }

    private suspend fun resolveGoogleSignIn(intent: AuthIntent.GoogleSignInResolved) {
        if (intent.requestId != pendingRequestId) return

        pendingRequestId = null
        when (val result = intent.result) {
            is GoogleSignInOutcome.Success -> activate(repository.exchangeGoogleIdToken(result.idToken))
            GoogleSignInOutcome.Cancelled -> {
                _state.value = AuthState.Unauthenticated()
                report(AuthDiagnosticEvent.GoogleSignIn(DiagnosticGoogleSignInOutcome.CANCELLED))
            }
            GoogleSignInOutcome.Unavailable,
            GoogleSignInOutcome.Failed,
            -> {
                _state.value = AuthState.Unavailable
                report(
                    AuthDiagnosticEvent.GoogleSignIn(
                        if (result == GoogleSignInOutcome.Unavailable) {
                            DiagnosticGoogleSignInOutcome.PLATFORM_UNAVAILABLE
                        } else {
                            DiagnosticGoogleSignInOutcome.PLATFORM_FAILED
                        },
                    ),
                )
            }
        }
    }

    private suspend fun refreshStoredSession(session: AuthSession) {
        repository.refresh(session.refreshToken).fold(
            onSuccess = { refreshed ->
                try {
                    sessionStore.write(refreshed)
                    publish(refreshed)
                    report(AuthDiagnosticEvent.SessionRefresh(RefreshOutcome.SUCCEEDED))
                } catch (_: SecureStoreUnavailableException) {
                    _state.value = AuthState.Unavailable
                    report(AuthDiagnosticEvent.SessionRefresh(RefreshOutcome.STORAGE_UNAVAILABLE))
                }
            },
            onFailure = { failure ->
                failure.rethrowIfCancellation()
                if (failure.isUnauthenticated()) {
                    sessionStore.clear()
                    activeSession = null
                    _state.value = AuthState.Unauthenticated()
                    report(AuthDiagnosticEvent.SessionRefresh(RefreshOutcome.UNAUTHENTICATED))
                } else {
                    _state.value = AuthState.Unavailable
                    report(AuthDiagnosticEvent.SessionRefresh(RefreshOutcome.UNAVAILABLE))
                }
            },
        )
    }

    private suspend fun activate(result: Result<AuthSession>) {
        result.fold(
            onSuccess = { session ->
                try {
                    sessionStore.write(session)
                    publish(session)
                    report(AuthDiagnosticEvent.GoogleSignIn(DiagnosticGoogleSignInOutcome.EXCHANGE_SUCCEEDED))
                } catch (_: SecureStoreUnavailableException) {
                    _state.value = AuthState.Unavailable
                    report(AuthDiagnosticEvent.GoogleSignIn(DiagnosticGoogleSignInOutcome.STORAGE_UNAVAILABLE))
                }
            },
            onFailure = { failure ->
                failure.rethrowIfCancellation()
                _state.value = if (failure.isUnauthenticated()) AuthState.Unauthenticated() else AuthState.Unavailable
                report(
                    AuthDiagnosticEvent.GoogleSignIn(
                        if (failure.isUnauthenticated()) {
                            DiagnosticGoogleSignInOutcome.UNAUTHENTICATED
                        } else {
                            DiagnosticGoogleSignInOutcome.UNAVAILABLE
                        },
                    ),
                )
            },
        )
    }

    private suspend fun activateDevLogin(
        result: Result<AuthSession>,
        previousLogin: AuthLoginUiState,
    ) {
        result.fold(
            onSuccess = { session ->
                try {
                    sessionStore.write(session)
                    publish(session)
                    report(AuthDiagnosticEvent.DevLogin(DevLoginOutcome.EXCHANGE_SUCCEEDED))
                } catch (_: SecureStoreUnavailableException) {
                    _state.value = AuthState.Unavailable
                    report(AuthDiagnosticEvent.DevLogin(DevLoginOutcome.STORAGE_UNAVAILABLE))
                }
            },
            onFailure = { failure ->
                failure.rethrowIfCancellation()
                if (failure == AuthException.InvalidCredential || failure.isUnauthenticated()) {
                    _state.value =
                        AuthState.Unauthenticated(
                            previousLogin.copy(isDevLoginSubmitting = false, showInvalidDevCredential = true),
                        )
                    report(AuthDiagnosticEvent.DevLogin(DevLoginOutcome.INVALID_CREDENTIAL))
                } else {
                    _state.value = AuthState.Unavailable
                    report(AuthDiagnosticEvent.DevLogin(DevLoginOutcome.UNAVAILABLE))
                }
            },
        )
    }

    private suspend fun logout() {
        pendingRequestId = null
        val session = activeSession
        try {
            val logoutFailure = session?.let { repository.logout(it.refreshToken).exceptionOrNull() }
            logoutFailure?.let { failure ->
                failure.rethrowIfCancellation()
                report(AuthDiagnosticEvent.Logout(LogoutOutcome.REMOTE_UNAVAILABLE))
            }
            sessionStore.clear()
            activeSession = null
            _state.value = AuthState.Unauthenticated()
            if (logoutFailure == null) {
                report(AuthDiagnosticEvent.Logout(LogoutOutcome.SUCCEEDED))
            }
        } catch (_: SecureStoreUnavailableException) {
            _state.value = AuthState.Unavailable
            report(AuthDiagnosticEvent.Logout(LogoutOutcome.STORAGE_UNAVAILABLE))
        }
    }

    private fun publish(session: AuthSession) {
        activeSession = session
        _state.value = AuthState.Authenticated(session.context)
    }

    private suspend fun readActiveSessionLocked(): AuthSession? =
        activeSession ?: try {
            sessionStore.read()?.also { activeSession = it }
        } catch (_: SecureStoreUnavailableException) {
            null
        }

    private fun report(event: AuthDiagnosticEvent) {
        runCatching { diagnosticReporter.report(event) }
    }

    private fun currentLoginState(): AuthLoginUiState = (_state.value as? AuthState.Unauthenticated)?.login ?: AuthLoginUiState()
}

private object NoOpAuthDiagnosticReporter : AuthDiagnosticReporter {
    override fun report(event: AuthDiagnosticEvent) = Unit
}

private fun Throwable?.isUnauthenticated(): Boolean = this == AuthException.Unauthenticated

private fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}
