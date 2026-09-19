package com.nexusflow.backend.feature.research.infrastructure.source.football

import com.nexusflow.backend.core.config.ApiFootballRuntimeConfig
import com.nexusflow.backend.core.config.FootballDataRuntimeConfig
import com.nexusflow.backend.core.external.ExternalSourceHttpClient
import com.nexusflow.backend.core.external.ExternalSourceInvalidPayloadException
import com.nexusflow.backend.core.external.ExternalSourceRateLimitedException
import com.nexusflow.backend.core.external.ExternalSourceUnauthorizedException
import com.nexusflow.backend.core.external.ExternalSourceUnavailableException
import com.nexusflow.backend.core.external.SourceCacheKey
import com.nexusflow.backend.core.external.SourceCacheStore
import com.nexusflow.backend.feature.task.domain.SourceAuthority
import com.nexusflow.backend.feature.task.domain.source.FixtureStatus
import com.nexusflow.backend.feature.task.domain.source.FootballFixtureQuery
import com.nexusflow.backend.feature.research.infrastructure.source.apifootball.ApiFootballFixtureSource
import com.nexusflow.backend.feature.research.infrastructure.source.footballdata.FootballDataFixtureSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FootballFixtureSourceTest {
    private val now = Instant.parse("2026-09-06T00:00:00Z")
    private val query = FootballFixtureQuery(
        teamName = "Liverpool",
        dateFrom = LocalDate.parse("2026-09-12"),
        dateTo = LocalDate.parse("2026-09-13"),
    )

    @Test
    fun `API-Football projects valid 200 fixture payload`() = runBlocking {
        val source = apiFootballSource(
            engine = MockEngine { request ->
                assertEquals("af-k", request.headers["x-apisports-key"])
                assertEquals("/fixtures", request.url.encodedPath)
                assertEquals("2026-09-12", request.url.parameters["from"])
                respondJson(apiFootballFixturePayload(rawMarker = "RAW_API_FOOTBALL"))
            },
        )

        val candidates = source.search(query)

        assertEquals("1001", candidates.single().externalFixtureId)
        assertEquals("Liverpool", candidates.single().homeTeam)
        assertEquals("Arsenal", candidates.single().awayTeam)
        assertEquals("Anfield", candidates.single().venueName)
        assertEquals(FixtureStatus.Scheduled, candidates.single().status)
        assertEquals(SourceAuthority.StructuredPrimary, candidates.single().sources.single().authority)
    }

    @Test
    fun `API-Football maps canonical Premier League slug to provider league id`() = runBlocking {
        val source = apiFootballSource(
            engine = MockEngine { request ->
                assertEquals("39", request.url.parameters["league"])
                assertEquals("2026", request.url.parameters["season"])
                respondJson(apiFootballFixturePayload(rawMarker = "RAW_API_FOOTBALL"))
            },
        )

        val candidates = source.search(query.copy(league = "premier_league"))

        assertEquals("Premier League", candidates.single().competition)
        Unit
    }

    @Test
    fun `API-Football separates empty unauthorized rate limited unavailable and invalid payloads`() = runBlocking {
        assertEquals(emptyList(), apiFootballSource(MockEngine { respondJson("""{"response":[]}""") }).search(query))
        assertIs<ExternalSourceUnauthorizedException>(apiFootballFailure(HttpStatusCode.Unauthorized))
        assertIs<ExternalSourceUnauthorizedException>(apiFootballFailure(HttpStatusCode.Forbidden))
        assertIs<ExternalSourceRateLimitedException>(apiFootballFailure(HttpStatusCode.TooManyRequests))
        assertIs<ExternalSourceUnavailableException>(apiFootballFailure(HttpStatusCode.ServiceUnavailable))

        val invalid = apiFootballSource(MockEngine { respondJson("""{"response":[{"fixture":{"id":1001,"date":"2026-09-12T12:00:00Z"}}]}""") })
        assertFailsWith<ExternalSourceInvalidPayloadException> {
            invalid.search(query)
        }
        Unit
    }

    @Test
    fun `API-Football typed cache excludes raw header-provider payload`() = runBlocking {
        val store = RecordingSourceCacheStore()
        val source = apiFootballSource(
            cacheStore = store,
            engine = MockEngine {
                respondJson(apiFootballFixturePayload(rawMarker = "RAW_HEADER_PROVIDER_SHOULD_NOT_CACHE"))
            },
        )

        source.search(query)

        assertTrue(store.keys.none { it.value.contains("af-k") })
        val cached = store.putValues.single().decodeToString()
        assertTrue(cached.contains("Liverpool"))
        assertFalse(cached.contains("RAW_HEADER_PROVIDER_SHOULD_NOT_CACHE"))
        assertFalse(cached.contains("raw_marker"))
    }

    @Test
    fun `football-data projects valid 200 fixture payload`() = runBlocking {
        val source = footballDataSource(
            engine = MockEngine { request ->
                assertEquals("fd-t", request.headers["X-Auth-Token"])
                assertEquals("/matches", request.url.encodedPath)
                assertEquals("2026-09-12", request.url.parameters["dateFrom"])
                respondJson(footballDataFixturePayload(rawMarker = "RAW_FOOTBALL_DATA"))
            },
        )

        val candidates = source.search(query)

        assertEquals("2002", candidates.single().externalFixtureId)
        assertEquals("Liverpool", candidates.single().homeTeam)
        assertEquals("Chelsea", candidates.single().awayTeam)
        assertEquals("Anfield", candidates.single().venueName)
        assertEquals(FixtureStatus.Scheduled, candidates.single().status)
        assertEquals(SourceAuthority.StructuredSecondary, candidates.single().sources.single().authority)
    }

    @Test
    fun `football-data maps canonical Premier League slug to provider competition code`() = runBlocking {
        val source = footballDataSource(
            engine = MockEngine { request ->
                assertEquals("PL", request.url.parameters["competitions"])
                respondJson(footballDataFixturePayload(rawMarker = "RAW_FOOTBALL_DATA"))
            },
        )

        val candidates = source.search(query.copy(league = "premier_league"))

        assertEquals("Premier League", candidates.single().competition)
        Unit
    }

    @Test
    fun `football-data separates empty unauthorized rate limited unavailable and invalid payloads`() = runBlocking {
        assertEquals(emptyList(), footballDataSource(MockEngine { respondJson("""{"matches":[]}""") }).search(query))
        assertIs<ExternalSourceUnauthorizedException>(footballDataFailure(HttpStatusCode.Unauthorized))
        assertIs<ExternalSourceUnauthorizedException>(footballDataFailure(HttpStatusCode.Forbidden))
        assertIs<ExternalSourceRateLimitedException>(footballDataFailure(HttpStatusCode.TooManyRequests))
        assertIs<ExternalSourceUnavailableException>(footballDataFailure(HttpStatusCode.InternalServerError))

        val invalid = footballDataSource(MockEngine { respondJson("""{"matches":[{"id":2002,"utcDate":"2026-09-12T12:00:00Z"}]}""") })
        assertFailsWith<ExternalSourceInvalidPayloadException> {
            invalid.search(query)
        }
        Unit
    }

    private suspend fun apiFootballFailure(status: HttpStatusCode): Throwable {
        val source = apiFootballSource(MockEngine { respondJson("""{"response":[]}""", status) })
        return assertFailsWith<Throwable> { source.search(query) }
    }

    private suspend fun footballDataFailure(status: HttpStatusCode): Throwable {
        val source = footballDataSource(MockEngine { respondJson("""{"matches":[]}""", status) })
        return assertFailsWith<Throwable> { source.search(query) }
    }

    private fun apiFootballFixturePayload(rawMarker: String): String =
        """
        {
          "response": [
            {
              "fixture": {
                "id": 1001,
                "date": "2026-09-12T12:00:00Z",
                "status": {"short": "NS"},
                "venue": {"name": "Anfield", "city": "Liverpool"}
              },
              "league": {"name": "Premier League"},
              "teams": {"home": {"name": "Liverpool"}, "away": {"name": "Arsenal"}},
              "raw_marker": "$rawMarker"
            }
          ]
        }
        """.trimIndent()

    private fun footballDataFixturePayload(rawMarker: String): String =
        """
        {
          "matches": [
            {
              "id": 2002,
              "utcDate": "2026-09-12T12:00:00Z",
              "status": "SCHEDULED",
              "competition": {"name": "Premier League"},
              "homeTeam": {"name": "Liverpool"},
              "awayTeam": {"name": "Chelsea"},
              "venue": "Anfield",
              "raw_marker": "$rawMarker"
            }
          ]
        }
        """.trimIndent()

    private fun apiFootballSource(
        engine: MockEngine,
        cacheStore: SourceCacheStore? = null,
    ): ApiFootballFixtureSource =
        ApiFootballFixtureSource(
            http = ExternalSourceHttpClient(testHttpClient(engine)),
            config = ApiFootballRuntimeConfig(apiKey = "af-k", baseUrl = "https://api-football.test"),
            cacheStore = cacheStore,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun footballDataSource(
        engine: MockEngine,
        cacheStore: SourceCacheStore? = null,
    ): FootballDataFixtureSource =
        FootballDataFixtureSource(
            http = ExternalSourceHttpClient(testHttpClient(engine)),
            config = FootballDataRuntimeConfig(apiToken = "fd-t", baseUrl = "https://football-data.test"),
            cacheStore = cacheStore,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun testHttpClient(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        explicitNulls = false
                    },
                )
            }
        }

    private fun MockRequestHandleScope.respondJson(
        content: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = content,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private class RecordingSourceCacheStore : SourceCacheStore {
        private val values = linkedMapOf<SourceCacheKey, ByteArray>()
        val keys = mutableListOf<SourceCacheKey>()
        val putValues = mutableListOf<ByteArray>()

        override suspend fun get(key: SourceCacheKey): ByteArray? = values[key]?.copyOf()

        override suspend fun put(
            key: SourceCacheKey,
            value: ByteArray,
            ttl: java.time.Duration,
        ) {
            values[key] = value.copyOf()
            keys += key
            putValues += value.copyOf()
        }

        override suspend fun remove(key: SourceCacheKey) {
            values.remove(key)
        }
    }
}
