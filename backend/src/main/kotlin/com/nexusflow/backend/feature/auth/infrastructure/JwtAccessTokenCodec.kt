package com.nexusflow.backend.feature.auth.infrastructure

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.nexusflow.backend.core.config.BackendRuntimeConfig
import com.nexusflow.backend.core.identity.ActorContext
import com.nexusflow.backend.feature.auth.domain.AccessTokenIssuer
import com.nexusflow.backend.feature.auth.domain.AccessTokenVerifier
import com.nexusflow.backend.feature.auth.domain.AuthPrincipal
import com.nexusflow.backend.feature.auth.domain.InvalidAccessTokenException
import com.nexusflow.backend.feature.auth.domain.VerifiedAccessToken
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.Date
import java.util.UUID
import kotlin.text.Charsets.UTF_8

class JwtAccessTokenCodec(
    private val issuer: String,
    private val audience: String,
    private val keyId: String,
    private val signingKey: RSAPrivateKey,
    verificationKey: RSAPublicKey,
    private val lifetime: Duration,
    private val clock: Clock = Clock.systemUTC(),
) : AccessTokenIssuer, AccessTokenVerifier {
    private val algorithm = Algorithm.RSA256(verificationKey, signingKey)
    private val verifier: JWTVerifier = JWT.require(Algorithm.RSA256(verificationKey, null))
        .withIssuer(issuer)
        .withAudience(audience)
        .build()

    override fun issue(sessionId: UUID, principal: AuthPrincipal): String {
        val issuedAt = clock.instant()
        return JWT.create()
            .withKeyId(keyId)
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(principal.userId.toString())
            .withClaim("tenant_id", principal.tenantId.toString())
            .withClaim("sid", sessionId.toString())
            .withArrayClaim("scope", arrayOf("orbit.tasks.read", "orbit.tasks.write"))
            .withIssuedAt(Date.from(issuedAt))
            .withExpiresAt(Date.from(issuedAt.plus(lifetime)))
            .sign(algorithm)
    }

    override fun verify(token: String): VerifiedAccessToken = try {
        val jwt = verifier.verify(token)
        val subject = jwt.subject?.let(UUID::fromString) ?: throw JWTVerificationException("Missing sub")
        val tenant = jwt.getClaim("tenant_id").asString()?.let(UUID::fromString)
            ?: throw JWTVerificationException("Missing tenant_id")
        val sessionId = jwt.getClaim("sid").asString()?.let(UUID::fromString)
            ?: throw JWTVerificationException("Missing sid")
        VerifiedAccessToken(
            sessionId = sessionId,
            actor = ActorContext(
                tenantId = tenant.toString(),
                userId = subject.toString(),
                scopes = jwt.getClaim("scope").asList(String::class.java).orEmpty().toSet(),
            ),
        )
    } catch (_: JWTVerificationException) {
        throw InvalidAccessTokenException()
    } catch (_: IllegalArgumentException) {
        throw InvalidAccessTokenException()
    }
}

fun BackendRuntimeConfig.accessTokenCodec(): JwtAccessTokenCodec = JwtAccessTokenCodec(
    issuer = jwtIssuer,
    audience = jwtAudience,
    keyId = jwtKeyId,
    signingKey = decodePrivateKey(jwtPrivateKeyPemBase64),
    verificationKey = decodePublicKey(jwtPublicKeyPemBase64),
    lifetime = accessLifetime,
)

private fun decodePrivateKey(value: String): RSAPrivateKey = KeyFactory.getInstance("RSA")
    .generatePrivate(PKCS8EncodedKeySpec(decodeKeyMaterial(value))) as RSAPrivateKey

private fun decodePublicKey(value: String): RSAPublicKey = KeyFactory.getInstance("RSA")
    .generatePublic(X509EncodedKeySpec(decodeKeyMaterial(value))) as RSAPublicKey

private fun decodeKeyMaterial(value: String): ByteArray {
    val decoded = Base64.getDecoder().decode(value)
    val pem = decoded.toString(UTF_8)
    if (!pem.startsWith("-----BEGIN ")) return decoded

    val der = pem
        .lineSequence()
        .filterNot { it.startsWith("-----") }
        .joinToString(separator = "")
    return Base64.getDecoder().decode(der)
}
