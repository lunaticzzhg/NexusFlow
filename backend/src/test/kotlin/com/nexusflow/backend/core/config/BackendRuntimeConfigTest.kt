package com.nexusflow.backend.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BackendRuntimeConfigTest {
    @Test
    fun `OpenAI settings are optional so local backend can start without provider credentials`() {
        val config = BackendRuntimeConfig.fromEnvironment(baseEnvironment())

        assertNull(config.openAiApiKey)
        assertNull(config.openAiModel)
        assertEquals(false, config.fixturePlanningEnabled)
        assertEquals(false, config.devLoginEnabled)
        assertNull(config.devLoginEmail)
        assertNull(config.devLoginPassword)
    }

    @Test
    fun `OpenAI settings are retained when configured`() {
        val config =
            BackendRuntimeConfig.fromEnvironment(
                baseEnvironment() +
                    mapOf(
                        "OPENAI_API_KEY" to "test-openai-key",
                        "OPENAI_MODEL" to "gpt-test",
                        "ORBIT_FIXTURE_PLANNING_ENABLED" to "true",
                        "ORBIT_DEV_LOGIN_ENABLED" to "true",
                        "ORBIT_DEV_LOGIN_EMAIL" to "dev@nexusflow.local",
                        "ORBIT_DEV_LOGIN_PASSWORD" to "devpass",
                    ),
            )

        assertEquals("test-openai-key", config.openAiApiKey)
        assertEquals("gpt-test", config.openAiModel)
        assertEquals(true, config.fixturePlanningEnabled)
        assertEquals(true, config.devLoginEnabled)
        assertEquals("dev@nexusflow.local", config.devLoginEmail)
        assertEquals("devpass", config.devLoginPassword)
    }

    private fun baseEnvironment(): Map<String, String> =
        mapOf(
            "DATABASE_URL" to "jdbc:postgresql://localhost:5432/nexusflow",
            "DATABASE_USER" to "nexusflow",
            "DATABASE_PASSWORD" to "nexusflow_dev",
            "AUTH_JWT_ISSUER" to "http://localhost:8080",
            "AUTH_JWT_AUDIENCE" to "nexusflow-api",
            "AUTH_JWT_KEY_ID" to "local-auth-key",
            "AUTH_JWT_PRIVATE_KEY_PEM_BASE64" to "private-key",
            "AUTH_JWT_PUBLIC_KEY_PEM_BASE64" to "public-key",
            "GOOGLE_ALLOWED_AUDIENCES" to "android-client,web-client",
            "AUTH_ACCESS_TTL_SECONDS" to "900",
            "AUTH_REFRESH_TTL_DAYS" to "30",
        )
}
