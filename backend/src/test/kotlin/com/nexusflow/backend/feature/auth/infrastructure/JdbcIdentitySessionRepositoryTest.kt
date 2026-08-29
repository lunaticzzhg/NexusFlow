package com.nexusflow.backend.feature.auth.infrastructure

import com.nexusflow.backend.feature.auth.domain.AuthPrincipal
import com.nexusflow.backend.feature.auth.domain.ExternalIdentityProvider
import com.nexusflow.backend.feature.auth.domain.StoredSession
import com.nexusflow.backend.feature.auth.domain.VerifiedExternalIdentity
import com.nexusflow.backend.test.PostgresTestGate
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JdbcIdentitySessionRepositoryTest {
    private lateinit var dataSource: HikariDataSource
    private lateinit var repository: JdbcIdentitySessionRepository

    @BeforeTest
    fun setUp() {
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres().getJdbcUrl()
                    username = postgres().getUsername()
                    password = postgres().getPassword()
                    maximumPoolSize = 2
                },
            )
        Flyway.configure()
            .dataSource(dataSource)
            .cleanDisabled(false)
            .load()
            .clean()
        Flyway.configure()
            .dataSource(dataSource)
            .load()
            .migrate()
        repository = JdbcIdentitySessionRepository(dataSource)
    }

    @AfterTest
    fun tearDown() {
        if (::dataSource.isInitialized) {
            dataSource.close()
        }
    }

    @Test
    fun `dev local identity provider is accepted by migrated schema`() {
        val identity = VerifiedExternalIdentity(ExternalIdentityProvider.DEV_LOCAL, "dev@nexusflow.local")

        val first = repository.findOrCreatePrincipal(identity, now)
        val second = repository.findOrCreatePrincipal(identity, now)

        assertEquals(first, second)
        assertEquals(1, countIdentityRows(ExternalIdentityProvider.DEV_LOCAL.name, "dev@nexusflow.local"))
    }

    @Test
    fun `session rotation inserts replacement before current session references it`() {
        val principal = createPrincipal()
        val familyId = UUID.fromString("00000000-0000-0000-0000-000000000010")
        val current = storedSession(
            id = UUID.fromString("00000000-0000-0000-0000-000000000011"),
            familyId = familyId,
            principal = principal,
            refreshTokenHash = "current-token",
            expiresAt = later,
        )
        val replacement = storedSession(
            id = UUID.fromString("00000000-0000-0000-0000-000000000012"),
            familyId = familyId,
            principal = principal,
            refreshTokenHash = "replacement-token",
            expiresAt = later,
        )
        repository.createSession(current, now)

        val rotated = repository.rotateSession(current, replacement, now)

        assertEquals(true, rotated)
        assertEquals(replacement, repository.findSessionByRefreshTokenHash("replacement-token"))
        assertEquals(SessionRotationRow(revokedAt = now, replacedBySessionId = replacement.id), readRotationRow("current-token"))
    }

    @Test
    fun `rejected session rotation rolls back replacement insert`() {
        val principal = createPrincipal()
        val familyId = UUID.fromString("00000000-0000-0000-0000-000000000020")
        val current = storedSession(
            id = UUID.fromString("00000000-0000-0000-0000-000000000021"),
            familyId = familyId,
            principal = principal,
            refreshTokenHash = "expired-current-token",
            expiresAt = beforeNow,
        )
        val replacement = storedSession(
            id = UUID.fromString("00000000-0000-0000-0000-000000000022"),
            familyId = familyId,
            principal = principal,
            refreshTokenHash = "orphan-replacement-token",
            expiresAt = later,
        )
        repository.createSession(current, beforeNow)

        val rotated = repository.rotateSession(current, replacement, now)

        assertEquals(false, rotated)
        assertNull(repository.findSessionByRefreshTokenHash("orphan-replacement-token"))
        assertEquals(SessionRotationRow(revokedAt = null, replacedBySessionId = null), readRotationRow("expired-current-token"))
    }

    private fun createPrincipal(): AuthPrincipal =
        repository.findOrCreatePrincipal(
            VerifiedExternalIdentity(ExternalIdentityProvider.GOOGLE, UUID.randomUUID().toString()),
            now,
        )

    private fun storedSession(
        id: UUID,
        familyId: UUID,
        principal: AuthPrincipal,
        refreshTokenHash: String,
        expiresAt: Instant,
    ): StoredSession =
        StoredSession(
            id = id,
            familyId = familyId,
            principal = principal,
            refreshTokenHash = refreshTokenHash,
            expiresAt = expiresAt,
            revokedAt = null,
        )

    private fun readRotationRow(refreshTokenHash: String): SessionRotationRow =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT revoked_at, replaced_by_session_id FROM auth_sessions WHERE refresh_token_hash = ?",
            ).use { statement ->
                statement.setString(1, refreshTokenHash)
                statement.executeQuery().use { result ->
                    result.next()
                    SessionRotationRow(
                        revokedAt = result.getTimestamp("revoked_at")?.toInstant(),
                        replacedBySessionId = result.getObject("replaced_by_session_id", UUID::class.java),
                    )
                }
            }
        }

    private fun countIdentityRows(
        provider: String,
        subject: String,
    ): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM external_identities WHERE provider = ? AND provider_subject = ?",
            ).use { statement ->
                statement.setString(1, provider)
                statement.setString(2, subject)
                statement.executeQuery().use { result ->
                    result.next()
                    result.getInt(1)
                }
            }
        }

    private companion object {
        private var postgresContainer: PostgreSQLContainer? = null

        fun postgres(): PostgreSQLContainer =
            postgresContainer ?: try {
                PostgreSQLContainer("postgres:16-alpine").apply { start() }
                    .also { postgresContainer = it }
            } catch (error: IllegalStateException) {
                PostgresTestGate.unavailable("Auth repository", error)
            }

        val beforeNow: Instant = Instant.parse("2026-08-28T23:15:00Z")
        val now: Instant = Instant.parse("2026-08-29T00:15:00Z")
        val later: Instant = Instant.parse("2026-08-29T00:30:00Z")
    }
}

private data class SessionRotationRow(
    val revokedAt: Instant?,
    val replacedBySessionId: UUID?,
)
