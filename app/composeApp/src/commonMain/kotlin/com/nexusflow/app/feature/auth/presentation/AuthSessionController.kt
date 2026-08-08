package com.nexusflow.app.feature.auth.presentation

import com.nexusflow.app.core.config.RuntimeConfig
import com.nexusflow.app.core.security.SecureStoreUnavailableException
import com.nexusflow.app.core.systemui.SystemUiRequestId
import com.nexusflow.app.core.time.AppClock
import com.nexusflow.app.feature.auth.data.AuthSessionStore
import com.nexusflow.app.feature.auth.domain.AuthRepository
import com.nexusflow.app.feature.auth.domain.AuthSession
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

/** The sole owner of persisted sessions and the observable identity snapshot. */
class AuthSessionController(
    private val repository: AuthRepository,
    private val sessionStore: AuthSessionStore,
    private val runtimeConfig: RuntimeConfig,
    private val clock: AppClock,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
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

    private suspend fun restoreLocked() {
        _state.value = AuthState.Restoring
        try {
            val storedSession = sessionStore.read()
            when {
                storedSession == null -> {
                    activeSession = null
                    _state.value = AuthState.Unauthenticated
                }
                storedSession.accessTokenExpiresAtMillis > clock.currentTimeMillis() -> publish(storedSession)
                storedSession.refreshTokenExpiresAtMillis <= clock.currentTimeMillis() -> {
                    sessionStore.clear()
                    activeSession = null
                    _state.value = AuthState.Unauthenticated
                }
                else -> refreshStoredSession(storedSession)
            }
        } catch (_: SecureStoreUnavailableException) {
            _state.value = AuthState.Unavailable
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            _state.value = AuthState.Unavailable
        }
    }

    internal suspend fun requestGoogleSignIn() {
        if (_state.value is AuthState.AuthenticatingGoogle) {
            return
        }
        if (runtimeConfig.googleServerClientId.isBlank()) {
            _state.value = AuthState.Unavailable
            return
        }
        val requestId = SystemUiRequestId("google-${clock.currentTimeMillis()}")
        pendingRequestId = requestId
        _state.value = AuthState.AuthenticatingGoogle
        _effects.emit(AuthEffect.RequestGoogleSignIn(requestId, runtimeConfig.googleServerClientId))
    }

    private suspend fun resolveGoogleSignIn(intent: AuthIntent.GoogleSignInResolved) {
        if (intent.requestId != pendingRequestId) return

        pendingRequestId = null
        when (val result = intent.result) {
            is GoogleSignInOutcome.Success -> activate(repository.exchangeGoogleIdToken(result.idToken))
            GoogleSignInOutcome.Cancelled -> _state.value = AuthState.Unauthenticated
            GoogleSignInOutcome.Unavailable,
            GoogleSignInOutcome.Failed,
            -> _state.value = AuthState.Unavailable
        }
    }

    private suspend fun refreshStoredSession(session: AuthSession) {
        repository.refresh(session.refreshToken).fold(
            onSuccess = { refreshed ->
                try {
                    sessionStore.write(refreshed)
                    publish(refreshed)
                } catch (_: SecureStoreUnavailableException) {
                    _state.value = AuthState.Unavailable
                }
            },
            onFailure = { failure ->
                if (failure.isUnauthenticated()) {
                    sessionStore.clear()
                    activeSession = null
                    _state.value = AuthState.Unauthenticated
                } else {
                    _state.value = AuthState.Unavailable
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
                } catch (_: SecureStoreUnavailableException) {
                    _state.value = AuthState.Unavailable
                }
            },
            onFailure = { failure ->
                _state.value = if (failure.isUnauthenticated()) AuthState.Unauthenticated else AuthState.Unavailable
            },
        )
    }

    private suspend fun logout() {
        pendingRequestId = null
        val session = activeSession
        try {
            if (session != null) repository.logout(session.refreshToken)
            sessionStore.clear()
            activeSession = null
            _state.value = AuthState.Unauthenticated
        } catch (_: SecureStoreUnavailableException) {
            _state.value = AuthState.Unavailable
        }
    }

    private fun publish(session: AuthSession) {
        activeSession = session
        _state.value = AuthState.Authenticated(session.context)
    }
}

private fun Throwable?.isUnauthenticated(): Boolean =
    this is com.nexusflow.app.feature.auth.data.AuthApiException &&
        failure == com.nexusflow.app.feature.auth.domain.AuthFailure.Unauthenticated
