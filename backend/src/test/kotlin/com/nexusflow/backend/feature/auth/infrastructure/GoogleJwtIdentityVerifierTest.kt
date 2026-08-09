package com.nexusflow.backend.feature.auth.infrastructure

import java.net.URL
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.slf4j.helpers.NOPLogger

class GoogleJwtIdentityVerifierTest {
    @Test
    fun `malformed token is categorized and logging excludes its content`() {
        val rawToken = "not-a-token.alice@example.com.secret"
        val verifier = GoogleJwtIdentityVerifier(
            allowedAudiences = setOf("google-client"),
            jwksUrl = URL("https://unused.test/certs"),
            logger = NOPLogger.NOP_LOGGER,
            clock = Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC),
        )

        val error = assertFailsWith<InvalidGoogleIdentityException> { verifier.verify(rawToken) }

        assertEquals(GoogleIdentityVerificationFailure.MALFORMED_TOKEN, error.failureCategory)
        val logFields = error.metadata.logFields()
        assertTrue(logFields.contains("algorithm=unavailable"))
        assertFalse(logFields.contains(rawToken))
        assertFalse(logFields.contains("alice@example.com"))
        assertFalse(logFields.contains("secret"))
    }
}
