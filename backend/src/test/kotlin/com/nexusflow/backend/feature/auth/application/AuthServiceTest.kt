package com.nexusflow.backend.feature.auth.application

import com.nexusflow.backend.feature.auth.domain.AccessTokenIssuer
import com.nexusflow.backend.feature.auth.domain.AuthPrincipal
import com.nexusflow.backend.feature.auth.domain.ExternalIdentityProvider
import com.nexusflow.backend.feature.auth.domain.GoogleIdentityVerifier
import com.nexusflow.backend.feature.auth.domain.IdentitySessionRepository
import com.nexusflow.backend.feature.auth.domain.InvalidAccessTokenException
import com.nexusflow.backend.feature.auth.domain.StoredSession
import com.nexusflow.backend.feature.auth.domain.VerifiedExternalIdentity
import com.nexusflow.backend.feature.auth.infrastructure.InvalidGoogleIdentityException
import com.nexusflow.backend.feature.auth.infrastructure.JwtAccessTokenCodec
import com.nexusflow.backend.feature.auth.infrastructure.accessTokenCodec
import com.nexusflow.backend.core.config.BackendRuntimeConfig

import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AuthServiceTest {
    @Test
    fun `refresh token rotates once and reuse revokes its whole session family`() {
        val repository = InMemoryIdentitySessionRepository()
        val service = authService(repository)
        val initial = service.exchangeGoogle("valid-google-token")
        val refreshed = service.refresh(initial.refreshToken)

        assertNotEquals(initial.refreshToken, refreshed.refreshToken)
        assertFailsWith<InvalidSessionException> { service.refresh(initial.refreshToken) }
        assertFailsWith<InvalidSessionException> { service.refresh(refreshed.refreshToken) }
    }

    @Test
    fun `invalid Google identity cannot create a business session`() {
        val repository = InMemoryIdentitySessionRepository()
        val service = authService(repository, verifier = GoogleIdentityVerifier { throw InvalidGoogleIdentityException() })

        assertFailsWith<InvalidGoogleIdentityException> { service.exchangeGoogle("forged") }
        assertTrue(repository.sessions.isEmpty())
    }

    @Test
    fun `dev login is disabled unless runtime credentials are enabled and configured`() {
        val repository = InMemoryIdentitySessionRepository()
        val service = authService(repository)

        assertFailsWith<DevLoginUnavailableException> { service.devLogin("dev@nexusflow.local", "devpass") }
        assertTrue(repository.sessions.isEmpty())
    }

    @Test
    fun `dev login rejects wrong credentials without creating a business session`() {
        val repository = InMemoryIdentitySessionRepository()
        val service = authService(repository, devLoginEnabled = true)

        assertFailsWith<InvalidDevLoginCredentialException> { service.devLogin("dev@nexusflow.local", "wrong-password") }
        assertTrue(repository.sessions.isEmpty())
    }

    @Test
    fun `dev login creates a backend issued session through local identity`() {
        val repository = InMemoryIdentitySessionRepository()
        val service = authService(repository, devLoginEnabled = true)

        val first = service.devLogin("DEV@nexusflow.local ", "devpass")
        val second = service.devLogin("dev@nexusflow.local", "devpass")

        assertEquals("access-token", first.accessToken)
        assertEquals(first.principal, second.principal)
        assertEquals(2, repository.sessions.size)
    }

    @Test
    fun `access token with wrong audience is rejected`() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val valid = JwtAccessTokenCodec(
            issuer = "https://api.nexusflow.test",
            audience = "nexusflow-api",
            keyId = "test",
            signingKey = keys.private as RSAPrivateKey,
            verificationKey = keys.public as RSAPublicKey,
            lifetime = Duration.ofMinutes(15),
        ).issue(UUID.randomUUID(), AuthPrincipal(UUID.randomUUID(), UUID.randomUUID()))
        val verifierWithDifferentAudience = JwtAccessTokenCodec(
            issuer = "https://api.nexusflow.test",
            audience = "other-api",
            keyId = "test",
            signingKey = keys.private as RSAPrivateKey,
            verificationKey = keys.public as RSAPublicKey,
            lifetime = Duration.ofMinutes(15),
        )

        assertFailsWith<InvalidAccessTokenException> { verifierWithDifferentAudience.verify(valid) }
    }

    @Test
    fun `runtime config accepts base64 encoded PEM signing keys`() {
        val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val codec = runtimeConfigWithPem(keys.private.encoded, keys.public.encoded).accessTokenCodec()

        val token = codec.issue(UUID.randomUUID(), AuthPrincipal(UUID.randomUUID(), UUID.randomUUID()))

        codec.verify(token)
    }

    private fun authService(
        repository: InMemoryIdentitySessionRepository,
        verifier: GoogleIdentityVerifier = GoogleIdentityVerifier {
            VerifiedExternalIdentity(ExternalIdentityProvider.GOOGLE, "google-subject")
        },
        accessTokenIssuer: AccessTokenIssuer = AccessTokenIssuer { _, _ -> "access-token" },
        devLoginEnabled: Boolean = false,
    ): AuthService {
        val accessLifetime = Duration.ofMinutes(15)
        return AuthService(
            googleIdentityVerifier = verifier,
            repository = repository,
            accessTokenIssuer = accessTokenIssuer,
            accessLifetime = accessLifetime,
            refreshLifetime = Duration.ofDays(30),
            devLoginEnabled = devLoginEnabled,
            devLoginEmail = "dev@nexusflow.local",
            devLoginPassword = "devpass",
            clock = Clock.fixed(Instant.parse("2026-08-07T00:00:00Z"), ZoneOffset.UTC),
            random = SecureRandom(),
        )
    }

    private fun runtimeConfigWithPem(
        privateKey: ByteArray,
        publicKey: ByteArray,
    ): BackendRuntimeConfig =
        BackendRuntimeConfig(
            databaseUrl = "jdbc:postgresql://unused",
            databaseUser = "unused",
            databasePassword = "unused",
            jwtIssuer = "https://api.nexusflow.test",
            jwtAudience = "nexusflow-api",
            jwtKeyId = "test",
            jwtPrivateKeyPemBase64 = pemBase64("PRIVATE KEY", privateKey),
            jwtPublicKeyPemBase64 = pemBase64("PUBLIC KEY", publicKey),
            googleAllowedAudiences = setOf("google-client"),
            accessLifetime = Duration.ofMinutes(15),
            refreshLifetime = Duration.ofDays(30),
            ai = null,
            devLoginEnabled = false,
            devLoginEmail = null,
            devLoginPassword = null,
        )

    private fun pemBase64(
        type: String,
        der: ByteArray,
    ): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        val pem = "-----BEGIN $type-----\n$body\n-----END $type-----\n"
        return Base64.getEncoder().encodeToString(pem.toByteArray())
    }
}

private class InMemoryIdentitySessionRepository : IdentitySessionRepository {
    val sessions = mutableMapOf<String, StoredSession>()
    private val principals = mutableMapOf<String, AuthPrincipal>()

    override fun findOrCreatePrincipal(identity: VerifiedExternalIdentity, now: Instant): AuthPrincipal = principals.getOrPut(
        "${identity.provider}:${identity.subject}",
    ) { AuthPrincipal(UUID.randomUUID(), UUID.randomUUID()) }

    override fun findSessionByRefreshTokenHash(refreshTokenHash: String): StoredSession? = sessions[refreshTokenHash]

    override fun createSession(session: StoredSession, now: Instant) {
        sessions[session.refreshTokenHash] = session
    }

    override fun rotateSession(current: StoredSession, replacement: StoredSession, now: Instant): Boolean {
        val stored = sessions[current.refreshTokenHash] ?: return false
        if (stored.revokedAt != null || stored.expiresAt <= now) return false
        sessions[current.refreshTokenHash] = stored.copy(revokedAt = now)
        sessions[replacement.refreshTokenHash] = replacement
        return true
    }

    override fun revokeFamily(familyId: UUID, now: Instant) {
        sessions.replaceAll { _, session -> if (session.familyId == familyId) session.copy(revokedAt = now) else session }
    }

    override fun revokeSession(refreshTokenHash: String, now: Instant) {
        sessions[refreshTokenHash]?.let { sessions[refreshTokenHash] = it.copy(revokedAt = now) }
    }

    override fun isSessionActive(sessionId: UUID, now: Instant): Boolean = sessions.values.any { session ->
        session.id == sessionId && session.revokedAt == null && session.expiresAt > now
    }
}
