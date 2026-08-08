package com.nexusflow.backend.feature.auth.domain

import java.time.Instant
import java.util.UUID

enum class ExternalIdentityProvider {
    GOOGLE,
}

data class VerifiedExternalIdentity(
    val provider: ExternalIdentityProvider,
    val subject: String,
)

data class AuthPrincipal(
    val userId: UUID,
    val tenantId: UUID,
)

data class StoredSession(
    val id: UUID,
    val familyId: UUID,
    val principal: AuthPrincipal,
    val refreshTokenHash: String,
    val expiresAt: Instant,
    val revokedAt: Instant?,
)

data class IssuedSession(
    val accessToken: String,
    val accessTokenExpiresInSeconds: Long,
    val refreshToken: String,
    val refreshTokenExpiresInSeconds: Long,
    val principal: AuthPrincipal,
)
