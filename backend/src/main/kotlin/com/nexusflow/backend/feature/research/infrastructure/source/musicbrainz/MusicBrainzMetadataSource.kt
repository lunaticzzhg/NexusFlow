package com.nexusflow.backend.feature.research.infrastructure.source.musicbrainz

import com.nexusflow.backend.core.config.MusicBrainzRuntimeConfig
import com.nexusflow.backend.core.external.ExternalSourceHttpClient
import com.nexusflow.backend.core.external.ExternalSourcePolicyRateLimitedException
import com.nexusflow.backend.core.external.requirePublicSourceContact
import com.nexusflow.backend.core.external.SourceCacheCodec
import com.nexusflow.backend.core.external.SourceCacheKey
import com.nexusflow.backend.core.external.SourceCacheResultKind
import com.nexusflow.backend.core.external.SourceCacheStore
import com.nexusflow.backend.core.external.SourceCacheTtlPolicy
import com.nexusflow.backend.core.external.executeExternalSourceRequest
import com.nexusflow.backend.core.external.rejectKnownExternalSourceStatus
import com.nexusflow.backend.feature.task.domain.source.MusicMetadataCandidate
import com.nexusflow.backend.feature.task.domain.source.MusicMetadataQuery
import com.nexusflow.backend.feature.task.domain.source.MusicMetadataSearchType
import com.nexusflow.backend.feature.task.domain.source.MusicMetadataSource
import com.nexusflow.backend.feature.research.infrastructure.source.CachedSourceRefDocument
import com.nexusflow.observability.StructuredLogger
import com.nexusflow.observability.logFields
import com.nexusflow.backend.core.external.externalBody
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.http.HttpHeaders
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.LocalDate

class MusicBrainzMetadataSource(
    private val http: ExternalSourceHttpClient,
    private val config: MusicBrainzRuntimeConfig,
    private val userAgent: String,
    private val cacheStore: SourceCacheStore? = null,
    private val logger: StructuredLogger? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val cachePolicy: SourceCacheTtlPolicy = SourceCacheTtlPolicy(
        successTtl = Duration.ofHours(12),
        emptyTtl = Duration.ofMinutes(30),
    ),
) : MusicMetadataSource {
    override suspend fun search(query: MusicMetadataQuery): List<MusicMetadataCandidate> {
        val effectiveType = query.type ?: MusicMetadataSearchType.Release
        val key = SourceCacheKey("source:v1:musicbrainz:metadata:${sha256(query.canonicalCacheRequest(effectiveType))}")
        cacheStore?.get(key)?.let { bytes ->
            val cached = MusicMetadataCandidateListCacheCodec.decode(bytes)
            logAcquisition(effectiveType, "success", cached.size, cacheHit = true)
            return cached
        }

        val candidates = executeExternalSourceRequest(logger, PROVIDER, "search") {
            requirePublicSourceContact(userAgent, PROVIDER, "search")
            if (!http.musicBrainzRequestGate.tryAcquire()) throw ExternalSourcePolicyRateLimitedException(PROVIDER, "search")
            val observedAt = clock.instant()
            when (effectiveType) {
                MusicMetadataSearchType.Artist -> searchArtists(query, observedAt)
                MusicMetadataSearchType.Release -> searchReleases(query, observedAt)
                MusicMetadataSearchType.Recording -> searchRecordings(query, observedAt)
            }.take(query.maxResults)
        }

        cacheStore?.put(
            key = key,
            value = MusicMetadataCandidateListCacheCodec.encode(candidates),
            ttl = cachePolicy.ttlFor(if (candidates.isEmpty()) SourceCacheResultKind.SuccessEmpty else SourceCacheResultKind.Success),
        )
        logAcquisition(effectiveType, if (candidates.isEmpty()) "empty" else "success", candidates.size, cacheHit = false)
        return candidates
    }

    private suspend fun searchArtists(
        query: MusicMetadataQuery,
        observedAt: java.time.Instant,
    ): List<MusicMetadataCandidate> {
        val response = http.client.get("${config.baseUrl.trimEnd('/')}/artist/") {
            header(HttpHeaders.UserAgent, userAgent)
            parameter("query", query.query)
            parameter("fmt", "json")
            parameter("limit", query.maxResults.toString())
        }
        response.rejectKnownExternalSourceStatus(PROVIDER, "search")
        return response.externalBody<MusicBrainzArtistSearchResponse>()
            .artists
            .map { artist -> artist.toMusicMetadataCandidate(observedAt) }
    }

    private suspend fun searchReleases(
        query: MusicMetadataQuery,
        observedAt: java.time.Instant,
    ): List<MusicMetadataCandidate> {
        val response = http.client.get("${config.baseUrl.trimEnd('/')}/release/") {
            header(HttpHeaders.UserAgent, userAgent)
            parameter("query", query.query)
            parameter("fmt", "json")
            parameter("limit", query.maxResults.toString())
        }
        response.rejectKnownExternalSourceStatus(PROVIDER, "search")
        return response.externalBody<MusicBrainzReleaseSearchResponse>()
            .releases
            .map { release -> release.toMusicMetadataCandidate(observedAt) }
    }

    private suspend fun searchRecordings(
        query: MusicMetadataQuery,
        observedAt: java.time.Instant,
    ): List<MusicMetadataCandidate> {
        val response = http.client.get("${config.baseUrl.trimEnd('/')}/recording/") {
            header(HttpHeaders.UserAgent, userAgent)
            parameter("query", query.query)
            parameter("fmt", "json")
            parameter("limit", query.maxResults.toString())
        }
        response.rejectKnownExternalSourceStatus(PROVIDER, "search")
        return response.externalBody<MusicBrainzRecordingSearchResponse>()
            .recordings
            .map { recording -> recording.toMusicMetadataCandidate(observedAt) }
    }

    private fun logAcquisition(
        type: MusicMetadataSearchType,
        outcome: String,
        resultCount: Int,
        cacheHit: Boolean,
    ) {
        logger?.info(
            component = "external_source",
            event = "source_acquisition_finished",
            fields = logFields {
                "source_id" value PROVIDER
                "capability" value "music_metadata"
                "entity_type" value type.name.lowercase()
                "operation" value "search"
                "outcome" value outcome
                "result_count" value resultCount
                "cache_mode" value (if (cacheStore == null) "none" else "memory")
                "cache_hit" value cacheHit
                "external_call_avoided" value cacheHit
            },
        )
    }

    private fun MusicMetadataQuery.canonicalCacheRequest(type: MusicMetadataSearchType): String =
        "query=${query.normalized()}|type=${type.name.lowercase()}|max=$maxResults"

    private fun String.normalized(): String =
        lowercase().replace(Regex("\\s+"), " ").trim()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private object MusicMetadataCandidateListCacheCodec : SourceCacheCodec<List<MusicMetadataCandidate>> {
        override fun encode(value: List<MusicMetadataCandidate>): ByteArray =
            Json.encodeToString(
                ListSerializer(MusicMetadataCandidateCacheDocument.serializer()),
                value.map(MusicMetadataCandidateCacheDocument::from),
            ).toByteArray()

        override fun decode(bytes: ByteArray): List<MusicMetadataCandidate> =
            Json.decodeFromString(
                ListSerializer(MusicMetadataCandidateCacheDocument.serializer()),
                bytes.decodeToString(),
            ).map(MusicMetadataCandidateCacheDocument::toDomain)
    }

    @Serializable
    private data class MusicMetadataCandidateCacheDocument(
        val externalId: String,
        val type: MusicMetadataSearchType,
        val title: String,
        val artists: Set<String>,
        val date: String?,
        val countryCode: String?,
        val status: String?,
        val disambiguation: String?,
        val lengthMillis: Long?,
        val source: CachedSourceRefDocument,
    ) {
        fun toDomain(): MusicMetadataCandidate =
            MusicMetadataCandidate(
                externalId = externalId,
                type = type,
                title = title,
                artists = artists,
                date = date?.let(LocalDate::parse),
                countryCode = countryCode,
                status = status,
                disambiguation = disambiguation,
                lengthMillis = lengthMillis,
                source = source.toDomain(),
            )

        companion object {
            fun from(candidate: MusicMetadataCandidate): MusicMetadataCandidateCacheDocument =
                MusicMetadataCandidateCacheDocument(
                    externalId = candidate.externalId,
                    type = candidate.type,
                    title = candidate.title,
                    artists = candidate.artists,
                    date = candidate.date?.toString(),
                    countryCode = candidate.countryCode,
                    status = candidate.status,
                    disambiguation = candidate.disambiguation,
                    lengthMillis = candidate.lengthMillis,
                    source = CachedSourceRefDocument.from(candidate.source),
                )
        }
    }

    private companion object {
        const val PROVIDER = "musicbrainz"
    }
}
