package com.nexusflow.backend.feature.task.infrastructure.source.liveevent

import com.nexusflow.backend.core.config.MusicBrainzRuntimeConfig
import com.nexusflow.backend.core.external.ExternalSourceHttpClient
import com.nexusflow.backend.core.external.ExternalSourceInvalidPayloadException
import com.nexusflow.backend.core.external.ExternalSourceRateLimitedException
import com.nexusflow.backend.core.external.ExternalSourceUnauthorizedException
import com.nexusflow.backend.core.external.ExternalSourceUnavailableException
import com.nexusflow.backend.core.external.SourceCacheKey
import com.nexusflow.backend.core.external.SourceCacheStore
import com.nexusflow.backend.feature.task.domain.SourceAuthority
import com.nexusflow.backend.feature.task.domain.source.MusicMetadataQuery
import com.nexusflow.backend.feature.task.domain.source.MusicMetadataSearchType
import com.nexusflow.backend.feature.task.infrastructure.source.musicbrainz.MusicBrainzMetadataSource
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

class MusicMetadataSourceTest {
    private val now = Instant.parse("2026-09-06T00:00:00Z")

    @Test
    fun `MusicBrainz release metadata projects typed fields`() = runBlocking {
        val source = musicMetadataSource(
            engine = MockEngine { request ->
                assertEquals("NexusFlow Test/1.0", request.headers[HttpHeaders.UserAgent])
                assertEquals("/release/", request.url.encodedPath)
                assertEquals("Radiohead", request.url.parameters["query"])
                assertEquals("json", request.url.parameters["fmt"])
                assertEquals("3", request.url.parameters["limit"])
                respondJson(releasePayload(rawMarker = "RAW_RELEASE_PAYLOAD"))
            },
        )

        val candidates = source.search(
            MusicMetadataQuery(
                query = "Radiohead",
                type = MusicMetadataSearchType.Release,
                maxResults = 3,
            ),
        )

        val candidate = candidates.single()
        assertEquals("mb-release-1", candidate.externalId)
        assertEquals(MusicMetadataSearchType.Release, candidate.type)
        assertEquals("OK Computer", candidate.title)
        assertEquals(setOf("Radiohead"), candidate.artists)
        assertEquals(LocalDate.of(1997, 5, 21), candidate.date)
        assertEquals("GB", candidate.countryCode)
        assertEquals("Official", candidate.status)
        assertEquals(SourceAuthority.StructuredPrimary, candidate.source.authority)
    }

    @Test
    fun `MusicBrainz artist and recording metadata use the requested entity endpoint`() = runBlocking {
        val paths = mutableListOf<String>()
        val source = musicMetadataSource(
            engine = MockEngine { request ->
                paths += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/artist/" -> respondJson(artistPayload())
                    "/recording/" -> respondJson(recordingPayload())
                    else -> respondJson("""{}""", HttpStatusCode.NotFound)
                }
            },
        )

        val artists = source.search(MusicMetadataQuery("Radiohead", MusicMetadataSearchType.Artist, maxResults = 1))
        val recordings = source.search(MusicMetadataQuery("Paranoid Android", MusicMetadataSearchType.Recording, maxResults = 1))

        assertEquals(listOf("/artist/", "/recording/"), paths)
        assertEquals("Radiohead", artists.single().title)
        assertEquals(LocalDate.of(1985, 1, 1), artists.single().date)
        assertEquals("Paranoid Android", recordings.single().title)
        assertEquals(387000, recordings.single().lengthMillis)
        assertEquals(setOf("Radiohead"), recordings.single().artists)
    }

    @Test
    fun `MusicBrainz metadata separates empty unauthorized rate limited unavailable and invalid payloads`() = runBlocking {
        assertEquals(emptyList(), musicMetadataSource(MockEngine { respondJson("""{"releases":[]}""") }).search(releaseQuery))
        assertIs<ExternalSourceUnauthorizedException>(musicMetadataFailure(HttpStatusCode.Unauthorized))
        assertIs<ExternalSourceUnauthorizedException>(musicMetadataFailure(HttpStatusCode.Forbidden))
        assertIs<ExternalSourceRateLimitedException>(musicMetadataFailure(HttpStatusCode.TooManyRequests))
        assertIs<ExternalSourceUnavailableException>(musicMetadataFailure(HttpStatusCode.InternalServerError))
        assertFailsWith<ExternalSourceInvalidPayloadException> {
            musicMetadataSource(MockEngine { respondJson("""{"releases":[{"id":"mb-release-1"}]}""") }).search(releaseQuery)
        }
        Unit
    }

    @Test
    fun `MusicBrainz metadata cache stores typed projection without raw provider payload`() = runBlocking {
        val store = RecordingSourceCacheStore()
        val source = musicMetadataSource(
            cacheStore = store,
            engine = MockEngine {
                respondJson(releasePayload(rawMarker = "RAW_MUSICBRAINZ_METADATA_SHOULD_NOT_CACHE"))
            },
        )

        source.search(releaseQuery)

        assertTrue(store.keys.none { it.value.contains("Radiohead") })
        val cached = store.putValues.single().decodeToString()
        assertTrue(cached.contains("OK Computer"))
        assertFalse(cached.contains("RAW_MUSICBRAINZ_METADATA_SHOULD_NOT_CACHE"))
        assertFalse(cached.contains("raw_marker"))
    }

    private suspend fun musicMetadataFailure(status: HttpStatusCode): Throwable =
        assertFailsWith<Throwable> {
            musicMetadataSource(MockEngine { respondJson("""{"releases":[]}""", status) }).search(releaseQuery)
        }

    private val releaseQuery = MusicMetadataQuery(
        query = "Radiohead",
        type = MusicMetadataSearchType.Release,
        maxResults = 3,
    )

    private fun releasePayload(rawMarker: String): String =
        """
        {
          "releases": [
            {
              "id": "mb-release-1",
              "title": "OK Computer",
              "date": "1997-05-21",
              "country": "GB",
              "status": "Official",
              "artist-credit": [{"name": "Radiohead", "artist": {"name": "Radiohead"}}],
              "raw_marker": "$rawMarker"
            }
          ]
        }
        """.trimIndent()

    private fun artistPayload(): String =
        """
        {
          "artists": [
            {
              "id": "mb-artist-1",
              "name": "Radiohead",
              "type": "Group",
              "country": "GB",
              "life-span": {"begin": "1985"}
            }
          ]
        }
        """.trimIndent()

    private fun recordingPayload(): String =
        """
        {
          "recordings": [
            {
              "id": "mb-recording-1",
              "title": "Paranoid Android",
              "length": 387000,
              "artist-credit": [{"name": "Radiohead"}],
              "releases": [{"date": "1997-05-21", "country": "GB"}]
            }
          ]
        }
        """.trimIndent()

    private fun musicMetadataSource(
        engine: MockEngine,
        cacheStore: SourceCacheStore? = null,
    ): MusicBrainzMetadataSource =
        MusicBrainzMetadataSource(
            http = ExternalSourceHttpClient(testHttpClient(engine)),
            config = MusicBrainzRuntimeConfig(baseUrl = "https://musicbrainz.test"),
            userAgent = "NexusFlow Test/1.0",
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
