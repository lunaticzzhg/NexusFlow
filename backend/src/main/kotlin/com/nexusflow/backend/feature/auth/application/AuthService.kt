package com.nexusflow.backend.feature.auth.application

import com.nexusflow.backend.feature.auth.domain.AccessTokenIssuer
import com.nexusflow.backend.feature.auth.domain.AuthPrincipal
import com.nexusflow.backend.feature.auth.domain.ExternalIdentityProvider
import com.nexusflow.backend.feature.auth.domain.GoogleIdentityVerifier
import com.nexusflow.backend.feature.auth.domain.IdentitySessionRepository
import com.nexusflow.backend.feature.auth.domain.IssuedSession
import com.nexusflow.backend.feature.auth.domain.StoredSession
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID

class AuthService(
    private val googleIdentityVerifier: GoogleIdentityVerifier,
    private val repository: IdentitySessionRepository,
    private val accessTokenIssuer: AccessTokenIssuer,
    private val accessLifetime: Duration,
    private val refreshLifetime: Duration,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) {
    fun exchangeGoogle(idToken: String): IssuedSession {
        val identity = googleIdentityVerifier.verify(idToken)
        check(identity.provider == ExternalIdentityProvider.GOOGLE)
        val principal = repository.findOrCreatePrincipal(identity, clock.instant())
        return issueSession(principal, UUID.randomUUID())
    }

    fun refresh(refreshToken: String): IssuedSession {
        val now = clock.instant()
        val current = repository.findSessionByRefreshTokenHash(hash(refreshToken))
            ?: throw InvalidSessionException()
        if (current.revokedAt != null || current.expiresAt <= now) {
            repository.revokeFamily(current.familyId, now)
            throw InvalidSessionException()
        }
        val (replacement, replacementToken) = newStoredSession(current.principal, current.familyId, now)
        if (!repository.rotateSession(current, replacement, now)) {
            repository.revokeFamily(current.familyId, now)
            throw InvalidSessionException()
        }
        return issuedSession(replacement, replacementToken)
    }

    fun logout(refreshToken: String) {
        repository.revokeSession(hash(refreshToken), clock.instant())
    }

    private fun issueSession(principal: AuthPrincipal, familyId: UUID): IssuedSession {
        val now = clock.instant()
        val (session, refreshToken) = newStoredSession(principal, familyId, now)
        repository.createSession(session, now)
        return issuedSession(session, refreshToken)
    }

    private fun newStoredSession(principal: AuthPrincipal, familyId: UUID, now: Instant): Pair<StoredSession, String> {
        val refreshToken = randomToken()
        return StoredSession(
            id = UUID.randomUUID(),
            familyId = familyId,
            principal = principal,
            refreshTokenHash = hash(refreshToken),
            expiresAt = now.plus(refreshLifetime),
            revokedAt = null,
        ) to refreshToken
    }

    private fun issuedSession(session: StoredSession, refreshToken: String): IssuedSession = IssuedSession(
        accessToken = accessTokenIssuer.issue(session.id, session.principal),
        accessTokenExpiresInSeconds = accessLifetime.seconds,
        refreshToken = refreshToken,
        refreshTokenExpiresInSeconds = refreshLifetime.seconds,
        principal = session.principal,
    )

    private fun randomToken(): String = ByteArray(32).also(random::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}

class InvalidSessionException : RuntimeException()
