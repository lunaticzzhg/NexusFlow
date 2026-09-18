package com.nexusflow.backend.feature.task

import com.nexusflow.backend.core.config.BackendRuntimeConfig
import com.nexusflow.backend.core.config.ChinaOfficialCinemaRuntimeConfig
import com.nexusflow.backend.core.config.ExternalSourcesRuntimeConfig
import com.nexusflow.backend.core.config.FootballDataRuntimeConfig
import com.nexusflow.backend.core.config.LoggingRuntimeConfig
import com.nexusflow.backend.core.config.LogFormat
import com.nexusflow.backend.core.config.OpportunitySourceMode
import com.nexusflow.backend.core.config.ResponseRunRuntimeConfig
import com.nexusflow.backend.core.config.RuntimeEnvironment
import com.nexusflow.backend.core.config.TavilyRuntimeConfig
import com.nexusflow.backend.core.config.TicketmasterRuntimeConfig
import com.nexusflow.backend.core.config.TmdbRuntimeConfig
import com.nexusflow.backend.core.external.ExternalSourceHttpClient
import com.nexusflow.backend.feature.task.application.readtool.MovieDetailsKey
import com.nexusflow.backend.feature.task.application.readtool.MovieDiscoveryKey
import com.nexusflow.backend.feature.task.application.readtool.MovieShowtimesKey
import com.nexusflow.backend.feature.task.application.readtool.MusicEventsKey
import com.nexusflow.backend.feature.task.application.readtool.MusicMetadataKey
import com.nexusflow.backend.feature.task.application.readtool.OutdoorTrailsKey
import com.nexusflow.backend.feature.task.application.readtool.PlacesSearchKey
import com.nexusflow.backend.feature.task.application.readtool.RouteEstimateKey
import com.nexusflow.backend.feature.task.application.readtool.SportsEventsKey
import com.nexusflow.backend.feature.task.application.readtool.SportsFixturesKey
import com.nexusflow.backend.feature.task.application.readtool.WeatherForecastKey
import com.nexusflow.backend.feature.task.application.readtool.WebSearchKey
import com.nexusflow.backend.feature.task.domain.source.WebDiscoverySource
import com.nexusflow.backend.feature.task.domain.source.WebExtractedPage
import com.nexusflow.backend.feature.task.domain.source.WebSearchHit
import com.nexusflow.backend.feature.task.domain.source.WebSearchQuery
import com.nexusflow.observability.LogFields
import com.nexusflow.observability.LogLevel
import com.nexusflow.observability.StructuredLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TaskDependenciesTest {
    @Test
    fun `external source wiring keeps web search independent from movie discovery without TMDB`() {
        val catalog = readToolCatalogForExternalSources(
            config = runtimeConfig(OpportunitySourceMode.External),
            externalHttpClient = externalHttpClient(MockEngine { respond("""{}""") }),
            cacheStore = null,
            webDiscoverySource = StaticWebDiscoverySource,
            movieMetadataAcquirer = null,
            logger = null,
        )

        assertEquals(
            listOf(
                MusicEventsKey.value,
                MusicMetadataKey.value,
                OutdoorTrailsKey.value,
                PlacesSearchKey.value,
                RouteEstimateKey.value,
                SportsEventsKey.value,
                WeatherForecastKey.value,
                WebSearchKey.value,
            ),
            catalog.definitions().map { it.key.value },
        )
    }

    @Test
    fun `TMDB runtime config registers movie discovery and movie details`() {
        val baseConfig = runtimeConfig(OpportunitySourceMode.External)
        val catalog = readToolCatalogForExternalSources(
            config = baseConfig.copy(
                externalSources = baseConfig.externalSources.copy(
                    tmdb = TmdbRuntimeConfig(apiReadToken = "t"),
                ),
            ),
            externalHttpClient = externalHttpClient(MockEngine { respond("""{}""") }),
            cacheStore = null,
            webDiscoverySource = null,
            logger = null,
        )

        assertEquals(
            listOf(
                MovieDetailsKey.value,
                MovieDiscoveryKey.value,
                MusicEventsKey.value,
                MusicMetadataKey.value,
                OutdoorTrailsKey.value,
                PlacesSearchKey.value,
                RouteEstimateKey.value,
                SportsEventsKey.value,
                WeatherForecastKey.value,
            ),
            catalog.definitions().map { it.key.value },
        )
    }

    @Test
    fun `China official cinema config registers movie showtimes`() {
        val baseConfig = runtimeConfig(OpportunitySourceMode.External)
        val catalog = readToolCatalogForExternalSources(
            config = baseConfig.copy(
                externalSources = baseConfig.externalSources.copy(
                    chinaOfficialCinema = ChinaOfficialCinemaRuntimeConfig(
                        pageUrls = listOf("https://cinema.example/showtimes"),
                    ),
                ),
            ),
            externalHttpClient = externalHttpClient(MockEngine { respond("""{}""") }),
            cacheStore = null,
            webDiscoverySource = null,
            logger = null,
        )

        assertEquals(
            listOf(
                MovieShowtimesKey.value,
                MusicEventsKey.value,
                MusicMetadataKey.value,
                OutdoorTrailsKey.value,
                PlacesSearchKey.value,
                RouteEstimateKey.value,
                SportsEventsKey.value,
                WeatherForecastKey.value,
            ),
            catalog.definitions().map { it.key.value },
        )
    }

    @Test
    fun `FootballData runtime config registers sports fixtures`() {
        val baseConfig = runtimeConfig(OpportunitySourceMode.External)
        val catalog = readToolCatalogForExternalSources(
            config = baseConfig.copy(
                externalSources = baseConfig.externalSources.copy(
                    footballData = FootballDataRuntimeConfig(apiToken = "f"),
                ),
            ),
            externalHttpClient = externalHttpClient(MockEngine { respond("""{}""") }),
            cacheStore = null,
            webDiscoverySource = null,
            logger = null,
        )

        assertEquals(
            listOf(
                MusicEventsKey.value,
                MusicMetadataKey.value,
                OutdoorTrailsKey.value,
                PlacesSearchKey.value,
                RouteEstimateKey.value,
                SportsEventsKey.value,
                SportsFixturesKey.value,
                WeatherForecastKey.value,
            ),
            catalog.definitions().map { it.key.value },
        )
    }

    @Test
    fun `Ticketmaster runtime config registers music events and sports events`() {
        val baseConfig = runtimeConfig(OpportunitySourceMode.External)
        val catalog = readToolCatalogForExternalSources(
            config = baseConfig.copy(
                externalSources = baseConfig.externalSources.copy(
                    ticketmaster = TicketmasterRuntimeConfig(apiKey = "k"),
                ),
            ),
            externalHttpClient = externalHttpClient(MockEngine { respond("""{}""") }),
            cacheStore = null,
            webDiscoverySource = null,
            logger = null,
        )

        assertEquals(
            listOf(
                MusicEventsKey.value,
                MusicMetadataKey.value,
                OutdoorTrailsKey.value,
                PlacesSearchKey.value,
                RouteEstimateKey.value,
                SportsEventsKey.value,
                WeatherForecastKey.value,
            ),
            catalog.definitions().map { it.key.value },
        )
    }

    @Test
    fun `default TheSportsDB dev fallback registers sports events without sports fixtures`() {
        val catalog = readToolCatalogForExternalSources(
            config = runtimeConfig(OpportunitySourceMode.External),
            externalHttpClient = externalHttpClient(MockEngine { respond("""{}""") }),
            cacheStore = null,
            webDiscoverySource = null,
            logger = null,
        )

        assertEquals(
            listOf(
                MusicEventsKey.value,
                MusicMetadataKey.value,
                OutdoorTrailsKey.value,
                PlacesSearchKey.value,
                RouteEstimateKey.value,
                SportsEventsKey.value,
                WeatherForecastKey.value,
            ),
            catalog.definitions().map { it.key.value },
        )
    }

    @Test
    fun `read tool catalog initialization logs actual tool keys without config secrets`() {
        val logger = RecordingStructuredLogger()
        val baseConfig = runtimeConfig(OpportunitySourceMode.External)
        val config = baseConfig.copy(
            databasePassword = "p",
            jwtPrivateKeyPemBase64 = "j",
            externalSources = baseConfig.externalSources.copy(
                userAgent = "ua",
                tavily = TavilyRuntimeConfig(apiKey = "k"),
                tmdb = TmdbRuntimeConfig(apiReadToken = "t"),
            ),
        )

        readToolCatalogForExternalSources(
            config = config,
            externalHttpClient = externalHttpClient(MockEngine { respond("""{}""") }),
            cacheStore = null,
            webDiscoverySource = StaticWebDiscoverySource,
            logger = logger,
        )

        val entry = logger.entries.single()
        assertEquals(LogLevel.INFO, entry.level)
        assertEquals("read_tool", entry.component)
        assertEquals("read_tool_catalog_initialized", entry.event)
        assertEquals("10", entry.fields.values["tool_count"])
        assertEquals(
            listOf(
                MovieDetailsKey.value,
                MovieDiscoveryKey.value,
                MusicEventsKey.value,
                MusicMetadataKey.value,
                OutdoorTrailsKey.value,
                PlacesSearchKey.value,
                RouteEstimateKey.value,
                SportsEventsKey.value,
                WeatherForecastKey.value,
                WebSearchKey.value,
            )
                .joinToString(","),
            entry.fields.values["available_tool_keys"],
        )
        assertEquals(setOf("tool_count", "available_tool_keys"), entry.fields.values.keys)
    }

    @Test
    fun `read tool wiring without http client fails clearly when weather sources would be built`() {
        assertFailsWith<IllegalStateException> {
            readToolCatalogForExternalSources(
                config = runtimeConfig(OpportunitySourceMode.External),
                externalHttpClient = null,
                cacheStore = null,
                webDiscoverySource = null,
                movieMetadataAcquirer = null,
                logger = null,
            )
        }
    }

    private fun runtimeConfig(mode: OpportunitySourceMode): BackendRuntimeConfig =
        BackendRuntimeConfig(
            databaseUrl = "jdbc:postgresql://unused",
            databaseUser = "unused",
            databasePassword = "unused",
            jwtIssuer = "https://api.nexusflow.test",
            jwtAudience = "nexusflow-api",
            jwtKeyId = "test",
            jwtPrivateKeyPemBase64 = "private",
            jwtPublicKeyPemBase64 = "public",
            googleAllowedAudiences = setOf("google-client"),
            accessLifetime = Duration.ofMinutes(15),
            refreshLifetime = Duration.ofDays(30),
            ai = null,
            responseRun = ResponseRunRuntimeConfig(
                workerEnabled = false,
                pollInterval = Duration.ofSeconds(1),
                leaseDuration = Duration.ofSeconds(30),
                heartbeatInterval = Duration.ofSeconds(10),
                retryBackoff = Duration.ofSeconds(5),
                maxAttempts = 3,
                maxDuration = Duration.ofMinutes(30),
            ),
            externalSources = ExternalSourcesRuntimeConfig(
                mode = mode,
                requestTimeout = Duration.ofSeconds(8),
                userAgent = "NexusFlow/0.1",
                tavily = null,
            ),
            logging = LoggingRuntimeConfig(
                environment = RuntimeEnvironment.Local,
                level = LogLevel.INFO,
                format = LogFormat.Pretty,
                serviceName = "nexusflow-backend",
            ),
            devLoginEnabled = false,
            devLoginEmail = null,
            devLoginPassword = null,
        )

    private fun externalHttpClient(engine: MockEngine): ExternalSourceHttpClient =
        ExternalSourceHttpClient(
            HttpClient(engine) {
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            explicitNulls = false
                        },
                    )
                }
            },
        )

    private object StaticWebDiscoverySource : WebDiscoverySource {
        override suspend fun search(request: WebSearchQuery): List<WebSearchHit> = emptyList()

        override suspend fun extract(urls: List<String>): List<WebExtractedPage> = emptyList()
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
            entries += Entry(level, component, event, fields)
        }
    }

    private data class Entry(
        val level: LogLevel,
        val component: String,
        val event: String,
        val fields: LogFields,
    )
}
