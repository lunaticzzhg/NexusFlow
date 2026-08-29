package com.nexusflow.app.core.network

internal interface FirstPartyApiSession {
    suspend fun currentAccessToken(): String?

    suspend fun refreshAccessTokenIfCurrent(accessToken: String): FirstPartySessionRefresh

    suspend fun clearSessionIfCurrent(accessToken: String): Boolean
}

sealed interface FirstPartySessionRefresh {
    data class TokenAvailable(
        val accessToken: String,
    ) : FirstPartySessionRefresh

    data object Unauthenticated : FirstPartySessionRefresh

    data object Unavailable : FirstPartySessionRefresh
}
