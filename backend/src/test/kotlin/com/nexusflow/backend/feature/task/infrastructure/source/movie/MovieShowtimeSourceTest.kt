package com.nexusflow.backend.feature.research.infrastructure.source.movie

import com.nexusflow.backend.core.config.ChinaOfficialCinemaRuntimeConfig
import com.nexusflow.backend.core.external.ExternalSourceHttpClient
import com.nexusflow.backend.core.external.ExternalSourceInvalidPayloadException
import com.nexusflow.backend.core.external.ExternalSourceRateLimitedException
import com.nexusflow.backend.core.external.ExternalSourceUnauthorizedException
import com.nexusflow.backend.core.external.ExternalSourceUnavailableException
import com.nexusflow.backend.core.external.SourceCacheKey
import com.nexusflow.backend.core.external.SourceCacheStore
import com.nexusflow.backend.feature.task.domain.AvailabilityFact
import com.nexusflow.backend.feature.task.domain.OpportunityFactKey
import com.nexusflow.backend.feature.task.domain.SourceAuthority
import com.nexusflow.backend.feature.task.domain.source.MovieShowtimeQuery
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MovieShowtimeSourceTest {
    private val now = Instant.parse("2026-09-06T00:00:00Z")

    @Test
    fun `China official cinema page projects schema org screening event and passes city query`() = runBlocking {
        val source = showtimeSource(
            engine = MockEngine { request ->
                assertEquals("/showtimes", request.url.encodedPath)
                assertEquals("CN", request.url.parameters["country"])
                assertEquals("深圳", request.url.parameters["city"])
                assertEquals("流浪地球2", request.url.parameters["movie"])
                assertEquals("2026-09-06", request.url.parameters["dateFrom"])
                respondHtml(validScreeningHtml())
            },
        )

        val candidates = source.search(query())

        assertEquals(1, candidates.size)
        val candidate = candidates.single()
        assertEquals("流浪地球2", candidate.movieTitle)
        assertEquals("深圳影城", candidate.cinemaName)
        assertEquals(Instant.parse("2026-09-06T11:30:00Z"), candidate.startsAt)
        assertEquals(Instant.parse("2026-09-06T13:50:00Z"), candidate.endsAt)
        assertEquals("深圳", candidate.city)
        assertEquals(48, candidate.price?.wholeUnits)
        assertEquals("CNY", candidate.price?.currencyCode)
        assertEquals(AvailabilityFact.Available, candidate.availability)
        assertEquals("https://cinema.example/showtimes/1001", candidate.publicUrl)
        assertEquals(SourceAuthority.OfficialWeb, candidate.source.authority)
        assertTrue(OpportunityFactKey.MovieShowtime in candidate.source.factKeys)
        assertTrue(OpportunityFactKey.Price in candidate.source.factKeys)
        assertTrue(OpportunityFactKey.Availability in candidate.source.factKeys)
    }

    @Test
    fun `China official cinema page supports no-title movie discovery`() = runBlocking {
        val source = showtimeSource(
            engine = MockEngine { request ->
                assertEquals("CN", request.url.parameters["country"])
                assertEquals("深圳", request.url.parameters["city"])
                assertNull(request.url.parameters["movie"])
                assertEquals("2026-09-06", request.url.parameters["dateFrom"])
                respondHtml(
                    screeningHtml(
                        screeningEvent(
                            movieTitle = "哪吒2",
                            cinemaName = "深圳影城",
                            city = "深圳",
                            startDate = "2026-09-06T19:30:00+08:00",
                            url = "https://cinema.example/showtimes/nezha",
                        ),
                    ),
                )
            },
        )

        val candidates = source.search(noTitleQuery())

        assertEquals(1, candidates.size)
        assertEquals("哪吒2", candidates.single().movieTitle)
        assertEquals("深圳影城", candidates.single().cinemaName)
    }

    @Test
    fun `China official cinema page filters mismatched title when title is present`() = runBlocking {
        val source = showtimeSource(
            engine = MockEngine {
                respondHtml(
                    screeningHtml(
                        screeningEvent(
                            movieTitle = "哪吒2",
                            cinemaName = "深圳影城",
                            city = "深圳",
                            startDate = "2026-09-06T19:30:00+08:00",
                            url = "https://cinema.example/showtimes/nezha",
                        ),
                    ),
                )
            },
        )

        assertEquals(emptyList(), source.search(query()))
    }

    @Test
    fun `China official cinema page rejects out of city and out of window events on same page`() = runBlocking {
        val source = showtimeSource(
            engine = MockEngine {
                respondHtml(
                    screeningHtml(
                        screeningEvent(
                            cinemaName = "深圳影城",
                            city = "深圳",
                            startDate = "2026-09-06T19:30:00+08:00",
                            url = "https://cinema.example/showtimes/shenzhen-valid",
                        ),
                        screeningEvent(
                            cinemaName = "北京影城",
                            city = "北京",
                            startDate = "2026-09-06T19:30:00+08:00",
                            url = "https://cinema.example/showtimes/beijing-rejected",
                        ),
                        screeningEvent(
                            cinemaName = "深圳影城",
                            city = "深圳",
                            startDate = "2026-09-08T09:30:00+08:00",
                            url = "https://cinema.example/showtimes/outside-window-rejected",
                        ),
                    ),
                )
            },
        )

        val candidates = source.search(query())

        assertEquals(listOf("https://cinema.example/showtimes/shenzhen-valid"), candidates.map { it.publicUrl })
    }

    @Test
    fun `China official cinema page treats empty page as normal no candidates`() = runBlocking {
        val source = showtimeSource(MockEngine { respondHtml("<html><body>暂无场次</body></html>") })

        assertEquals(emptyList(), source.search(query()))
    }

    @Test
    fun `China official cinema page separates unauthorized rate limited unavailable and invalid payloads`() = runBlocking {
        assertIs<ExternalSourceUnauthorizedException>(showtimeFailure(HttpStatusCode.Unauthorized))
        assertIs<ExternalSourceUnauthorizedException>(showtimeFailure(HttpStatusCode.Forbidden))
        assertIs<ExternalSourceRateLimitedException>(showtimeFailure(HttpStatusCode.TooManyRequests))
        assertIs<ExternalSourceUnavailableException>(showtimeFailure(HttpStatusCode.InternalServerError))

        val invalid = showtimeSource(
            MockEngine {
                respondHtml("""<script type="application/ld+json">{"@type":"ScreeningEvent",</script>""")
            },
        )
        assertFailsWith<ExternalSourceInvalidPayloadException> {
            invalid.search(query())
        }
        Unit
    }

    @Test
    fun `China official cinema page typed cache excludes raw html and provider extras`() = runBlocking {
        val store = RecordingSourceCacheStore()
        val source = showtimeSource(
            cacheStore = store,
            engine = MockEngine {
                respondHtml(
                    """
                    <html>
                    <body>RAW_HTML_SHOULD_NOT_CACHE</body>
                    <script type="application/ld+json">
                    {
                      "@context":"https://schema.org",
                      "@type":"ScreeningEvent",
                      "name":"流浪地球2",
                      "startDate":"2026-09-06T19:30:00+08:00",
                      "location":{"@type":"MovieTheater","name":"深圳影城","address":{"addressLocality":"深圳"}},
                      "url":"https://cinema.example/showtimes/1001",
                      "raw_marker":"RAW_JSONLD_SHOULD_NOT_CACHE"
                    }
                    </script>
                    </html>
                    """.trimIndent(),
                )
            },
        )

        source.search(query())

        val cached = store.putValues.single().decodeToString()
        assertTrue(cached.contains("流浪地球2"))
        assertFalse(cached.contains("RAW_HTML_SHOULD_NOT_CACHE"))
        assertFalse(cached.contains("RAW_JSONLD_SHOULD_NOT_CACHE"))
        assertFalse(cached.contains("raw_marker"))
        assertTrue(store.keys.single().value.startsWith("source:v1:china-official-cinema:movie-showtime:"))
    }

    @Test
    fun `China official cinema page cache key distinguishes title absent from title present`() = runBlocking {
        val store = RecordingSourceCacheStore()
        val requestedMovieParameters = mutableListOf<String?>()
        val source = showtimeSource(
            cacheStore = store,
            engine = MockEngine { request ->
                requestedMovieParameters += request.url.parameters["movie"]
                respondHtml(validScreeningHtml())
            },
        )

        source.search(noTitleQuery())
        source.search(query())

        assertEquals(listOf(null, "流浪地球2"), requestedMovieParameters)
        assertEquals(2, store.keys.distinct().size)
    }

    private suspend fun showtimeFailure(status: HttpStatusCode): Throwable {
        val source = showtimeSource(MockEngine { respondHtml("<html></html>", status) })
        return assertFailsWith<Throwable> { source.search(query()) }
    }

    private fun showtimeSource(
        engine: MockEngine,
        cacheStore: SourceCacheStore? = null,
    ): ChinaOfficialCinemaPageShowtimeSource =
        ChinaOfficialCinemaPageShowtimeSource(
            http = ExternalSourceHttpClient(HttpClient(engine)),
            config = ChinaOfficialCinemaRuntimeConfig(pageUrls = listOf("https://cinema.example/showtimes")),
            cacheStore = cacheStore,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun query(): MovieShowtimeQuery =
        MovieShowtimeQuery(
            title = "流浪地球2",
            city = "深圳",
            dateFrom = LocalDate.parse("2026-09-06"),
            dateTo = LocalDate.parse("2026-09-07"),
        )

    private fun noTitleQuery(): MovieShowtimeQuery =
        MovieShowtimeQuery(
            title = null,
            city = "深圳",
            dateFrom = LocalDate.parse("2026-09-06"),
            dateTo = LocalDate.parse("2026-09-07"),
        )

    private fun validScreeningHtml(): String =
        screeningHtml(
            screeningEvent(
                cinemaName = "深圳影城",
                city = "深圳",
                startDate = "2026-09-06T19:30:00+08:00",
                endDate = "2026-09-06T21:50:00+08:00",
                url = "https://cinema.example/showtimes/1001",
            ),
        )

    private fun screeningHtml(vararg events: String): String =
        """
        <html>
        ${events.joinToString("\n")}
        </html>
        """.trimIndent()

    private fun screeningEvent(
        movieTitle: String = "流浪地球2",
        cinemaName: String,
        city: String,
        startDate: String,
        endDate: String? = null,
        url: String,
    ): String =
        """
        <script type="application/ld+json">
        {
          "@context":"https://schema.org",
          "@type":"ScreeningEvent",
          "workPresented":{"@type":"Movie","name":"$movieTitle"},
          "startDate":"$startDate",
          ${endDate?.let { """"endDate":"$it",""" }.orEmpty()}
          "location":{
            "@type":"MovieTheater",
            "name":"$cinemaName",
            "address":{"addressLocality":"$city"}
          },
          "offers":{
            "@type":"Offer",
            "price":"48",
            "priceCurrency":"CNY",
            "availability":"https://schema.org/InStock",
            "url":"$url"
          }
        }
        </script>
        """.trimIndent()

    private fun MockRequestHandleScope.respondHtml(
        content: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = content,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "text/html; charset=utf-8"),
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
