package com.nexusflow.backend.feature.auth.api

import com.nexusflow.backend.core.http.configureHttpPlatform
import com.nexusflow.backend.feature.auth.application.AuthService
import com.nexusflow.backend.feature.auth.domain.AccessTokenIssuer
import com.nexusflow.backend.feature.auth.domain.AuthPrincipal
import com.nexusflow.backend.feature.auth.domain.ExternalIdentityProvider
import com.nexusflow.backend.feature.auth.domain.GoogleIdentityVerifier
import com.nexusflow.backend.feature.auth.domain.IdentitySessionRepository
import com.nexusflow.backend.feature.auth.domain.StoredSession
import com.nexusflow.backend.feature.auth.domain.VerifiedExternalIdentity
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuthRoutesTest {
    @Test
    fun `dev login route exchanges configured credentials for a session response`() = testApplication {
        application {
            configureHttpPlatform()
            routing { authRoutes(authService(devLoginEnabled = true)) }
        }

        val response = client.post("/v1/auth/dev-login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"dev@nexusflow.local","password":"devpass"}""")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"accessToken\":\"access-token\""))
        assertTrue(body.contains("\"refreshToken\""))
    }

    @Test
    fun `dev login route maps wrong credentials to unauthorized`() = testApplication {
        application {
            configureHttpPlatform()
            routing { authRoutes(authService(devLoginEnabled = true)) }
        }

        val wrongCredential = client.post("/v1/auth/dev-login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"dev@nexusflow.local","password":"wrong-password"}""")
        }

        assertEquals(HttpStatusCode.Unauthorized, wrongCredential.status)
    }

    @Test
    fun `dev login route maps unavailable configuration to not found`() = testApplication {
        application {
            configureHttpPlatform()
            routing { authRoutes(authService(devLoginEnabled = false)) }
        }

        val unavailable = client.post("/v1/auth/dev-login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"dev@nexusflow.local","password":"devpass"}""")
        }

        assertEquals(HttpStatusCode.NotFound, unavailable.status)
    }

    private fun authService(devLoginEnabled: Boolean): AuthService =
        AuthService(
            googleIdentityVerifier = GoogleIdentityVerifier {
                VerifiedExternalIdentity(ExternalIdentityProvider.GOOGLE, "google-subject")
            },
            repository = InMemoryIdentitySessionRepository(),
            accessTokenIssuer = AccessTokenIssuer { _, _ -> "access-token" },
            accessLifetime = Duration.ofMinutes(15),
            refreshLifetime = Duration.ofDays(30),
            devLoginEnabled = devLoginEnabled,
            devLoginEmail = "dev@nexusflow.local",
            devLoginPassword = "devpass",
            clock = Clock.fixed(Instant.parse("2026-08-07T00:00:00Z"), ZoneOffset.UTC),
            random = SecureRandom(),
        )
}

private class InMemoryIdentitySessionRepository : IdentitySessionRepository {
    private val sessions = mutableMapOf<String, StoredSession>()
    private val principals = mutableMapOf<String, AuthPrincipal>()

    override fun findOrCreatePrincipal(identity: VerifiedExternalIdentity, now: Instant): AuthPrincipal = principals.getOrPut(
        "${identity.provider}:${identity.subject}",
    ) { AuthPrincipal(UUID.randomUUID(), UUID.randomUUID()) }

    override fun findSessionByRefreshTokenHash(refreshTokenHash: String): StoredSession? = sessions[refreshTokenHash]

    override fun createSession(session: StoredSession, now: Instant) {
        sessions[session.refreshTokenHash] = session
    }

    override fun rotateSession(current: StoredSession, replacement: StoredSession, now: Instant): Boolean = false

    override fun revokeFamily(familyId: UUID, now: Instant) = Unit

    override fun revokeSession(refreshTokenHash: String, now: Instant) = Unit

    override fun isSessionActive(sessionId: UUID, now: Instant): Boolean = true
}
