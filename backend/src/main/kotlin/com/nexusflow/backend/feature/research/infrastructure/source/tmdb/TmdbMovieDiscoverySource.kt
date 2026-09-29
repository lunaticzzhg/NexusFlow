package com.nexusflow.backend.feature.research.infrastructure.source.tmdb

import com.nexusflow.backend.core.config.TmdbRuntimeConfig
import com.nexusflow.backend.core.external.ExternalSourceHttpClient
import com.nexusflow.backend.core.external.SourceCacheCodec
import com.nexusflow.backend.core.external.SourceCacheKey
import com.nexusflow.backend.core.external.SourceCacheResultKind
import com.nexusflow.backend.core.external.SourceCacheStore
import com.nexusflow.backend.core.external.SourceCacheTtlPolicy
import com.nexusflow.backend.core.external.executeExternalSourceRequest
import com.nexusflow.backend.core.external.rejectKnownExternalSourceStatus
import com.nexusflow.backend.feature.task.domain.source.MovieDiscoveryCandidate
import com.nexusflow.backend.feature.task.domain.source.MovieDiscoveryMode
import com.nexusflow.backend.feature.task.domain.source.MovieDiscoveryQuery
import com.nexusflow.backend.feature.task.domain.source.MovieDiscoverySource
import com.nexusflow.backend.feature.research.infrastructure.source.CachedSourceRefDocument
import com.nexusflow.observability.StructuredLogger
import com.nexusflow.observability.logFields
import com.nexusflow.backend.core.external.externalBody
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class TmdbMovieDiscoverySource(
    private val http: ExternalSourceHttpClient,
    private val config: TmdbRuntimeConfig,
    private val cacheStore: SourceCacheStore? = null,
    private val logger: StructuredLogger? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val cachePolicy: SourceCacheTtlPolicy = SourceCacheTtlPolicy(
        successTtl = Duration.ofHours(6),
        emptyTtl = Duration.ofMinutes(30),
    ),
) : MovieDiscoverySource {
    override suspend fun discover(query: MovieDiscoveryQuery): List<MovieDiscoveryCandidate> {
        val key = SourceCacheKey("source:v1:tmdb:movie-discovery:${sha256(query.canonicalCacheRequest())}")
        cacheStore?.get(key)?.let { bytes ->
            val cached = MovieDiscoveryCandidateListCacheCodec.decode(bytes)
            logAcquisition(query.mode.operationName(), if (cached.isEmpty()) "empty" else "success", cached.size, cacheHit = true)
            return cached
        }

        val candidates = executeExternalSourceRequest(logger, PROVIDER, query.mode.operationName()) {
            val response = http.client.get("${config.baseUrl.trimEnd('/')}/${query.mode.path()}") {
                bearerAuth(config.apiReadToken)
                query.language?.let { parameter("language", it) }
                if (query.mode != MovieDiscoveryMode.Trending) {
                    query.region?.let { parameter("region", it) }
                }
            }
            response.rejectKnownExternalSourceStatus(PROVIDER, query.mode.operationName())
            val body = response.externalBody<TmdbSearchResponse>()
            val observedAt = clock.instant()
            val summaries = body.results ?: throw IllegalArgumentException("TMDB results missing")
            summaries
                .map { summary -> summary.toMovieDiscoveryCandidate(observedAt) }
                .filterByDate(query)
                .take(query.maxResults)
        }

        cacheStore?.put(
            key = key,
            value = MovieDiscoveryCandidateListCacheCodec.encode(candidates),
            ttl = cachePolicy.ttlFor(if (candidates.isEmpty()) SourceCacheResultKind.SuccessEmpty else SourceCacheResultKind.Success),
        )
        logAcquisition(query.mode.operationName(), if (candidates.isEmpty()) "empty" else "success", candidates.size, cacheHit = false)
        return candidates
    }

    private fun List<MovieDiscoveryCandidate>.filterByDate(query: MovieDiscoveryQuery): List<MovieDiscoveryCandidate> =
        filter { candidate ->
            val releaseDate = candidate.releaseDate ?: return@filter true
            (query.dateFrom == null || !releaseDate.isBefore(query.dateFrom)) &&
                (query.dateTo == null || !releaseDate.isAfter(query.dateTo))
        }

    private fun MovieDiscoveryMode.path(): String =
        when (this) {
            MovieDiscoveryMode.NowPlaying -> "movie/now_playing"
            MovieDiscoveryMode.Upcoming -> "movie/upcoming"
            MovieDiscoveryMode.Trending -> "trending/movie/week"
        }

    private fun MovieDiscoveryMode.operationName(): String =
        when (this) {
            MovieDiscoveryMode.NowPlaying -> "now_playing"
            MovieDiscoveryMode.Upcoming -> "upcoming"
            MovieDiscoveryMode.Trending -> "trending"
        }

    private fun logAcquisition(
        operation: String,
        outcome: String,
        resultCount: Int,
        cacheHit: Boolean,
    ) {
        logger?.info(
            component = "external_source",
            event = "source_acquisition_finished",
            fields = logFields {
                "source_id" value PROVIDER
                "capability" value "movie_discovery"
                "operation" value operation
                "outcome" value outcome
                "result_count" value resultCount
                "cache_mode" value (if (cacheStore == null) "none" else "memory")
                "cache_hit" value cacheHit
                "external_call_avoided" value cacheHit
            },
        )
    }

    private fun MovieDiscoveryQuery.canonicalCacheRequest(): String =
        "mode=${mode.name}|region=${region.orEmpty()}|language=${language.orEmpty()}|" +
            "from=${dateFrom ?: ""}|to=${dateTo ?: ""}|maxResults=$maxResults"

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private object MovieDiscoveryCandidateListCacheCodec : SourceCacheCodec<List<MovieDiscoveryCandidate>> {
        override fun encode(value: List<MovieDiscoveryCandidate>): ByteArray =
            Json.encodeToString(
                ListSerializer(MovieDiscoveryCandidateCacheDocument.serializer()),
                value.map(MovieDiscoveryCandidateCacheDocument::from),
            ).toByteArray()

        override fun decode(bytes: ByteArray): List<MovieDiscoveryCandidate> =
            Json.decodeFromString(
                ListSerializer(MovieDiscoveryCandidateCacheDocument.serializer()),
                bytes.decodeToString(),
            ).map(MovieDiscoveryCandidateCacheDocument::toDomain)
    }

    @Serializable
    private data class MovieDiscoveryCandidateCacheDocument(
        val externalMovieId: String,
        val title: String,
        val originalTitle: String?,
        val releaseDate: String?,
        val summary: String?,
        val genreIds: List<String>,
        val popularity: Double?,
        val publicUrl: String?,
        val source: CachedSourceRefDocument,
    ) {
        fun toDomain(): MovieDiscoveryCandidate =
            MovieDiscoveryCandidate(
                externalMovieId = externalMovieId,
                title = title,
                originalTitle = originalTitle,
                releaseDate = releaseDate?.let(LocalDate::parse),
                summary = summary,
                genreIds = genreIds,
                popularity = popularity,
                publicUrl = publicUrl,
                source = source.toDomain(),
            )

        companion object {
            fun from(candidate: MovieDiscoveryCandidate): MovieDiscoveryCandidateCacheDocument =
                MovieDiscoveryCandidateCacheDocument(
                    externalMovieId = candidate.externalMovieId,
                    title = candidate.title,
                    originalTitle = candidate.originalTitle,
                    releaseDate = candidate.releaseDate?.toString(),
                    summary = candidate.summary,
                    genreIds = candidate.genreIds,
                    popularity = candidate.popularity,
                    publicUrl = candidate.publicUrl,
                    source = CachedSourceRefDocument.from(candidate.source),
                )
        }
    }

    private companion object {
        const val PROVIDER = "tmdb"
    }
}
