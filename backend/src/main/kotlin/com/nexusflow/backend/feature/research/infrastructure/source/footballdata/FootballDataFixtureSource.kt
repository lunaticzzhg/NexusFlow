package com.nexusflow.backend.feature.research.infrastructure.source.footballdata

import com.nexusflow.backend.core.config.FootballDataRuntimeConfig
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
import com.nexusflow.backend.feature.task.domain.source.FixtureStatus
import com.nexusflow.backend.feature.task.domain.source.FootballFixtureCandidate
import com.nexusflow.backend.feature.task.domain.source.FootballFixtureQuery
import com.nexusflow.backend.feature.task.domain.source.FootballFixtureSource
import com.nexusflow.observability.StructuredLogger
import com.nexusflow.observability.logFields
import com.nexusflow.backend.core.external.externalBody
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant

class FootballDataFixtureSource(
    private val http: ExternalSourceHttpClient,
    private val config: FootballDataRuntimeConfig,
    private val cacheStore: SourceCacheStore? = null,
    private val logger: StructuredLogger? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val cachePolicy: SourceCacheTtlPolicy = SourceCacheTtlPolicy(
        successTtl = Duration.ofHours(2),
        emptyTtl = Duration.ofMinutes(10),
    ),
) : FootballFixtureSource {
    override suspend fun search(query: FootballFixtureQuery): List<FootballFixtureCandidate> {
        val key = SourceCacheKey("source:v1:football-data:fixture:${sha256(query.canonicalCacheRequest())}")
        cacheStore?.get(key)?.let { bytes ->
            val cached = FootballFixtureCandidateListCacheCodec.decode(bytes)
            logAcquisition("search", "success", cached.size, cacheHit = true)
            return cached
        }

        val candidates = executeExternalSourceRequest(logger, PROVIDER, "search") {
            val response = http.client.get("${config.baseUrl.trimEnd('/')}/matches") {
                header("X-Auth-Token", config.apiToken)
                query.dateFrom?.let { parameter("dateFrom", it.toString()) }
                query.dateTo?.let { parameter("dateTo", it.toString()) }
                query.league?.toFootballDataCompetitionCode()?.let { parameter("competitions", it) }
            }
            response.rejectKnownExternalSourceStatus(PROVIDER, "search")
            val body = response.externalBody<FootballDataMatchesResponse>()
            val observedAt = clock.instant()
            val matches = body.matches ?: throw IllegalArgumentException("football-data matches missing")
            matches.map { it.toFootballFixtureCandidate(observedAt) }
                .filterByTeam(query.teamName)
                .take(MAX_RESULTS)
        }

        cacheStore?.put(
            key = key,
            value = FootballFixtureCandidateListCacheCodec.encode(candidates),
            ttl = cachePolicy.ttlFor(if (candidates.isEmpty()) SourceCacheResultKind.SuccessEmpty else SourceCacheResultKind.Success),
        )
        logAcquisition("search", if (candidates.isEmpty()) "empty" else "success", candidates.size, cacheHit = false)
        return candidates
    }

    private fun List<FootballFixtureCandidate>.filterByTeam(teamName: String?): List<FootballFixtureCandidate> {
        val normalized = teamName?.lowercase()?.trim()?.takeIf(String::isNotBlank) ?: return this
        return filter {
            it.homeTeam.lowercase().contains(normalized) || it.awayTeam.lowercase().contains(normalized)
        }
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
                "capability" value "football_fixture"
                "operation" value operation
                "outcome" value outcome
                "result_count" value resultCount
                "cache_mode" value (if (cacheStore == null) "none" else "memory")
                "cache_hit" value cacheHit
                "external_call_avoided" value cacheHit
            },
        )
    }

    private fun FootballFixtureQuery.canonicalCacheRequest(): String =
        "team=${teamName.orEmpty().lowercase().trim()}|from=${dateFrom ?: ""}|to=${dateTo ?: ""}|league=${league.orEmpty().lowercase().trim()}"

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private object FootballFixtureCandidateListCacheCodec : SourceCacheCodec<List<FootballFixtureCandidate>> {
        override fun encode(value: List<FootballFixtureCandidate>): ByteArray =
            Json.encodeToString(
                ListSerializer(FootballFixtureCandidateCacheDocument.serializer()),
                value.map(FootballFixtureCandidateCacheDocument::from),
            ).toByteArray()

        override fun decode(bytes: ByteArray): List<FootballFixtureCandidate> =
            Json.decodeFromString(
                ListSerializer(FootballFixtureCandidateCacheDocument.serializer()),
                bytes.decodeToString(),
            ).map(FootballFixtureCandidateCacheDocument::toDomain)
    }

    @Serializable
    private data class FootballFixtureCandidateCacheDocument(
        val externalFixtureId: String,
        val competition: String?,
        val homeTeam: String,
        val awayTeam: String,
        val startsAt: String,
        val venueName: String?,
        val venueCity: String?,
        val status: FixtureStatus,
        val sources: List<SourceRefCacheDocument>,
    ) {
        fun toDomain(): FootballFixtureCandidate =
            FootballFixtureCandidate(
                externalFixtureId = externalFixtureId,
                competition = competition,
                homeTeam = homeTeam,
                awayTeam = awayTeam,
                startsAt = Instant.parse(startsAt),
                venueName = venueName,
                venueCity = venueCity,
                status = status,
                sources = sources.map { it.toDomain() },
            )

        companion object {
            fun from(candidate: FootballFixtureCandidate): FootballFixtureCandidateCacheDocument =
                FootballFixtureCandidateCacheDocument(
                    externalFixtureId = candidate.externalFixtureId,
                    competition = candidate.competition,
                    homeTeam = candidate.homeTeam,
                    awayTeam = candidate.awayTeam,
                    startsAt = candidate.startsAt.toString(),
                    venueName = candidate.venueName,
                    venueCity = candidate.venueCity,
                    status = candidate.status,
                    sources = candidate.sources.map(SourceRefCacheDocument::from),
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
        const val PROVIDER = "football-data"
        const val MAX_RESULTS = 10
    }
}

private fun String.toFootballDataCompetitionCode(): String? =
    when (lowercase().trim()) {
        "premier_league" -> "PL"
        "champions_league" -> "CL"
        "la_liga" -> "PD"
        "serie_a" -> "SA"
        "bundesliga" -> "BL1"
        "ligue_1" -> "FL1"
        else -> null
    }
