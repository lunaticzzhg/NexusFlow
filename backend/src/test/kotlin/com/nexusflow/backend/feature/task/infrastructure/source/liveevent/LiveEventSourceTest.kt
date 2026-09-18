package com.nexusflow.backend.feature.task.infrastructure.source.liveevent

import com.nexusflow.backend.core.config.MusicBrainzRuntimeConfig
import com.nexusflow.backend.core.config.TheSportsDbRuntimeConfig
import com.nexusflow.backend.core.config.TicketmasterRuntimeConfig
import com.nexusflow.backend.core.external.ExternalSourceHttpClient
import com.nexusflow.backend.core.external.ExternalSourceInvalidPayloadException
import com.nexusflow.backend.core.external.ExternalSourceRateLimitedException
import com.nexusflow.backend.core.external.ExternalSourceUnauthorizedException
import com.nexusflow.backend.core.external.ExternalSourceUnavailableException
import com.nexusflow.backend.core.external.SourceCacheKey
import com.nexusflow.backend.core.external.SourceCacheStore
import com.nexusflow.backend.feature.task.domain.AvailabilityFact
import com.nexusflow.backend.feature.task.domain.SourceAuthority
import com.nexusflow.backend.feature.task.domain.source.GeneralSportsEventQuery
import com.nexusflow.backend.feature.task.domain.source.LiveMusicEventQuery
import com.nexusflow.backend.feature.task.infrastructure.source.musicbrainz.MusicBrainzLiveMusicEventSource
import com.nexusflow.backend.feature.task.infrastructure.source.thesportsdb.TheSportsDbEventSource
import com.nexusflow.backend.feature.task.infrastructure.source.ticketmaster.TicketmasterLiveMusicEventSource
import com.nexusflow.backend.feature.task.infrastructure.source.ticketmaster.TicketmasterSportsEventSource
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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LiveEventSourceTest {
    private val now = Instant.parse("2026-09-06T00:00:00Z")
    private val liveMusicQuery = LiveMusicEventQuery(
        keyword = "Radiohead",
        city = "London",
        dateFrom = LocalDate.parse("2026-10-01"),
        dateTo = LocalDate.parse("2026-10-31"),
    )
    private val sportsQuery = GeneralSportsEventQuery(
        keyword = "basketball",
        city = "London",
        dateFrom = LocalDate.parse("2026-10-01"),
        dateTo = LocalDate.parse("2026-10-31"),
    )

    @Test
    fun `Ticketmaster music projects valid 200 event payload`() = runBlocking {
        val source = ticketmasterMusicSource(
            engine = MockEngine { request ->
                assertEquals("/events.json", request.url.encodedPath)
                assertEquals("tm-k", request.url.parameters["apikey"])
                assertEquals("music", request.url.parameters["classificationName"])
                assertEquals("Radiohead", request.url.parameters["keyword"])
                respondJson(ticketmasterEventPayload(rawMarker = "RAW_TICKETMASTER_MUSIC"))
            },
        )

        val candidates = source.search(liveMusicQuery)

        assertEquals("tm-1001", candidates.single().externalEventId)
        assertEquals("Radiohead Live", candidates.single().title)
        assertEquals(setOf("Radiohead"), candidates.single().artists)
        assertEquals("O2 Arena", candidates.single().venueName)
        assertEquals(AvailabilityFact.Available, candidates.single().availability)
        assertEquals(SourceAuthority.StructuredPrimary, candidates.single().sources.single().authority)
    }

    @Test
    fun `Ticketmaster sports projects valid 200 event payload`() = runBlocking {
        val source = ticketmasterSportsSource(
            engine = MockEngine { request ->
                assertEquals("sports", request.url.parameters["classificationName"])
                assertEquals("tm-k", request.url.parameters["apikey"])
                respondJson(ticketmasterEventPayload(rawMarker = "RAW_TICKETMASTER_SPORTS", title = "London Lions vs Paris"))
            },
        )

        val candidates = source.search(sportsQuery)

        assertEquals("London Lions vs Paris", candidates.single().title)
        assertEquals("Basketball", candidates.single().sportName)
        assertEquals(SourceAuthority.StructuredPrimary, candidates.single().sources.single().authority)
    }

    @Test
    fun `Ticketmaster sports separates empty unauthorized rate limited unavailable and invalid payloads`() = runBlocking {
        assertEquals(emptyList(), ticketmasterSportsSource(MockEngine { respondJson("""{}""") }).search(sportsQuery))
        assertIs<ExternalSourceUnauthorizedException>(ticketmasterSportsFailure(HttpStatusCode.Unauthorized))
        assertIs<ExternalSourceUnauthorizedException>(ticketmasterSportsFailure(HttpStatusCode.Forbidden))
        assertIs<ExternalSourceRateLimitedException>(ticketmasterSportsFailure(HttpStatusCode.TooManyRequests))
        assertIs<ExternalSourceUnavailableException>(ticketmasterSportsFailure(HttpStatusCode.ServiceUnavailable))

        val invalid = ticketmasterSportsSource(
            MockEngine {
                respondJson("""{"_embedded":{"events":[{"id":"tm-1001","dates":{"start":{"dateTime":"2026-10-05T19:00:00Z"}}}]}}""")
            },
        )
        assertFailsWith<ExternalSourceInvalidPayloadException> {
            invalid.search(sportsQuery)
        }
        Unit
    }

    @Test
    fun `Ticketmaster separates empty unauthorized rate limited unavailable and invalid payloads`() = runBlocking {
        assertEquals(emptyList(), ticketmasterMusicSource(MockEngine { respondJson("""{}""") }).search(liveMusicQuery))
        assertIs<ExternalSourceUnauthorizedException>(ticketmasterMusicFailure(HttpStatusCode.Unauthorized))
        assertIs<ExternalSourceUnauthorizedException>(ticketmasterMusicFailure(HttpStatusCode.Forbidden))
        assertIs<ExternalSourceRateLimitedException>(ticketmasterMusicFailure(HttpStatusCode.TooManyRequests))
        assertIs<ExternalSourceUnavailableException>(ticketmasterMusicFailure(HttpStatusCode.BadGateway))

        val invalid = ticketmasterMusicSource(
            MockEngine {
                respondJson("""{"_embedded":{"events":[{"id":"tm-1001","dates":{"start":{"dateTime":"2026-10-05T19:00:00Z"}}}]}}""")
            },
        )
        assertFailsWith<ExternalSourceInvalidPayloadException> {
            invalid.search(liveMusicQuery)
        }
        Unit
    }

    @Test
    fun `Ticketmaster typed cache excludes query api key and raw provider payload`() = runBlocking {
        val store = RecordingSourceCacheStore()
        val source = ticketmasterMusicSource(
            cacheStore = store,
            engine = MockEngine {
                respondJson(ticketmasterEventPayload(rawMarker = "RAW_QUERY_KEY_PROVIDER_SHOULD_NOT_CACHE"))
            },
        )

        source.search(liveMusicQuery)

        assertTrue(store.keys.none { it.value.contains("tm-k") })
        val cached = store.putValues.single().decodeToString()
        assertTrue(cached.contains("Radiohead Live"))
        assertFalse(cached.contains("tm-k"))
        assertFalse(cached.contains("RAW_QUERY_KEY_PROVIDER_SHOULD_NOT_CACHE"))
        assertFalse(cached.contains("raw_marker"))

        val sportsStore = RecordingSourceCacheStore()
        ticketmasterSportsSource(
            cacheStore = sportsStore,
            engine = MockEngine {
                respondJson(ticketmasterEventPayload(rawMarker = "RAW_SPORTS_QUERY_KEY_PROVIDER_SHOULD_NOT_CACHE"))
            },
        ).search(sportsQuery)
        assertTrue(sportsStore.keys.none { it.value.contains("tm-k") })
        val sportsCached = sportsStore.putValues.single().decodeToString()
        assertFalse(sportsCached.contains("tm-k"))
        assertFalse(sportsCached.contains("RAW_SPORTS_QUERY_KEY_PROVIDER_SHOULD_NOT_CACHE"))
    }

    @Test
    fun `MusicBrainz projects valid and separates failure outcomes`() = runBlocking {
        val source = musicBrainzSource(
            engine = MockEngine { request ->
                assertEquals("NexusFlow Test/1.0", request.headers[HttpHeaders.UserAgent])
                assertEquals("/event/", request.url.encodedPath)
                assertEquals("json", request.url.parameters["fmt"])
                respondJson(musicBrainzEventPayload())
            },
        )

        val candidates = source.search(liveMusicQuery)

        assertEquals("mb-1001", candidates.single().externalEventId)
        assertEquals("Radiohead Live", candidates.single().title)
        assertEquals("Roundhouse", candidates.single().venueName)
        assertEquals(SourceAuthority.StructuredSecondary, candidates.single().sources.single().authority)

        assertEquals(emptyList(), musicBrainzSource(MockEngine { respondJson("""{"events":[]}""") }).search(liveMusicQuery))
        assertIs<ExternalSourceUnauthorizedException>(musicBrainzFailure(HttpStatusCode.Unauthorized))
        assertIs<ExternalSourceUnauthorizedException>(musicBrainzFailure(HttpStatusCode.Forbidden))
        assertIs<ExternalSourceRateLimitedException>(musicBrainzFailure(HttpStatusCode.TooManyRequests))
        assertIs<ExternalSourceUnavailableException>(musicBrainzFailure(HttpStatusCode.InternalServerError))
        assertFailsWith<ExternalSourceInvalidPayloadException> {
            musicBrainzSource(MockEngine { respondJson("""{"events":[{"id":"mb-1001"}]}""") }).search(liveMusicQuery)
        }
        Unit
    }

    @Test
    fun `TheSportsDB projects valid and separates failure outcomes`() = runBlocking {
        val source = theSportsDbSource(
            engine = MockEngine { request ->
                assertEquals("/api/v1/json/3/searchevents.php", request.url.encodedPath)
                assertEquals("basketball", request.url.parameters["e"])
                respondJson(theSportsDbEventPayload())
            },
        )

        val candidates = source.search(sportsQuery)

        assertEquals("tsdb-1001", candidates.single().externalEventId)
        assertEquals("London Lions vs Paris", candidates.single().title)
        assertEquals("Basketball", candidates.single().sportName)
        assertEquals(SourceAuthority.StructuredSecondary, candidates.single().sources.single().authority)

        assertEquals(emptyList(), theSportsDbSource(MockEngine { respondJson("""{"event":null}""") }).search(sportsQuery))
        assertIs<ExternalSourceUnauthorizedException>(theSportsDbFailure(HttpStatusCode.Unauthorized))
        assertIs<ExternalSourceUnauthorizedException>(theSportsDbFailure(HttpStatusCode.Forbidden))
        assertIs<ExternalSourceRateLimitedException>(theSportsDbFailure(HttpStatusCode.TooManyRequests))
        assertIs<ExternalSourceUnavailableException>(theSportsDbFailure(HttpStatusCode.ServiceUnavailable))
        assertFailsWith<ExternalSourceInvalidPayloadException> {
            theSportsDbSource(MockEngine { respondJson("""{"event":[{"idEvent":"tsdb-1001"}]}""") }).search(sportsQuery)
        }
        Unit
    }

    private suspend fun ticketmasterMusicFailure(status: HttpStatusCode): Throwable =
        assertFailsWith<Throwable> {
            ticketmasterMusicSource(MockEngine { respondJson("""{}""", status) }).search(liveMusicQuery)
        }

    private suspend fun ticketmasterSportsFailure(status: HttpStatusCode): Throwable =
        assertFailsWith<Throwable> {
            ticketmasterSportsSource(MockEngine { respondJson("""{}""", status) }).search(sportsQuery)
        }

    private suspend fun musicBrainzFailure(status: HttpStatusCode): Throwable =
        assertFailsWith<Throwable> {
            musicBrainzSource(MockEngine { respondJson("""{"events":[]}""", status) }).search(liveMusicQuery)
        }

    private suspend fun theSportsDbFailure(status: HttpStatusCode): Throwable =
        assertFailsWith<Throwable> {
            theSportsDbSource(MockEngine { respondJson("""{"event":[]}""", status) }).search(sportsQuery)
        }

    private fun ticketmasterEventPayload(
        rawMarker: String,
        title: String = "Radiohead Live",
    ): String =
        """
        {
          "_embedded": {
            "events": [
              {
                "id": "tm-1001",
                "name": "$title",
                "url": "https://ticketmaster.test/event/tm-1001",
                "dates": {
                  "start": {"dateTime": "2026-10-05T19:00:00Z"},
                  "end": {"dateTime": "2026-10-05T22:00:00Z"}
                },
                "_embedded": {
                  "venues": [
                    {
                      "name": "O2 Arena",
                      "city": {"name": "London"},
                      "country": {"countryCode": "GB"},
                      "location": {"latitude": "51.503", "longitude": "0.003"}
                    }
                  ],
                  "attractions": [{"name": "Radiohead"}]
                },
                "classifications": [
                  {"segment": {"name": "Sports"}, "genre": {"name": "Basketball"}}
                ],
                "raw_marker": "$rawMarker"
              }
            ]
          }
        }
        """.trimIndent()

    private fun musicBrainzEventPayload(): String =
        """
        {
          "events": [
            {
              "id": "mb-1001",
              "name": "Radiohead Live",
              "time": "19:00",
              "life-span": {"begin": "2026-10-05", "end": "2026-10-05"},
              "relations": [
                {"type": "place", "place": {"name": "Roundhouse"}},
                {"type": "area", "area": {"name": "London"}},
                {"type": "artist", "artist": {"name": "Radiohead"}}
              ]
            }
          ]
        }
        """.trimIndent()

    private fun theSportsDbEventPayload(): String =
        """
        {
          "event": [
            {
              "idEvent": "tsdb-1001",
              "strEvent": "London Lions vs Paris",
              "strSport": "Basketball",
              "strTimestamp": "2026-10-05T19:00:00Z",
              "strVenue": "Copper Box Arena",
              "strCity": "London",
              "strCountry": "England",
              "strLeague": "EuroCup"
            }
          ]
        }
        """.trimIndent()

    private fun ticketmasterMusicSource(
        engine: MockEngine,
        cacheStore: SourceCacheStore? = null,
    ): TicketmasterLiveMusicEventSource =
        TicketmasterLiveMusicEventSource(
            http = ExternalSourceHttpClient(testHttpClient(engine)),
            config = TicketmasterRuntimeConfig(apiKey = "tm-k", baseUrl = "https://ticketmaster.test"),
            cacheStore = cacheStore,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun ticketmasterSportsSource(
        engine: MockEngine,
        cacheStore: SourceCacheStore? = null,
    ): TicketmasterSportsEventSource =
        TicketmasterSportsEventSource(
            http = ExternalSourceHttpClient(testHttpClient(engine)),
            config = TicketmasterRuntimeConfig(apiKey = "tm-k", baseUrl = "https://ticketmaster.test"),
            cacheStore = cacheStore,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun musicBrainzSource(engine: MockEngine): MusicBrainzLiveMusicEventSource =
        MusicBrainzLiveMusicEventSource(
            http = ExternalSourceHttpClient(testHttpClient(engine)),
            config = MusicBrainzRuntimeConfig(baseUrl = "https://musicbrainz.test"),
            userAgent = "NexusFlow Test/1.0",
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun theSportsDbSource(engine: MockEngine): TheSportsDbEventSource =
        TheSportsDbEventSource(
            http = ExternalSourceHttpClient(testHttpClient(engine)),
            config = TheSportsDbRuntimeConfig(baseUrl = "https://thesportsdb.test"),
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
            ttl: Duration,
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
