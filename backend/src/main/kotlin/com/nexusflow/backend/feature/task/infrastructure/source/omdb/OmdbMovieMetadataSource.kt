package com.nexusflow.backend.feature.task.infrastructure.source.omdb

import com.nexusflow.backend.core.config.OmdbRuntimeConfig
import com.nexusflow.backend.core.external.ExternalSourceHttpClient
import com.nexusflow.backend.core.external.SourceCacheCodec
import com.nexusflow.backend.core.external.SourceCacheKey
import com.nexusflow.backend.core.external.SourceCacheResultKind
import com.nexusflow.backend.core.external.SourceCacheStore
import com.nexusflow.backend.core.external.SourceCacheTtlPolicy
import com.nexusflow.backend.core.external.executeExternalSourceRequest
import com.nexusflow.backend.core.external.rejectKnownExternalSourceStatus
import com.nexusflow.backend.feature.task.domain.OpportunityFactKey
import com.nexusflow.backend.feature.task.domain.SourceAuthority
import com.nexusflow.backend.feature.task.domain.SourceRef
import com.nexusflow.backend.feature.task.domain.source.MovieMetadataCandidate
import com.nexusflow.backend.feature.task.domain.source.MovieMetadataQuery
import com.nexusflow.backend.feature.task.domain.source.MovieMetadataSource
import com.nexusflow.observability.StructuredLogger
import com.nexusflow.observability.logFields
import io.ktor.client.call.body
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

class OmdbMovieMetadataSource(
    private val http: ExternalSourceHttpClient,
    private val config: OmdbRuntimeConfig,
    private val cacheStore: SourceCacheStore? = null,
    private val logger: StructuredLogger? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val cachePolicy: SourceCacheTtlPolicy = SourceCacheTtlPolicy(
        successTtl = Duration.ofHours(12),
        emptyTtl = Duration.ofMinutes(30),
    ),
) : MovieMetadataSource {
    override suspend fun search(query: MovieMetadataQuery): List<MovieMetadataCandidate> {
        val key = SourceCacheKey("source:v1:omdb:movie-metadata:${sha256(query.canonicalCacheRequest())}")
        cacheStore?.get(key)?.let { bytes ->
            val cached = MovieMetadataCandidateListCacheCodec.decode(bytes)
            logAcquisition("search", "success", cached.size, cacheHit = true)
            return cached
        }

        val candidates = executeExternalSourceRequest(PROVIDER, "search") {
            val searchResponse = http.client.get(config.baseUrl) {
                parameter("apikey", config.apiKey)
                parameter("s", query.title)
                parameter("type", "movie")
            }
            searchResponse.rejectKnownExternalSourceStatus(PROVIDER, "search")
            val search = searchResponse.body<OmdbSearchResponse>()
            if (search.response.equals("False", ignoreCase = true)) {
                emptyList()
            } else {
                val imdbIds = search.search ?: throw IllegalArgumentException("OMDb Search missing")
                imdbIds.take(MAX_RESULTS).mapNotNull { result ->
                    result.imdbId?.takeIf(String::isNotBlank)?.let { imdbId -> fetchByImdbId(imdbId) }
                }
            }
        }

        cacheStore?.put(
            key = key,
            value = MovieMetadataCandidateListCacheCodec.encode(candidates),
            ttl = cachePolicy.ttlFor(if (candidates.isEmpty()) SourceCacheResultKind.SuccessEmpty else SourceCacheResultKind.Success),
        )
        logAcquisition("search", if (candidates.isEmpty()) "empty" else "success", candidates.size, cacheHit = false)
        return candidates
    }

    private suspend fun fetchByImdbId(imdbId: String): MovieMetadataCandidate? =
        executeExternalSourceRequest(PROVIDER, "lookup") {
            val response = http.client.get(config.baseUrl) {
                parameter("apikey", config.apiKey)
                parameter("i", imdbId)
                parameter("plot", "short")
            }
            response.rejectKnownExternalSourceStatus(PROVIDER, "lookup")
            response.body<OmdbMovieResponse>().toMovieMetadataCandidate(clock.instant())
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
                "capability" value "movie_metadata"
                "operation" value operation
                "outcome" value outcome
                "result_count" value resultCount
                "cache_mode" value (if (cacheStore == null) "none" else "memory")
                "cache_hit" value cacheHit
                "external_call_avoided" value cacheHit
            },
        )
    }

    private fun MovieMetadataQuery.canonicalCacheRequest(): String =
        "title=${title.lowercase().replace(Regex("\\s+"), " ").trim()}"

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private object MovieMetadataCandidateListCacheCodec : SourceCacheCodec<List<MovieMetadataCandidate>> {
        override fun encode(value: List<MovieMetadataCandidate>): ByteArray =
            Json.encodeToString(
                ListSerializer(MovieMetadataCandidateCacheDocument.serializer()),
                value.map(MovieMetadataCandidateCacheDocument::from),
            ).toByteArray()

        override fun decode(bytes: ByteArray): List<MovieMetadataCandidate> =
            Json.decodeFromString(
                ListSerializer(MovieMetadataCandidateCacheDocument.serializer()),
                bytes.decodeToString(),
            ).map(MovieMetadataCandidateCacheDocument::toDomain)
    }

    @Serializable
    private data class MovieMetadataCandidateCacheDocument(
        val externalMovieId: String,
        val title: String,
        val releaseDate: String?,
        val runtimeMinutes: Int?,
        val genres: Set<String>,
        val summary: String?,
        val source: SourceRefCacheDocument,
    ) {
        fun toDomain(): MovieMetadataCandidate =
            MovieMetadataCandidate(
                externalMovieId = externalMovieId,
                title = title,
                releaseDate = releaseDate?.let(LocalDate::parse),
                runtimeMinutes = runtimeMinutes,
                genres = genres,
                summary = summary,
                source = source.toDomain(),
            )

        companion object {
            fun from(candidate: MovieMetadataCandidate): MovieMetadataCandidateCacheDocument =
                MovieMetadataCandidateCacheDocument(
                    externalMovieId = candidate.externalMovieId,
                    title = candidate.title,
                    releaseDate = candidate.releaseDate?.toString(),
                    runtimeMinutes = candidate.runtimeMinutes,
                    genres = candidate.genres,
                    summary = candidate.summary,
                    source = SourceRefCacheDocument.from(candidate.source),
                )
        }
    }

    @Serializable
    private data class SourceRefCacheDocument(
        val label: String,
        val uri: String?,
        val sourceUpdatedAt: String?,
        val sourceId: String,
        val authority: String,
        val factKeys: List<String>,
    ) {
        fun toDomain(): SourceRef =
            SourceRef(
                label = label,
                uri = uri,
                sourceUpdatedAt = sourceUpdatedAt?.let(Instant::parse),
                sourceId = sourceId,
                authority = SourceAuthority.valueOf(authority),
                factKeys = factKeys.mapTo(mutableSetOf()) { OpportunityFactKey.valueOf(it) },
            )

        companion object {
            fun from(source: SourceRef): SourceRefCacheDocument =
                SourceRefCacheDocument(
                    label = source.label,
                    uri = source.uri,
                    sourceUpdatedAt = source.sourceUpdatedAt?.toString(),
                    sourceId = source.sourceId,
                    authority = source.authority.name,
                    factKeys = source.factKeys.map { it.name }.sorted(),
                )
        }
    }

    private companion object {
        const val PROVIDER = "omdb"
        const val MAX_RESULTS = 5
    }
}
