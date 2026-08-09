package com.nexusflow.app.feature.auth.data

import com.nexusflow.app.core.security.SecureKeys
import com.nexusflow.app.core.security.SecureStore
import com.nexusflow.app.feature.auth.domain.AppContextSnapshot
import com.nexusflow.app.feature.auth.domain.AuthSession
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AuthSessionStore(
    private val secureStore: SecureStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private val authStore = secureStore.namespace(AUTH_NAMESPACE)

    suspend fun read(): AuthSession? =
        authStore.read(SESSION_KEY)?.let { encoded ->
            json.decodeFromString<StoredAuthSession>(encoded).toDomain()
        }

    suspend fun write(session: AuthSession) {
        authStore.write(SESSION_KEY, json.encodeToString(StoredAuthSession.from(session)))
    }

    suspend fun clear() {
        authStore.clear()
    }

    private companion object {
        const val AUTH_NAMESPACE = "auth"
        val SESSION_KEY = SecureKeys.string("session_v1")
    }
}

@Serializable
private data class StoredAuthSession(
    val accessToken: String,
    val accessTokenExpiresAtMillis: Long,
    val refreshToken: String,
    val refreshTokenExpiresAtMillis: Long,
    val userId: String,
    val tenantId: String,
) {
    fun toDomain(): AuthSession =
        AuthSession(
            accessToken = accessToken,
            accessTokenExpiresAtMillis = accessTokenExpiresAtMillis,
            refreshToken = refreshToken,
            refreshTokenExpiresAtMillis = refreshTokenExpiresAtMillis,
            context = AppContextSnapshot(userId, tenantId),
        )

    companion object {
        fun from(session: AuthSession): StoredAuthSession =
            StoredAuthSession(
                accessToken = session.accessToken,
                accessTokenExpiresAtMillis = session.accessTokenExpiresAtMillis,
                refreshToken = session.refreshToken,
                refreshTokenExpiresAtMillis = session.refreshTokenExpiresAtMillis,
                userId = session.context.userId,
                tenantId = session.context.tenantId,
            )
    }
}
