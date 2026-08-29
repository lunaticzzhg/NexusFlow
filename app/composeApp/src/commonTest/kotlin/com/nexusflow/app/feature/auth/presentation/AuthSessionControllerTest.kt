package com.nexusflow.app.feature.auth.presentation

import com.nexusflow.app.core.config.BuildMode
import com.nexusflow.app.core.config.RuntimeConfig
import com.nexusflow.app.core.error.AppException
import com.nexusflow.app.core.network.FirstPartySessionRefresh
import com.nexusflow.app.core.security.SecureKey
import com.nexusflow.app.core.security.SecureStore
import com.nexusflow.app.core.security.SecureStoreNamespace
import com.nexusflow.app.core.time.AppClock
import com.nexusflow.app.feature.auth.data.AuthSessionStore
import com.nexusflow.app.feature.auth.domain.AppContextSnapshot
import com.nexusflow.app.feature.auth.domain.AuthException
import com.nexusflow.app.feature.auth.domain.AuthRepository
import com.nexusflow.app.feature.auth.domain.AuthSession
import com.nexusflow.app.feature.auth.observability.AuthDiagnosticEvent
import com.nexusflow.app.feature.auth.observability.AuthDiagnosticReporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthSessionControllerTest {
    @Test
    fun restorePublishesStoredSessionWhenAccessTokenIsStillValid() =
        runBlocking {
            val store = MemorySecureStore()
            AuthSessionStore(store).write(validSession)
            val controller = controller(store)

            controller.restore()

            assertEquals(AuthState.Authenticated(validSession.context), controller.state.value)
        }

    @Test
    fun restoreClearsExpiredRefreshTokenAndReturnsToLogin() =
        runBlocking {
            val store = MemorySecureStore()
            AuthSessionStore(store).write(
                validSession.copy(
                    accessTokenExpiresAtMillis = 999,
                    refreshTokenExpiresAtMillis = 999,
                ),
            )
            val controller = controller(store)

            controller.restore()

            assertEquals(AuthState.Unauthenticated(), controller.state.value)
            assertEquals(null, AuthSessionStore(store).read())
        }

    @Test
    fun repeatedGoogleSignInRequestKeepsTheActiveAuthenticationState() =
        runBlocking {
            val controller = controller(MemorySecureStore())
            val firstEffect = CompletableDeferred<AuthEffect>()
            val collector =
                launch(start = CoroutineStart.UNDISPATCHED) {
                    controller.effects.collect { effect ->
                        firstEffect.complete(effect)
                    }
                }

            controller.requestGoogleSignIn()
            firstEffect.await()
            controller.requestGoogleSignIn()

            assertEquals(AuthState.AuthenticatingGoogle, controller.state.value)
            collector.cancelAndJoin()
        }

    @Test
    fun diagnosticFailureDoesNotChangeRestoredState() =
        runBlocking {
            val store = MemorySecureStore()
            AuthSessionStore(store).write(validSession)
            val controller = controller(store, diagnosticReporter = ThrowingDiagnosticReporter)

            controller.restore()

            assertEquals(AuthState.Authenticated(validSession.context), controller.state.value)
        }

    @Test
    fun devLoginPublishesBackendIssuedSession() =
        runBlocking {
            val store = MemorySecureStore()
            val repository = RecordingDevLoginRepository(Result.success(validSession))
            val controller = controller(store, repository = repository)

            controller.submitDevLogin("devpass")

            assertEquals(listOf("dev@nexusflow.local" to "devpass"), repository.devLoginRequests)
            assertEquals(validSession, AuthSessionStore(store).read())
            assertEquals(AuthState.Authenticated(validSession.context), controller.state.value)
        }

    @Test
    fun invalidDevLoginCredentialReturnsToLoginWithoutStoringSession() =
        runBlocking {
            val store = MemorySecureStore()
            val repository = RecordingDevLoginRepository(Result.failure(AuthException.InvalidCredential))
            val controller = controller(store, repository = repository)

            controller.submitDevLogin("wrong-password")

            assertEquals(null, AuthSessionStore(store).read())
            assertEquals(
                AuthState.Unauthenticated(
                    AuthLoginUiState(showInvalidDevCredential = true),
                ),
                controller.state.value,
            )
        }

    @Test
    fun devLoginEmailChangesAreIgnoredWhileSubmitting() =
        runBlocking {
            val repository = SuspendedDevLoginRepository()
            val controller = controller(MemorySecureStore(), repository = repository)

            controller.updateDevLoginEmail("custom@nexusflow.local")
            val submit = launch { controller.submitDevLogin("devpass") }
            repository.started.await()
            controller.updateDevLoginEmail("late@nexusflow.local")

            assertEquals(
                AuthState.Unauthenticated(
                    AuthLoginUiState(devLoginEmail = "custom@nexusflow.local", isDevLoginSubmitting = true),
                ),
                controller.state.value,
            )
            submit.cancelAndJoin()
        }

    @Test
    fun cancellationInRefreshResultIsRethrown() =
        runBlocking {
            val store = MemorySecureStore()
            AuthSessionStore(store).write(
                validSession.copy(accessTokenExpiresAtMillis = 999),
            )
            val controller = controller(store, repository = CancellingRefreshRepository)

            assertFailsWith<CancellationException> { controller.restore() }
            Unit
        }

    @Test
    fun unauthenticatedRefreshClearsStoredSession() =
        runBlocking {
            val store = expiredAccessTokenStore()
            val controller = controller(store, repository = UnauthenticatedRefreshRepository)

            controller.restore()

            assertEquals(AuthState.Unauthenticated(), controller.state.value)
            assertEquals(null, AuthSessionStore(store).read())
        }

    @Test
    fun unavailableRefreshKeepsStoredSession() =
        runBlocking {
            val store = expiredAccessTokenStore()
            val controller = controller(store, repository = UnavailableRefreshRepository)

            controller.restore()

            assertEquals(AuthState.Unavailable, controller.state.value)
            assertEquals(validSession.copy(accessTokenExpiresAtMillis = 999), AuthSessionStore(store).read())
        }

    @Test
    fun protectedApiRefreshWritesNewSessionWhenTokenStillMatches() =
        runBlocking {
            val store = MemorySecureStore()
            AuthSessionStore(store).write(validSession)
            val refreshed = validSession.copy(accessToken = "new-access-token")
            val repository = RecordingRefreshRepository(Result.success(refreshed))
            val controller = controller(store, repository = repository)

            val result = controller.refreshAccessTokenIfCurrent("access-token")

            assertEquals(FirstPartySessionRefresh.TokenAvailable("new-access-token"), result)
            assertEquals(listOf("refresh-token"), repository.refreshRequests)
            assertEquals(refreshed, AuthSessionStore(store).read())
            assertEquals(AuthState.Authenticated(refreshed.context), controller.state.value)
        }

    @Test
    fun protectedApiRefreshReusesNewerSessionWhenRequestTokenIsStale() =
        runBlocking {
            val store = MemorySecureStore()
            AuthSessionStore(store).write(validSession.copy(accessToken = "newer-access-token"))
            val repository = RecordingRefreshRepository(Result.failure(IllegalStateException("refresh should not be called")))
            val controller = controller(store, repository = repository)

            val result = controller.refreshAccessTokenIfCurrent("old-access-token")

            assertEquals(FirstPartySessionRefresh.TokenAvailable("newer-access-token"), result)
            assertEquals(emptyList(), repository.refreshRequests)
            assertEquals(validSession.copy(accessToken = "newer-access-token"), AuthSessionStore(store).read())
        }

    @Test
    fun protectedApiReplayClearOnlyClearsMatchingCurrentSession() =
        runBlocking {
            val store = MemorySecureStore()
            AuthSessionStore(store).write(validSession.copy(accessToken = "newer-access-token"))
            val controller = controller(store)

            assertEquals(false, controller.clearSessionIfCurrent("old-access-token"))
            assertEquals(validSession.copy(accessToken = "newer-access-token"), AuthSessionStore(store).read())

            assertEquals(true, controller.clearSessionIfCurrent("newer-access-token"))
            assertEquals(null, AuthSessionStore(store).read())
            assertEquals(AuthState.Unauthenticated(), controller.state.value)
        }

    private suspend fun expiredAccessTokenStore(): MemorySecureStore =
        MemorySecureStore().also { store ->
            AuthSessionStore(store).write(validSession.copy(accessTokenExpiresAtMillis = 999))
        }

    private fun controller(
        store: SecureStore,
        repository: AuthRepository = FailingRepository,
        diagnosticReporter: AuthDiagnosticReporter = RecordingDiagnosticReporter,
    ): AuthSessionController =
        AuthSessionController(
            repository = repository,
            sessionStore = AuthSessionStore(store),
            runtimeConfig = RuntimeConfig("https://api.example", "client-id", BuildMode.DEBUG),
            clock = FixedClock,
            diagnosticReporter = diagnosticReporter,
        )

    private object FixedClock : AppClock {
        override fun currentTimeMillis(): Long = 1_000
    }

    private object FailingRepository : AuthRepository {
        override suspend fun exchangeGoogleIdToken(idToken: String): Result<AuthSession> = error("Not used")

        override suspend fun devLogin(
            email: String,
            password: String,
        ): Result<AuthSession> = error("Not used")

        override suspend fun refresh(refreshToken: String): Result<AuthSession> = error("Not used")

        override suspend fun logout(refreshToken: String): Result<Unit> = error("Not used")
    }

    private object CancellingRefreshRepository : AuthRepository {
        override suspend fun exchangeGoogleIdToken(idToken: String): Result<AuthSession> = error("Not used")

        override suspend fun devLogin(
            email: String,
            password: String,
        ): Result<AuthSession> = error("Not used")

        override suspend fun refresh(refreshToken: String): Result<AuthSession> = Result.failure(CancellationException("cancelled"))

        override suspend fun logout(refreshToken: String): Result<Unit> = error("Not used")
    }

    private object UnauthenticatedRefreshRepository : AuthRepository {
        override suspend fun exchangeGoogleIdToken(idToken: String): Result<AuthSession> = error("Not used")

        override suspend fun devLogin(
            email: String,
            password: String,
        ): Result<AuthSession> = error("Not used")

        override suspend fun refresh(refreshToken: String): Result<AuthSession> = Result.failure(AuthException.Unauthenticated)

        override suspend fun logout(refreshToken: String): Result<Unit> = error("Not used")
    }

    private object UnavailableRefreshRepository : AuthRepository {
        override suspend fun exchangeGoogleIdToken(idToken: String): Result<AuthSession> = error("Not used")

        override suspend fun devLogin(
            email: String,
            password: String,
        ): Result<AuthSession> = error("Not used")

        override suspend fun refresh(refreshToken: String): Result<AuthSession> = Result.failure(AppException.Unavailable())

        override suspend fun logout(refreshToken: String): Result<Unit> = error("Not used")
    }

    private class RecordingRefreshRepository(
        private val refreshResult: Result<AuthSession>,
    ) : AuthRepository {
        val refreshRequests = mutableListOf<String>()

        override suspend fun exchangeGoogleIdToken(idToken: String): Result<AuthSession> = error("Not used")

        override suspend fun devLogin(
            email: String,
            password: String,
        ): Result<AuthSession> = error("Not used")

        override suspend fun refresh(refreshToken: String): Result<AuthSession> {
            refreshRequests += refreshToken
            return refreshResult
        }

        override suspend fun logout(refreshToken: String): Result<Unit> = error("Not used")
    }

    private class RecordingDevLoginRepository(
        private val result: Result<AuthSession>,
    ) : AuthRepository {
        val devLoginRequests = mutableListOf<Pair<String, String>>()

        override suspend fun exchangeGoogleIdToken(idToken: String): Result<AuthSession> = error("Not used")

        override suspend fun devLogin(
            email: String,
            password: String,
        ): Result<AuthSession> {
            devLoginRequests += email to password
            return result
        }

        override suspend fun refresh(refreshToken: String): Result<AuthSession> = error("Not used")

        override suspend fun logout(refreshToken: String): Result<Unit> = error("Not used")
    }

    private class SuspendedDevLoginRepository : AuthRepository {
        val started = CompletableDeferred<Unit>()
        private val result = CompletableDeferred<Result<AuthSession>>()

        override suspend fun exchangeGoogleIdToken(idToken: String): Result<AuthSession> = error("Not used")

        override suspend fun devLogin(
            email: String,
            password: String,
        ): Result<AuthSession> {
            started.complete(Unit)
            return result.await()
        }

        override suspend fun refresh(refreshToken: String): Result<AuthSession> = error("Not used")

        override suspend fun logout(refreshToken: String): Result<Unit> = error("Not used")
    }

    private object RecordingDiagnosticReporter : AuthDiagnosticReporter {
        override fun report(event: AuthDiagnosticEvent) = Unit
    }

    private object ThrowingDiagnosticReporter : AuthDiagnosticReporter {
        override fun report(event: AuthDiagnosticEvent) = error("diagnostic failed")
    }

    private class MemorySecureStore : SecureStore {
        private val values = mutableMapOf<String, String>()

        override fun namespace(name: String): SecureStoreNamespace = MemorySecureStoreNamespace(values, name)
    }

    private class MemorySecureStoreNamespace(
        private val values: MutableMap<String, String>,
        private val namespace: String,
    ) : SecureStoreNamespace {
        override suspend fun read(key: SecureKey): String? = values[physicalKey(key)]

        override suspend fun write(
            key: SecureKey,
            value: String,
        ) {
            values[physicalKey(key)] = value
        }

        override suspend fun remove(key: SecureKey) {
            values.remove(physicalKey(key))
        }

        override suspend fun clear() {
            val prefix = "$namespace."
            values.keys.filter { it.startsWith(prefix) }.forEach(values::remove)
        }

        private fun physicalKey(key: SecureKey): String = "$namespace.${key.name}"
    }

    private companion object {
        val validSession =
            AuthSession(
                accessToken = "access-token",
                accessTokenExpiresAtMillis = 2_000,
                refreshToken = "refresh-token",
                refreshTokenExpiresAtMillis = 3_000,
                context = AppContextSnapshot(userId = "user", tenantId = "tenant"),
            )
    }
}
