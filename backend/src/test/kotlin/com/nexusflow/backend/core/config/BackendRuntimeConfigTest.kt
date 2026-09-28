package com.nexusflow.backend.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackendRuntimeConfigTest {
    @Test
    fun `AI settings are optional so local backend can start without provider credentials`() {
        val config = BackendRuntimeConfig.fromEnvironment(baseEnvironment())

        assertNull(config.ai)
        assertEquals(RuntimeEnvironment.Local, config.logging.environment)
        assertEquals(com.nexusflow.observability.LogLevel.INFO, config.logging.level)
        assertEquals(LogFormat.Pretty, config.logging.format)
        assertEquals("nexusflow-backend", config.logging.serviceName)
        assertEquals(OpportunitySourceMode.External, config.externalSources.mode)
        assertEquals(8_000, config.externalSources.requestTimeout.toMillis())
        assertEquals("NexusFlow/0.1", config.externalSources.userAgent)
        assertNull(config.externalSources.tavily)
        assertEquals("https://musicbrainz.org/ws/2", config.externalSources.musicBrainz.baseUrl)
        assertEquals("3", config.externalSources.theSportsDb.apiKey)
        assertNull(config.externalSources.openRouteService)
        assertEquals("https://overpass-api.de/api", config.externalSources.overpass.baseUrl)
        assertEquals("https://api.open-meteo.com", config.externalSources.openMeteo.baseUrl)
        assertNull(config.externalSources.chinaOfficialCinema)
        assertEquals(false, config.responseRun.workerEnabled)
        assertEquals(1_000, config.responseRun.pollInterval.toMillis())
        assertEquals(30_000, config.responseRun.leaseDuration.toMillis())
        assertEquals(10_000, config.responseRun.heartbeatInterval.toMillis())
        assertEquals(5_000, config.responseRun.retryBackoff.toMillis())
        assertEquals(3, config.responseRun.maxAttempts)
        assertEquals(2, config.responseRun.workerParallelism)
        assertEquals(30 * 60 * 1_000, config.responseRun.maxDuration.toMillis())
        assertEquals(false, config.devLoginEnabled)
        assertNull(config.devLoginEmail)
        assertNull(config.devLoginPassword)
    }

    @Test
    fun `response run worker settings are centralized and validated`() {
        val config = BackendRuntimeConfig.fromEnvironment(
            baseEnvironment() + mapOf(
                "RESPONSE_RUN_WORKER_ENABLED" to "true",
                "RESPONSE_RUN_POLL_INTERVAL_MS" to "250",
                "RESPONSE_RUN_LEASE_DURATION_MS" to "5000",
                "RESPONSE_RUN_HEARTBEAT_INTERVAL_MS" to "1000",
                "RESPONSE_RUN_RETRY_BACKOFF_MS" to "750",
                "RESPONSE_RUN_MAX_ATTEMPTS" to "4",
                "RESPONSE_RUN_WORKER_PARALLELISM" to "3",
                "RESPONSE_RUN_MAX_DURATION_MS" to "60000",
            ),
        )

        assertEquals(true, config.responseRun.workerEnabled)
        assertEquals(250, config.responseRun.pollInterval.toMillis())
        assertEquals(5_000, config.responseRun.leaseDuration.toMillis())
        assertEquals(1_000, config.responseRun.heartbeatInterval.toMillis())
        assertEquals(750, config.responseRun.retryBackoff.toMillis())
        assertEquals(4, config.responseRun.maxAttempts)
        assertEquals(3, config.responseRun.workerParallelism)
        assertEquals(60_000, config.responseRun.maxDuration.toMillis())

        assertFailsWith<IllegalArgumentException> {
            BackendRuntimeConfig.fromEnvironment(
                baseEnvironment() + mapOf(
                    "RESPONSE_RUN_LEASE_DURATION_MS" to "1000",
                    "RESPONSE_RUN_HEARTBEAT_INTERVAL_MS" to "1000",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BackendRuntimeConfig.fromEnvironment(baseEnvironment() + mapOf("RESPONSE_RUN_MAX_ATTEMPTS" to "0"))
        }
        assertFailsWith<IllegalArgumentException> {
            BackendRuntimeConfig.fromEnvironment(baseEnvironment() + mapOf("RESPONSE_RUN_WORKER_PARALLELISM" to "0"))
        }
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
        assertFailsWith<IllegalStateException> {
            BackendRuntimeConfig.fromEnvironment(baseEnvironment() + mapOf("LOG_LEVEL" to "verbose"))
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

    @Test
    fun `external source settings are retained and Tavily secret is redacted`() {
        val config =
            BackendRuntimeConfig.fromEnvironment(
                baseEnvironment() +
                    mapOf(
                        "ORBIT_OPPORTUNITY_SOURCE_MODE" to "external",
                        "EXTERNAL_SOURCE_REQUEST_TIMEOUT_MS" to "2500",
                        "EXTERNAL_SOURCE_USER_AGENT" to "NexusFlow Test/1.0",
                        "TAVILY_API_KEY" to "tv-k",
                        "TICKETMASTER_API_KEY" to "tm-k",
                        "THESPORTSDB_API_KEY" to "ts-k",
                        "ORS_API_KEY" to "ors-k",
                        "ORS_ROUTING_BASE_URL" to "https://ors-route.test",
                        "ORS_GEOCODE_BASE_URL" to "https://ors-geocode.test",
                        "OVERPASS_BASE_URL" to "https://overpass.test/api",
                        "TRAILSPLITS_BASE_URL" to "https://trailsplits.test",
                        "NOMINATIM_BASE_URL" to "https://nominatim.test",
                        "OPEN_METEO_BASE_URL" to "https://open-meteo.test",
                        "MET_NO_BASE_URL" to "https://met-no.test",
                        "MUSICBRAINZ_BASE_URL" to "https://musicbrainz.test/ws/2",
                        "THESPORTSDB_BASE_URL" to "https://thesportsdb.test",
                        "CHINA_OFFICIAL_CINEMA_PAGE_URLS" to "https://cinema-a.test/showtimes, https://cinema-b.test/shenzhen",
                    ),
            )

        assertEquals(OpportunitySourceMode.External, config.externalSources.mode)
        assertEquals(2_500, config.externalSources.requestTimeout.toMillis())
        assertEquals("NexusFlow Test/1.0", config.externalSources.userAgent)
        assertEquals("tv-k", config.externalSources.tavily?.apiKey)
        assertEquals("tm-k", config.externalSources.ticketmaster?.apiKey)
        assertEquals("ts-k", config.externalSources.theSportsDb.apiKey)
        assertEquals("ors-k", config.externalSources.openRouteService?.apiKey)
        assertEquals("https://ors-route.test", config.externalSources.openRouteService?.routingBaseUrl)
        assertEquals("https://ors-geocode.test", config.externalSources.openRouteService?.geocodeBaseUrl)
        assertEquals("https://overpass.test/api", config.externalSources.overpass.baseUrl)
        assertEquals("https://trailsplits.test", config.externalSources.trailSplits.baseUrl)
        assertEquals("https://nominatim.test", config.externalSources.nominatim.baseUrl)
        assertEquals("https://open-meteo.test", config.externalSources.openMeteo.baseUrl)
        assertEquals("https://met-no.test", config.externalSources.metNo.baseUrl)
        assertEquals("https://musicbrainz.test/ws/2", config.externalSources.musicBrainz.baseUrl)
        assertEquals("https://thesportsdb.test", config.externalSources.theSportsDb.baseUrl)
        assertEquals(
            listOf("https://cinema-a.test/showtimes", "https://cinema-b.test/shenzhen"),
            config.externalSources.chinaOfficialCinema?.pageUrls,
        )
        assertFalse(config.externalSources.tavily.toString().contains("tv-k"))
        assertFalse(config.externalSources.ticketmaster.toString().contains("tm-k"))
        assertFalse(config.externalSources.theSportsDb.toString().contains("ts-k"))
        assertFalse(config.externalSources.openRouteService.toString().contains("ors-k"))
    }

    @Test
    fun `external source mode and timeout are validated`() {
        assertFailsWith<IllegalStateException> {
            BackendRuntimeConfig.fromEnvironment(
                baseEnvironment() + mapOf("ORBIT_OPPORTUNITY_SOURCE_MODE" to "mock"),
            )
        }
        assertFailsWith<IllegalStateException> {
            BackendRuntimeConfig.fromEnvironment(
                baseEnvironment() + mapOf("ORBIT_OPPORTUNITY_SOURCE_MODE" to "controlled"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BackendRuntimeConfig.fromEnvironment(
                baseEnvironment() + mapOf("EXTERNAL_SOURCE_REQUEST_TIMEOUT_MS" to "0"),
            )
        }
    }

    @Test
    fun `present blank typed settings are rejected instead of silently using defaults`() {
        listOf(
            "ORBIT_DEV_LOGIN_ENABLED",
            "AI_REQUEST_TIMEOUT_MS",
            "AI_ENABLE_THINKING",
            "ORBIT_OPPORTUNITY_SOURCE_MODE",
            "EXTERNAL_SOURCE_REQUEST_TIMEOUT_MS",
            "RESPONSE_RUN_WORKER_ENABLED",
            "RESPONSE_RUN_POLL_INTERVAL_MS",
            "RESPONSE_RUN_LEASE_DURATION_MS",
            "RESPONSE_RUN_HEARTBEAT_INTERVAL_MS",
            "RESPONSE_RUN_RETRY_BACKOFF_MS",
            "RESPONSE_RUN_MAX_ATTEMPTS",
            "RESPONSE_RUN_WORKER_PARALLELISM",
            "RESPONSE_RUN_MAX_DURATION_MS",
            "APP_ENV",
            "LOG_LEVEL",
            "LOG_FORMAT",
        ).forEach { name ->
            val environment = if (name == "AI_ENABLE_THINKING" || name == "AI_REQUEST_TIMEOUT_MS") {
                baseAiEnvironment()
            } else {
                baseEnvironment()
            }
            assertFailsWith<IllegalArgumentException>(message = "$name should reject blank values") {
                BackendRuntimeConfig.fromEnvironment(environment + mapOf(name to ""))
            }
        }
    }

    @Test
    fun `numeric settings fail with keyed diagnostics`() {
        listOf(
            "AUTH_ACCESS_TTL_SECONDS",
            "AUTH_REFRESH_TTL_DAYS",
            "AI_REQUEST_TIMEOUT_MS",
            "EXTERNAL_SOURCE_REQUEST_TIMEOUT_MS",
            "RESPONSE_RUN_POLL_INTERVAL_MS",
            "RESPONSE_RUN_MAX_ATTEMPTS",
        ).forEach { name ->
            val environment = if (name == "AI_REQUEST_TIMEOUT_MS") {
                baseAiEnvironment()
            } else {
                baseEnvironment()
            }
            val failure = assertFailsWith<IllegalStateException>(message = "$name should name the invalid numeric key") {
                BackendRuntimeConfig.fromEnvironment(environment + mapOf(name to "not-a-number"))
            }

            assertTrue(failure.message?.contains(name) == true)
        }
    }

    @Test
    fun `present invalid typed booleans are rejected`() {
        assertFailsWith<IllegalStateException> {
            BackendRuntimeConfig.fromEnvironment(baseEnvironment() + mapOf("ORBIT_DEV_LOGIN_ENABLED" to "yes"))
        }
        assertFailsWith<IllegalStateException> {
            BackendRuntimeConfig.fromEnvironment(baseEnvironment() + mapOf("RESPONSE_RUN_WORKER_ENABLED" to "yes"))
        }
    }

    @Test
    fun `dev login credentials are required only when dev login is enabled`() {
        val disabled = BackendRuntimeConfig.fromEnvironment(
            baseEnvironment() + mapOf("ORBIT_DEV_LOGIN_ENABLED" to "false"),
        )

        assertEquals(false, disabled.devLoginEnabled)
        assertNull(disabled.devLoginEmail)
        assertNull(disabled.devLoginPassword)

        assertFailsWith<IllegalArgumentException> {
            BackendRuntimeConfig.fromEnvironment(
                baseEnvironment() + mapOf(
                    "ORBIT_DEV_LOGIN_ENABLED" to "true",
                    "ORBIT_DEV_LOGIN_EMAIL" to "dev@nexusflow.local",
                ),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            BackendRuntimeConfig.fromEnvironment(
                baseEnvironment() + mapOf(
                    "ORBIT_DEV_LOGIN_ENABLED" to "true",
                    "ORBIT_DEV_LOGIN_PASSWORD" to "devpass",
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

    private fun baseAiEnvironment(): Map<String, String> =
        baseEnvironment() +
            mapOf(
                "AI_PROVIDER" to "qwen",
                "AI_API_KEY" to "test-ai-key",
                "AI_BASE_URL" to "https://dashscope.aliyuncs.com/compatible-mode/v1",
                "AI_MODEL" to "qwen3.8-flash",
            )
}
