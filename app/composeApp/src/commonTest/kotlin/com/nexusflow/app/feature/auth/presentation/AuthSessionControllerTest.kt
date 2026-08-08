package com.nexusflow.app.feature.auth.presentation

import com.nexusflow.app.core.config.BuildMode
import com.nexusflow.app.core.config.RuntimeConfig
import com.nexusflow.app.core.security.SecureStore
import com.nexusflow.app.core.time.AppClock
import com.nexusflow.app.feature.auth.data.AuthSessionStore
import com.nexusflow.app.feature.auth.domain.AppContextSnapshot
import com.nexusflow.app.feature.auth.domain.AuthRepository
import com.nexusflow.app.feature.auth.domain.AuthSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

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

            assertEquals(AuthState.Unauthenticated, controller.state.value)
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

    private fun controller(store: SecureStore): AuthSessionController =
        AuthSessionController(
            repository = FailingRepository,
            sessionStore = AuthSessionStore(store),
            runtimeConfig = RuntimeConfig("https://api.example", "client-id", BuildMode.DEBUG),
            clock = FixedClock,
        )

    private object FixedClock : AppClock {
        override fun currentTimeMillis(): Long = 1_000
    }

    private object FailingRepository : AuthRepository {
        override suspend fun exchangeGoogleIdToken(idToken: String): Result<AuthSession> = error("Not used")

        override suspend fun refresh(refreshToken: String): Result<AuthSession> = error("Not used")

        override suspend fun logout(refreshToken: String): Result<Unit> = error("Not used")
    }

    private class MemorySecureStore : SecureStore {
        private val values = mutableMapOf<String, String>()

        override fun read(key: String): String? = values[key]

        override fun write(
            key: String,
            value: String,
        ) {
            values[key] = value
        }

        override fun remove(key: String) {
            values.remove(key)
        }
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
