package com.nexusflow.app.feature.auth.data

import com.nexusflow.app.core.security.SecureStore
import com.nexusflow.app.feature.auth.domain.AppContextSnapshot
import com.nexusflow.app.feature.auth.domain.AuthSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AuthSessionStore(
    private val secureStore: SecureStore,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    suspend fun read(): AuthSession? =
        withContext(Dispatchers.Default) {
            secureStore.read(SESSION_KEY)?.let { encoded ->
                json.decodeFromString<StoredAuthSession>(encoded).toDomain()
            }
        }

    suspend fun write(session: AuthSession) {
        withContext(Dispatchers.Default) {
            secureStore.write(SESSION_KEY, json.encodeToString(StoredAuthSession.from(session)))
        }
    }

    suspend fun clear() {
        withContext(Dispatchers.Default) {
            secureStore.remove(SESSION_KEY)
        }
    }

    private companion object {
        const val SESSION_KEY = "feature.auth.session.v1"
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
