package com.nexusflow.backend.feature.research.infrastructure.source.movie

import com.nexusflow.backend.core.config.OmdbRuntimeConfig
import com.nexusflow.backend.core.config.TmdbRuntimeConfig
import com.nexusflow.backend.core.external.ExternalSourceHttpClient
import com.nexusflow.backend.core.external.ExternalSourceInvalidPayloadException
import com.nexusflow.backend.core.external.ExternalSourceRateLimitedException
import com.nexusflow.backend.core.external.ExternalSourceUnauthorizedException
import com.nexusflow.backend.core.external.ExternalSourceUnavailableException
import com.nexusflow.backend.core.external.SourceCacheKey
import com.nexusflow.backend.core.external.SourceCacheStore
import com.nexusflow.backend.feature.task.domain.SourceAuthority
import com.nexusflow.backend.feature.task.domain.source.MovieDiscoveryMode
import com.nexusflow.backend.feature.task.domain.source.MovieDiscoveryQuery
import com.nexusflow.backend.feature.task.domain.source.MovieMetadataQuery
import com.nexusflow.backend.feature.research.infrastructure.source.omdb.OmdbMovieMetadataSource
import com.nexusflow.backend.feature.research.infrastructure.source.tmdb.TmdbMovieDiscoverySource
import com.nexusflow.backend.feature.research.infrastructure.source.tmdb.TmdbMovieMetadataSource
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
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MovieMetadataSourceTest {
    private val now = Instant.parse("2026-09-06T00:00:00Z")

    @Test
    fun `TMDB projects valid 200 search and detail payload`() = runBlocking {
        val requests = mutableListOf<String>()
        val source = tmdbSource(
            engine = MockEngine { request ->
                requests += "${request.url.encodedPath}?${request.url.encodedQuery}"
                assertEquals("Bearer tmdb-secret", request.headers[HttpHeaders.Authorization])
                when (request.url.encodedPath) {
                    "/search/movie" -> respondJson(
                        """
                        {"results":[{"id":550,"title":"Fight Club","release_date":"1999-10-15","genre_ids":[18],"overview":"Underground boxing.","raw_marker":"RAW_TMDB"}]}
                        """.trimIndent(),
                    )
                    "/movie/550" -> respondJson("""{"runtime":139,"genres":[{"name":"Drama"}],"raw_marker":"RAW_DETAIL"}""")
                    else -> error("Unexpected TMDB path ${request.url.encodedPath}")
                }
            },
        )

        val candidates = source.search(MovieMetadataQuery("Fight Club", region = "US", language = "en-US"))

        assertEquals(listOf("/search/movie?query=Fight+Club&region=US&language=en-US", "/movie/550?"), requests)
        assertEquals("550", candidates.single().externalMovieId)
        assertEquals("Fight Club", candidates.single().title)
        assertEquals(139, candidates.single().runtimeMinutes)
        assertEquals(setOf("Drama"), candidates.single().genres)
        assertEquals(SourceAuthority.StructuredPrimary, candidates.single().source.authority)
    }

    @Test
    fun `TMDB separates empty unauthorized rate limited unavailable and invalid payloads`() = runBlocking {
        assertEquals(emptyList(), tmdbSource(MockEngine { respondJson("""{"results":[]}""") }).search(MovieMetadataQuery("Nope")))
        assertIs<ExternalSourceUnauthorizedException>(tmdbFailure(HttpStatusCode.Unauthorized))
        assertIs<ExternalSourceUnauthorizedException>(tmdbFailure(HttpStatusCode.Forbidden))
        assertIs<ExternalSourceRateLimitedException>(tmdbFailure(HttpStatusCode.TooManyRequests))
        assertIs<ExternalSourceUnavailableException>(tmdbFailure(HttpStatusCode.BadGateway))

        val invalid = tmdbSource(MockEngine { respondJson("""{"results":[{"id":550}]}""") })
        assertFailsWith<ExternalSourceInvalidPayloadException> {
            invalid.search(MovieMetadataQuery("Missing title"))
        }
        Unit
    }

    @Test
    fun `TMDB movie discovery uses official now playing upcoming and trending endpoints`() = runBlocking {
        val requests = mutableListOf<String>()
        val source = tmdbDiscoverySource(
            engine = MockEngine { request ->
                requests += "${request.url.encodedPath}?${request.url.encodedQuery}"
                assertEquals("Bearer tmdb-secret", request.headers[HttpHeaders.Authorization])
                respondJson(
                    """
                    {"results":[{"id":101,"title":"Discovery","original_title":"Discovery Original","release_date":"2026-09-01","genre_ids":[12,18],"overview":"Current film.","popularity":77.5,"raw_marker":"RAW_TMDB"}]}
                    """.trimIndent(),
                )
            },
        )

        val nowPlaying = source.discover(discoveryQuery(MovieDiscoveryMode.NowPlaying))
        val upcoming = source.discover(discoveryQuery(MovieDiscoveryMode.Upcoming))
        val trending = source.discover(discoveryQuery(MovieDiscoveryMode.Trending))

        assertEquals(
            listOf(
                "/movie/now_playing?language=zh-CN&region=CN",
                "/movie/upcoming?language=zh-CN&region=CN",
                "/trending/movie/week?language=zh-CN",
            ),
            requests,
        )
        assertEquals("101", nowPlaying.single().externalMovieId)
        assertEquals("Discovery", upcoming.single().title)
        assertEquals(listOf("tmdb:12", "tmdb:18"), trending.single().genreIds)
        assertEquals(77.5, trending.single().popularity)
        assertEquals(SourceAuthority.StructuredPrimary, trending.single().source.authority)
    }

    @Test
    fun `TMDB movie discovery cache stores projected candidates without raw provider payload`() = runBlocking {
        val store = RecordingSourceCacheStore()
        val source = tmdbDiscoverySource(
            cacheStore = store,
            engine = MockEngine {
                respondJson(
                    """{"results":[{"id":102,"title":"Projected Discovery","overview":"Safe","raw_marker":"RAW_DISCOVERY_SHOULD_NOT_CACHE"}]}""",
                )
            },
        )

        source.discover(discoveryQuery(MovieDiscoveryMode.NowPlaying))

        val cached = store.putValues.single().decodeToString()
        assertTrue(cached.contains("Projected Discovery"))
        assertFalse(cached.contains("RAW_DISCOVERY_SHOULD_NOT_CACHE"))
        assertFalse(cached.contains("raw_marker"))
        assertTrue(store.keys.single().value.startsWith("source:v1:tmdb:movie-discovery:"))
    }

    @Test
    fun `TMDB typed cache excludes raw provider payload`() = runBlocking {
        val store = RecordingSourceCacheStore()
        val source = tmdbSource(
            cacheStore = store,
            engine = MockEngine { request ->
                when (request.url.encodedPath) {
                    "/search/movie" -> respondJson(
                        """{"results":[{"id":1,"title":"Projected","overview":"Safe","raw_marker":"RAW_SEARCH_SHOULD_NOT_CACHE"}]}""",
                    )
                    "/movie/1" -> respondJson("""{"runtime":100,"genres":[],"raw_marker":"RAW_DETAIL_SHOULD_NOT_CACHE"}""")
                    else -> error("Unexpected path ${request.url.encodedPath}")
                }
            },
        )

        source.search(MovieMetadataQuery("Projected"))

        val cached = store.putValues.single().decodeToString()
        assertTrue(cached.contains("Projected"))
        assertFalse(cached.contains("RAW_SEARCH_SHOULD_NOT_CACHE"))
        assertFalse(cached.contains("RAW_DETAIL_SHOULD_NOT_CACHE"))
        assertFalse(cached.contains("raw_marker"))
    }

    @Test
    fun `OMDb projects valid 200 search and lookup payload`() = runBlocking {
        val source = omdbSource(
            engine = MockEngine { request ->
                assertEquals("omdb-secret", request.url.parameters["apikey"])
                when (request.url.parameters["i"]) {
                    null -> {
                        assertEquals("Inception", request.url.parameters["s"])
                        respondJson("""{"Response":"True","Search":[{"imdbID":"tt1375666"}],"raw_marker":"RAW_SEARCH"}""")
                    }
                    "tt1375666" -> respondJson(
                        """
                        {"Response":"True","imdbID":"tt1375666","Title":"Inception","Released":"16 Jul 2010","Runtime":"148 min","Genre":"Action, Sci-Fi","Plot":"Dream heist.","raw_marker":"RAW_LOOKUP"}
                        """.trimIndent(),
                    )
                    else -> error("Unexpected OMDb lookup ${request.url.parameters["i"]}")
                }
            },
        )

        val candidates = source.search(MovieMetadataQuery("Inception"))

        assertEquals("tt1375666", candidates.single().externalMovieId)
        assertEquals("Inception", candidates.single().title)
        assertEquals(148, candidates.single().runtimeMinutes)
        assertEquals(setOf("Action", "Sci-Fi"), candidates.single().genres)
        assertEquals(SourceAuthority.StructuredSecondary, candidates.single().source.authority)
    }

    @Test
    fun `OMDb separates empty unauthorized rate limited unavailable invalid payloads and keeps secrets out of cache keys`() =
        runBlocking {
            assertEquals(emptyList(), omdbSource(MockEngine { respondJson("""{"Response":"False"}""") }).search(MovieMetadataQuery("Nope")))
            assertIs<ExternalSourceUnauthorizedException>(omdbFailure(HttpStatusCode.Unauthorized))
            assertIs<ExternalSourceUnauthorizedException>(omdbFailure(HttpStatusCode.Forbidden))
            assertIs<ExternalSourceRateLimitedException>(omdbFailure(HttpStatusCode.TooManyRequests))
            assertIs<ExternalSourceUnavailableException>(omdbFailure(HttpStatusCode.InternalServerError))

            val invalid = omdbSource(
                MockEngine { request ->
                    if (request.url.parameters["i"] == null) {
                        respondJson("""{"Response":"True","Search":[{"imdbID":"tt1"}]}""")
                    } else {
                        respondJson("""{"Response":"True","imdbID":"tt1"}""")
                    }
                },
            )
            assertFailsWith<ExternalSourceInvalidPayloadException> {
                invalid.search(MovieMetadataQuery("Missing title"))
            }

            val store = RecordingSourceCacheStore()
            val cachedSource = omdbSource(
                cacheStore = store,
                engine = MockEngine { request ->
                    if (request.url.parameters["i"] == null) {
                        respondJson("""{"Response":"True","Search":[{"imdbID":"tt2"}],"raw_marker":"RAW_SEARCH_SHOULD_NOT_CACHE"}""")
                    } else {
                        respondJson(
                            """{"Response":"True","imdbID":"tt2","Title":"Projected","Released":"01 Jan 2026","Runtime":"90 min","Genre":"Drama","Plot":"Safe","raw_marker":"RAW_LOOKUP_SHOULD_NOT_CACHE"}""",
                        )
                    }
                },
            )

            cachedSource.search(MovieMetadataQuery("Projected"))

            assertTrue(store.keys.none { it.value.contains("omdb-secret") })
            val cached = store.putValues.single().decodeToString()
            assertFalse(cached.contains("RAW_SEARCH_SHOULD_NOT_CACHE"))
            assertFalse(cached.contains("RAW_LOOKUP_SHOULD_NOT_CACHE"))
            assertFalse(cached.contains("raw_marker"))
        }

    private suspend fun tmdbFailure(status: HttpStatusCode): Throwable {
        val source = tmdbSource(MockEngine { respondJson("""{"results":[]}""", status) })
        return assertFailsWith<Throwable> { source.search(MovieMetadataQuery("Failure")) }
    }

    private suspend fun omdbFailure(status: HttpStatusCode): Throwable {
        val source = omdbSource(MockEngine { respondJson("""{"Response":"False"}""", status) })
        return assertFailsWith<Throwable> { source.search(MovieMetadataQuery("Failure")) }
    }

    private fun tmdbSource(
        engine: MockEngine,
        cacheStore: SourceCacheStore? = null,
    ): TmdbMovieMetadataSource =
        TmdbMovieMetadataSource(
            http = ExternalSourceHttpClient(testHttpClient(engine)),
            config = TmdbRuntimeConfig(apiReadToken = "tmdb-secret", baseUrl = "https://tmdb.test"),
            cacheStore = cacheStore,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun tmdbDiscoverySource(
        engine: MockEngine,
        cacheStore: SourceCacheStore? = null,
    ): TmdbMovieDiscoverySource =
        TmdbMovieDiscoverySource(
            http = ExternalSourceHttpClient(testHttpClient(engine)),
            config = TmdbRuntimeConfig(apiReadToken = "tmdb-secret", baseUrl = "https://tmdb.test"),
            cacheStore = cacheStore,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun discoveryQuery(mode: MovieDiscoveryMode): MovieDiscoveryQuery =
        MovieDiscoveryQuery(
            mode = mode,
            region = "CN",
            language = "zh-CN",
            dateFrom = null,
            dateTo = null,
            maxResults = 5,
        )

    private fun omdbSource(
        engine: MockEngine,
        cacheStore: SourceCacheStore? = null,
    ): OmdbMovieMetadataSource =
        OmdbMovieMetadataSource(
            http = ExternalSourceHttpClient(testHttpClient(engine)),
            config = OmdbRuntimeConfig(apiKey = "omdb-secret", baseUrl = "https://omdb.test/"),
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
