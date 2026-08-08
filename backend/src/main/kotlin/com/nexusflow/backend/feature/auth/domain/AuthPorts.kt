package com.nexusflow.backend.feature.auth.domain

import com.nexusflow.backend.core.identity.ActorContext
import java.time.Instant
import java.util.UUID

fun interface GoogleIdentityVerifier {
    fun verify(idToken: String): VerifiedExternalIdentity
}

interface IdentitySessionRepository {
    fun findOrCreatePrincipal(identity: VerifiedExternalIdentity, now: Instant): AuthPrincipal

    fun findSessionByRefreshTokenHash(refreshTokenHash: String): StoredSession?

    fun createSession(session: StoredSession, now: Instant)

    /** Atomically marks the old session unusable and creates its replacement. */
    fun rotateSession(current: StoredSession, replacement: StoredSession, now: Instant): Boolean

    fun revokeFamily(familyId: UUID, now: Instant)

    fun revokeSession(refreshTokenHash: String, now: Instant)

    fun isSessionActive(sessionId: UUID, now: Instant): Boolean
}

fun interface AccessTokenIssuer {
    fun issue(sessionId: UUID, principal: AuthPrincipal): String
}

fun interface AccessTokenVerifier {
    fun verify(token: String): VerifiedAccessToken
}

data class VerifiedAccessToken(
    val sessionId: UUID,
    val actor: ActorContext,
)

class InvalidAccessTokenException : RuntimeException()
