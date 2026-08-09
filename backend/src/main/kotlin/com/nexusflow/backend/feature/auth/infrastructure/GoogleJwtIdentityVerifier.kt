package com.nexusflow.backend.feature.auth.infrastructure

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.AlgorithmMismatchException
import com.auth0.jwt.exceptions.IncorrectClaimException
import com.auth0.jwt.exceptions.InvalidClaimException
import com.auth0.jwt.exceptions.JWTDecodeException
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.exceptions.SignatureVerificationException
import com.auth0.jwt.exceptions.TokenExpiredException
import com.auth0.jwt.interfaces.DecodedJWT
import com.auth0.jwt.interfaces.RSAKeyProvider
import com.nexusflow.backend.feature.auth.domain.ExternalIdentityProvider
import com.nexusflow.backend.feature.auth.domain.GoogleIdentityVerifier
import com.nexusflow.backend.feature.auth.domain.VerifiedExternalIdentity
import java.net.URL
import java.security.interfaces.RSAPublicKey
import java.time.Clock
import java.time.Duration
import java.time.Instant
import org.slf4j.Logger

class GoogleJwtIdentityVerifier(
    private val allowedAudiences: Set<String>,
    jwksUrl: URL = URL(GOOGLE_JWKS_URL),
    private val logger: Logger,
    private val clock: Clock = Clock.systemUTC(),
) : GoogleIdentityVerifier {
    private val jwkProvider = JwkProviderBuilder(jwksUrl).cached(10, Duration.ofHours(1)).build()
    private val verifier = JWT.require(Algorithm.RSA256(object : RSAKeyProvider {
        override fun getPublicKeyById(keyId: String?): RSAPublicKey = jwkProvider.get(keyId).publicKey as RSAPublicKey

        override fun getPrivateKey(): Nothing? = null

        override fun getPrivateKeyId(): Nothing? = null
    }))
        .withIssuer(*GOOGLE_ISSUERS)
        .build()

    override fun verify(idToken: String): VerifiedExternalIdentity {
        val metadata = GoogleJwtMetadata.from(idToken, allowedAudiences, clock.instant())
        return try {
            val jwt = verifier.verify(idToken)
            if (jwt.audience.none(allowedAudiences::contains)) {
                throw InvalidGoogleIdentityException(GoogleIdentityVerificationFailure.UNEXPECTED_AUDIENCE, metadata)
            }
            val subject = jwt.subject?.takeIf(String::isNotBlank)
                ?: throw InvalidGoogleIdentityException(GoogleIdentityVerificationFailure.MISSING_SUBJECT, metadata)
            VerifiedExternalIdentity(ExternalIdentityProvider.GOOGLE, subject)
        } catch (error: InvalidGoogleIdentityException) {
            logVerificationFailure(error)
            throw error
        } catch (error: JWTVerificationException) {
            val invalidIdentity = InvalidGoogleIdentityException(error.toFailureCategory(), metadata)
            logVerificationFailure(invalidIdentity)
            throw invalidIdentity
        } catch (_: Exception) {
            val invalidIdentity = InvalidGoogleIdentityException(GoogleIdentityVerificationFailure.KEY_RESOLUTION_FAILED, metadata)
            logVerificationFailure(invalidIdentity)
            throw invalidIdentity
        }
    }

    private fun logVerificationFailure(error: InvalidGoogleIdentityException) {
        val metadata = error.metadata
        logger.error(
            "Google ID token verification failed " +
                "[category=${error.failureCategory}, ${metadata.logFields()}]",
        )
    }

    private companion object {
        const val GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs"
    }
}

class InvalidGoogleIdentityException(
    val failureCategory: GoogleIdentityVerificationFailure = GoogleIdentityVerificationFailure.VERIFICATION_FAILED,
    val metadata: GoogleJwtMetadata = GoogleJwtMetadata.unavailable(),
) : RuntimeException()

enum class GoogleIdentityVerificationFailure {
    MALFORMED_TOKEN,
    ALGORITHM_MISMATCH,
    INVALID_ISSUER,
    EXPIRED_TOKEN,
    INVALID_SIGNATURE,
    INVALID_CLAIMS,
    KEY_RESOLUTION_FAILED,
    UNEXPECTED_AUDIENCE,
    MISSING_SUBJECT,
    VERIFICATION_FAILED,
}

data class GoogleJwtMetadata(
    val algorithm: String,
    val keyId: String,
    val issuerPresent: Boolean,
    val issuerAllowed: Boolean,
    val audiencePresent: Boolean,
    val audienceAllowed: Boolean,
    val issuedAtStatus: JwtTimeClaimStatus,
    val expiresAtStatus: JwtTimeClaimStatus,
) {
    internal fun logFields(): String =
        "algorithm=$algorithm, keyId=$keyId, issuerPresent=$issuerPresent, issuerAllowed=$issuerAllowed, " +
            "audiencePresent=$audiencePresent, audienceAllowed=$audienceAllowed, " +
            "issuedAt=$issuedAtStatus, expiresAt=$expiresAtStatus"

    companion object {
        fun from(idToken: String, allowedAudiences: Set<String>, now: Instant): GoogleJwtMetadata = try {
            JWT.decode(idToken).toMetadata(allowedAudiences, now)
        } catch (_: JWTDecodeException) {
            unavailable()
        }

        fun unavailable(): GoogleJwtMetadata = GoogleJwtMetadata(
            algorithm = "unavailable",
            keyId = "unavailable",
            issuerPresent = false,
            issuerAllowed = false,
            audiencePresent = false,
            audienceAllowed = false,
            issuedAtStatus = JwtTimeClaimStatus.UNAVAILABLE,
            expiresAtStatus = JwtTimeClaimStatus.UNAVAILABLE,
        )
    }
}

enum class JwtTimeClaimStatus {
    UNAVAILABLE,
    MISSING,
    PAST_OR_PRESENT,
    FUTURE,
}

private fun DecodedJWT.toMetadata(allowedAudiences: Set<String>, now: Instant): GoogleJwtMetadata = GoogleJwtMetadata(
    algorithm = algorithm.safeHeaderValue(),
    keyId = keyId.safeHeaderValue(),
    issuerPresent = issuer != null,
    issuerAllowed = issuer in GOOGLE_ISSUERS,
    audiencePresent = audience.isNotEmpty(),
    audienceAllowed = audience.any(allowedAudiences::contains),
    issuedAtStatus = issuedAt.toTimeClaimStatus(now),
    expiresAtStatus = expiresAt.toTimeClaimStatus(now),
)

private fun String?.safeHeaderValue(): String =
    takeIf { it != null && it.matches(SAFE_HEADER_VALUE) } ?: "unavailable"

private fun java.util.Date?.toTimeClaimStatus(now: Instant): JwtTimeClaimStatus = when {
    this == null -> JwtTimeClaimStatus.MISSING
    toInstant() > now -> JwtTimeClaimStatus.FUTURE
    else -> JwtTimeClaimStatus.PAST_OR_PRESENT
}

private fun JWTVerificationException.toFailureCategory(): GoogleIdentityVerificationFailure = when (this) {
    is JWTDecodeException -> GoogleIdentityVerificationFailure.MALFORMED_TOKEN
    is AlgorithmMismatchException -> GoogleIdentityVerificationFailure.ALGORITHM_MISMATCH
    is TokenExpiredException -> GoogleIdentityVerificationFailure.EXPIRED_TOKEN
    is SignatureVerificationException -> GoogleIdentityVerificationFailure.INVALID_SIGNATURE
    is IncorrectClaimException -> GoogleIdentityVerificationFailure.INVALID_ISSUER
    is InvalidClaimException -> GoogleIdentityVerificationFailure.INVALID_CLAIMS
    else -> GoogleIdentityVerificationFailure.VERIFICATION_FAILED
}

private val SAFE_HEADER_VALUE = Regex("[A-Za-z0-9_-]{1,128}")
private val GOOGLE_ISSUERS = arrayOf("https://accounts.google.com", "accounts.google.com")
