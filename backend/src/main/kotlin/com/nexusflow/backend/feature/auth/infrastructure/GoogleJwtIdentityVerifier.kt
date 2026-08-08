package com.nexusflow.backend.feature.auth.infrastructure

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.interfaces.RSAKeyProvider
import com.nexusflow.backend.feature.auth.domain.ExternalIdentityProvider
import com.nexusflow.backend.feature.auth.domain.GoogleIdentityVerifier
import com.nexusflow.backend.feature.auth.domain.VerifiedExternalIdentity
import java.net.URL
import java.security.interfaces.RSAPublicKey
import java.time.Duration

class GoogleJwtIdentityVerifier(
    private val allowedAudiences: Set<String>,
    jwksUrl: URL = URL(GOOGLE_JWKS_URL),
) : GoogleIdentityVerifier {
    private val jwkProvider = JwkProviderBuilder(jwksUrl).cached(10, Duration.ofHours(1)).build()
    private val verifier = JWT.require(Algorithm.RSA256(object : RSAKeyProvider {
        override fun getPublicKeyById(keyId: String?): RSAPublicKey = jwkProvider.get(keyId).publicKey as RSAPublicKey

        override fun getPrivateKey(): Nothing? = null

        override fun getPrivateKeyId(): Nothing? = null
    }))
        .withIssuer(*GOOGLE_ISSUERS)
        .build()

    override fun verify(idToken: String): VerifiedExternalIdentity = try {
        val jwt = verifier.verify(idToken)
        if (jwt.audience.none(allowedAudiences::contains)) throw JWTVerificationException("Unexpected Google audience")
        val subject = jwt.subject?.takeIf(String::isNotBlank) ?: throw JWTVerificationException("Missing Google subject")
        VerifiedExternalIdentity(ExternalIdentityProvider.GOOGLE, subject)
    } catch (error: JWTVerificationException) {
        throw InvalidGoogleIdentityException()
    }

    private companion object {
        const val GOOGLE_JWKS_URL = "https://www.googleapis.com/oauth2/v3/certs"
        val GOOGLE_ISSUERS = arrayOf("https://accounts.google.com", "accounts.google.com")
    }
}

class InvalidGoogleIdentityException : RuntimeException()
