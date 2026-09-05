package com.nexusflow.backend.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class BackendRuntimeConfigTest {
    @Test
    fun `AI settings are optional so local backend can start without provider credentials`() {
        val config = BackendRuntimeConfig.fromEnvironment(baseEnvironment())

        assertNull(config.ai)
        assertEquals(RuntimeEnvironment.Local, config.logging.environment)
        assertEquals(com.nexusflow.observability.LogLevel.INFO, config.logging.level)
        assertEquals(LogFormat.Pretty, config.logging.format)
        assertEquals("nexusflow-backend", config.logging.serviceName)
        assertEquals(false, config.devLoginEnabled)
        assertNull(config.devLoginEmail)
        assertNull(config.devLoginPassword)
    }

    @Test
    fun `AI settings are retained when a known provider is configured`() {
        val config =
            BackendRuntimeConfig.fromEnvironment(
                baseEnvironment() +
                    mapOf(
                        "AI_PROVIDER" to "qwen",
                        "AI_API_KEY" to "test-ai-key",
                        "AI_BASE_URL" to "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "AI_MODEL" to "qwen3.8-max",
                        "AI_REQUEST_TIMEOUT_MS" to "45000",
                        "ORBIT_DEV_LOGIN_ENABLED" to "true",
                        "ORBIT_DEV_LOGIN_EMAIL" to "dev@nexusflow.local",
                        "ORBIT_DEV_LOGIN_PASSWORD" to "devpass",
                    ),
            )

        assertEquals(AiProvider.Qwen, config.ai?.provider)
        assertEquals("test-ai-key", config.ai?.apiKey)
        assertEquals("https://dashscope.aliyuncs.com/compatible-mode/v1", config.ai?.baseUrl)
        assertEquals("qwen3.8-max", config.ai?.model)
        assertEquals(45_000, config.ai?.requestTimeout?.toMillis())
        assertEquals(false, config.ai?.enableThinking)
        assertFalse(config.ai.toString().contains("test-ai-key"))
        assertEquals(true, config.devLoginEnabled)
        assertEquals("dev@nexusflow.local", config.devLoginEmail)
        assertEquals("devpass", config.devLoginPassword)
    }

    @Test
    fun `qwen defaults to longer timeout and disabled thinking`() {
        val config =
            BackendRuntimeConfig.fromEnvironment(
                baseEnvironment() +
                    mapOf(
                        "AI_PROVIDER" to "qwen",
                        "AI_API_KEY" to "test-ai-key",
                        "AI_BASE_URL" to "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "AI_MODEL" to "qwen3.8-flash",
                    ),
            )

        assertEquals(90_000, config.ai?.requestTimeout?.toMillis())
        assertEquals(false, config.ai?.enableThinking)
    }

    @Test
    fun `AI thinking can be explicitly enabled`() {
        val config =
            BackendRuntimeConfig.fromEnvironment(
                baseEnvironment() +
                    mapOf(
                        "AI_PROVIDER" to "qwen",
                        "AI_API_KEY" to "test-ai-key",
                        "AI_BASE_URL" to "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "AI_MODEL" to "qwen3.8-flash",
                        "AI_ENABLE_THINKING" to "true",
                    ),
            )

        assertEquals(true, config.ai?.enableThinking)
    }

    @Test
    fun `AI provider must be known`() {
        assertFailsWith<IllegalStateException> {
            BackendRuntimeConfig.fromEnvironment(
                baseEnvironment() +
                    mapOf(
                        "AI_PROVIDER" to "unknown",
                        "AI_API_KEY" to "test-ai-key",
                        "AI_BASE_URL" to "https://api.test",
                        "AI_MODEL" to "model",
                    ),
            )
        }
    }

    @Test
    fun `AI provider requires nonblank base url model and api key`() {
        assertFailsWith<IllegalStateException> {
            BackendRuntimeConfig.fromEnvironment(
                baseEnvironment() +
                    mapOf(
                        "AI_PROVIDER" to "openai",
                        "AI_API_KEY" to "test-ai-key",
                        "AI_MODEL" to "gpt-test",
                    ),
            )
        }
    }

    @Test
    fun `logging settings can be configured for production json output`() {
        val config =
            BackendRuntimeConfig.fromEnvironment(
                baseEnvironment() +
                    mapOf(
                        "APP_ENV" to "prod",
                        "LOG_LEVEL" to "warn",
                        "LOG_FORMAT" to "json",
                        "SERVICE_NAME" to "nexusflow-backend",
                    ),
            )

        assertEquals(RuntimeEnvironment.Prod, config.logging.environment)
        assertEquals(com.nexusflow.observability.LogLevel.WARN, config.logging.level)
        assertEquals(LogFormat.Json, config.logging.format)
        assertEquals("nexusflow-backend", config.logging.serviceName)
    }

    @Test
    fun `logging settings reject unknown values`() {
        assertFailsWith<IllegalStateException> {
            BackendRuntimeConfig.fromEnvironment(baseEnvironment() + mapOf("LOG_FORMAT" to "xml"))
        }
        assertFailsWith<IllegalStateException> {
            BackendRuntimeConfig.fromEnvironment(baseEnvironment() + mapOf("APP_ENV" to "qa"))
        }
    }

    @Test
    fun `AI request timeout must be positive`() {
        assertFailsWith<IllegalArgumentException> {
            BackendRuntimeConfig.fromEnvironment(
                baseEnvironment() +
                    mapOf(
                        "AI_PROVIDER" to "deepseek",
                        "AI_API_KEY" to "test-ai-key",
                        "AI_BASE_URL" to "https://api.deepseek.com",
                        "AI_MODEL" to "deepseek-v4-flash",
                        "AI_REQUEST_TIMEOUT_MS" to "0",
                ),
            )
        }
    }

    @Test
    fun `AI thinking setting must be boolean`() {
        assertFailsWith<IllegalStateException> {
            BackendRuntimeConfig.fromEnvironment(
                baseEnvironment() +
                    mapOf(
                        "AI_PROVIDER" to "qwen",
                        "AI_API_KEY" to "test-ai-key",
                        "AI_BASE_URL" to "https://dashscope.aliyuncs.com/compatible-mode/v1",
                        "AI_MODEL" to "qwen3.8-flash",
                        "AI_ENABLE_THINKING" to "maybe",
                    ),
            )
        }
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
