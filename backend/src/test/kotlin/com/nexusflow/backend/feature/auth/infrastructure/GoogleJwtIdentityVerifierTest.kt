package com.nexusflow.backend.feature.auth.infrastructure

import com.nexusflow.observability.LogFields
import com.nexusflow.observability.LogLevel
import com.nexusflow.observability.StructuredLogger
import java.net.URL
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoogleJwtIdentityVerifierTest {
    @Test
    fun `malformed token is categorized and logging excludes its content`() {
        val rawToken = "not-a-token.alice@example.com.secret"
        val logger = RecordingStructuredLogger()
        val verifier = GoogleJwtIdentityVerifier(
            allowedAudiences = setOf("google-client"),
            jwksUrl = URL("https://unused.test/certs"),
            logger = logger,
            clock = Clock.fixed(Instant.parse("2026-08-08T00:00:00Z"), ZoneOffset.UTC),
        )

        val error = assertFailsWith<InvalidGoogleIdentityException> { verifier.verify(rawToken) }

        assertEquals(GoogleIdentityVerificationFailure.MALFORMED_TOKEN, error.failureCategory)
        val logFields = error.metadata.logFields()
        assertTrue(logFields.contains("algorithm=unavailable"))
        assertFalse(logFields.contains(rawToken))
        assertFalse(logFields.contains("alice@example.com"))
        assertFalse(logFields.contains("secret"))

        val entry = logger.entries.single()
        assertEquals(LogLevel.ERROR, entry.level)
        assertEquals("auth", entry.component)
        assertEquals("google_id_token_verification_failed", entry.event)
        assertEquals("MALFORMED_TOKEN", entry.fields.values["failure_category"])
        val renderedFields = entry.fields.values.entries.joinToString("|") { (key, value) -> "$key=$value" }
        assertFalse(renderedFields.contains(rawToken))
        assertFalse(renderedFields.contains("alice@example.com"))
        assertFalse(renderedFields.contains("secret"))
    }

    private class RecordingStructuredLogger : StructuredLogger {
        val entries = mutableListOf<Entry>()

        override fun log(
            level: LogLevel,
            component: String,
            event: String,
            fields: LogFields,
            cause: Throwable?,
        ) {
            entries += Entry(level, component, event, fields, cause?.let { it::class.simpleName })
        }
    }

    private data class Entry(
        val level: LogLevel,
        val component: String,
        val event: String,
        val fields: LogFields,
        val errorType: String?,
    )
}
