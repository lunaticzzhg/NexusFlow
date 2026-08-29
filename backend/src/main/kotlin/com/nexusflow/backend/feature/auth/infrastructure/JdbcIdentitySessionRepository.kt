package com.nexusflow.backend.feature.auth.infrastructure

import com.nexusflow.backend.feature.auth.domain.AuthPrincipal
import com.nexusflow.backend.feature.auth.domain.IdentitySessionRepository
import com.nexusflow.backend.feature.auth.domain.StoredSession
import com.nexusflow.backend.feature.auth.domain.VerifiedExternalIdentity

import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcIdentitySessionRepository(
    private val dataSource: DataSource,
) : IdentitySessionRepository {
    override fun findOrCreatePrincipal(identity: VerifiedExternalIdentity, now: Instant): AuthPrincipal = inTransaction { connection ->
        connection.prepareStatement(
            """
            SELECT e.user_id, m.tenant_id
            FROM external_identities e
            JOIN tenant_memberships m ON m.user_id = e.user_id
            WHERE e.provider = ? AND e.provider_subject = ?
            ORDER BY m.created_at ASC LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, identity.provider.name)
            statement.setString(2, identity.subject)
            statement.executeQuery().use { result ->
                if (result.next()) return@inTransaction result.principal()
            }
        }
        val userId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        connection.prepareStatement("INSERT INTO users (id, created_at) VALUES (?, ?)").use { statement ->
            statement.setObject(1, userId)
            statement.setTimestamp(2, Timestamp.from(now))
            statement.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, ?)").use { statement ->
            statement.setObject(1, tenantId)
            statement.setString(2, "Personal")
            statement.setTimestamp(3, Timestamp.from(now))
            statement.executeUpdate()
        }
        connection.prepareStatement("INSERT INTO tenant_memberships (tenant_id, user_id, created_at) VALUES (?, ?, ?)").use { statement ->
            statement.setObject(1, tenantId)
            statement.setObject(2, userId)
            statement.setTimestamp(3, Timestamp.from(now))
            statement.executeUpdate()
        }
        connection.prepareStatement(
            "INSERT INTO external_identities (provider, provider_subject, user_id, created_at) VALUES (?, ?, ?, ?)",
        ).use { statement ->
            statement.setString(1, identity.provider.name)
            statement.setString(2, identity.subject)
            statement.setObject(3, userId)
            statement.setTimestamp(4, Timestamp.from(now))
            statement.executeUpdate()
        }
        AuthPrincipal(userId, tenantId)
    }

    override fun findSessionByRefreshTokenHash(refreshTokenHash: String): StoredSession? = dataSource.connection.use { connection ->
        connection.prepareStatement(
            "SELECT id, family_id, user_id, tenant_id, refresh_token_hash, expires_at, revoked_at FROM auth_sessions WHERE refresh_token_hash = ?",
        ).use { statement ->
            statement.setString(1, refreshTokenHash)
            statement.executeQuery().use { result -> if (result.next()) result.session() else null }
        }
    }

    override fun createSession(session: StoredSession, now: Instant) = inTransaction { connection ->
        connection.insertSession(session, now)
    }

    override fun rotateSession(current: StoredSession, replacement: StoredSession, now: Instant): Boolean =
        try {
            inTransaction { connection ->
                connection.insertSession(replacement, now)
                val updated = connection.prepareStatement(
                    "UPDATE auth_sessions SET revoked_at = ?, replaced_by_session_id = ? WHERE id = ? AND revoked_at IS NULL AND expires_at > ?",
                ).use { statement ->
                    statement.setTimestamp(1, Timestamp.from(now))
                    statement.setObject(2, replacement.id)
                    statement.setObject(3, current.id)
                    statement.setTimestamp(4, Timestamp.from(now))
                    statement.executeUpdate()
                }
                if (updated != 1) throw SessionRotationRejected()
                true
            }
        } catch (_: SessionRotationRejected) {
            false
        }

    override fun revokeFamily(familyId: UUID, now: Instant) {
        inTransaction { connection ->
            connection.prepareStatement("UPDATE auth_sessions SET revoked_at = ? WHERE family_id = ? AND revoked_at IS NULL").use { statement ->
                statement.setTimestamp(1, Timestamp.from(now))
                statement.setObject(2, familyId)
                statement.executeUpdate()
            }
        }
    }

    override fun revokeSession(refreshTokenHash: String, now: Instant) {
        inTransaction { connection ->
            connection.prepareStatement("UPDATE auth_sessions SET revoked_at = ? WHERE refresh_token_hash = ? AND revoked_at IS NULL").use { statement ->
                statement.setTimestamp(1, Timestamp.from(now))
                statement.setString(2, refreshTokenHash)
                statement.executeUpdate()
            }
        }
    }

    override fun isSessionActive(sessionId: UUID, now: Instant): Boolean = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT 1 FROM auth_sessions WHERE id = ? AND revoked_at IS NULL AND expires_at > ?").use { statement ->
            statement.setObject(1, sessionId)
            statement.setTimestamp(2, Timestamp.from(now))
            statement.executeQuery().use(ResultSet::next)
        }
    }

    private fun Connection.insertSession(session: StoredSession, now: Instant) {
        prepareStatement(
            """
            INSERT INTO auth_sessions (id, family_id, user_id, tenant_id, refresh_token_hash, expires_at, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, session.id)
            statement.setObject(2, session.familyId)
            statement.setObject(3, session.principal.userId)
            statement.setObject(4, session.principal.tenantId)
            statement.setString(5, session.refreshTokenHash)
            statement.setTimestamp(6, Timestamp.from(session.expiresAt))
            statement.setTimestamp(7, Timestamp.from(now))
            statement.executeUpdate()
        }
    }

    private fun <T> inTransaction(block: (Connection) -> T): T = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            block(connection).also { connection.commit() }
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        }
    }

    private fun ResultSet.principal(): AuthPrincipal = AuthPrincipal(
        userId = getObject("user_id", UUID::class.java),
        tenantId = getObject("tenant_id", UUID::class.java),
    )

    private fun ResultSet.session(): StoredSession = StoredSession(
        id = getObject("id", UUID::class.java),
        familyId = getObject("family_id", UUID::class.java),
        principal = AuthPrincipal(getObject("user_id", UUID::class.java), getObject("tenant_id", UUID::class.java)),
        refreshTokenHash = getString("refresh_token_hash"),
        expiresAt = getTimestamp("expires_at").toInstant(),
        revokedAt = getTimestamp("revoked_at")?.toInstant(),
    )
}

private class SessionRotationRejected : RuntimeException()
