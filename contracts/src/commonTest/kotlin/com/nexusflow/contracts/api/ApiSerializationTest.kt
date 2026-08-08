package com.nexusflow.contracts.api

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiSerializationTest {
    private val json = Json { encodeDefaults = false }

    @Test
    fun `auth session wire field names remain stable`() {
        val session = AuthSessionResponse(
            accessToken = "access-token",
            accessTokenExpiresInSeconds = 900,
            refreshToken = "refresh-token",
            refreshTokenExpiresInSeconds = 2_592_000,
            userId = "user-1",
            tenantId = "tenant-1",
        )

        val encoded = json.encodeToString(session)

        assertEquals(
            "{\"accessToken\":\"access-token\",\"accessTokenExpiresInSeconds\":900," +
                "\"refreshToken\":\"refresh-token\",\"refreshTokenExpiresInSeconds\":2592000," +
                "\"userId\":\"user-1\",\"tenantId\":\"tenant-1\"}",
            encoded,
        )
        assertEquals(session, json.decodeFromString<AuthSessionResponse>(encoded))
    }
}
