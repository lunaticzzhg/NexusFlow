package com.nexusflow.backend.feature.task.infrastructure.source.ticketmaster

import com.nexusflow.backend.core.config.TicketmasterRuntimeConfig
import com.nexusflow.backend.core.external.ExternalSourceHttpClient
import com.nexusflow.backend.core.external.SourceCacheCodec
import com.nexusflow.backend.core.external.SourceCacheKey
import com.nexusflow.backend.core.external.SourceCacheResultKind
import com.nexusflow.backend.core.external.SourceCacheStore
import com.nexusflow.backend.core.external.SourceCacheTtlPolicy
import com.nexusflow.backend.core.external.executeExternalSourceRequest
import com.nexusflow.backend.core.external.rejectKnownExternalSourceStatus
import com.nexusflow.backend.feature.task.domain.AvailabilityFact
import com.nexusflow.backend.feature.task.domain.source.GeneralSportsEventCandidate
import com.nexusflow.backend.feature.task.domain.source.GeneralSportsEventQuery
import com.nexusflow.backend.feature.task.domain.source.GeneralSportsEventSource
import com.nexusflow.backend.feature.task.infrastructure.source.CachedSourceRefDocument
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
import java.time.ZoneOffset

class TicketmasterSportsEventSource(
    private val http: ExternalSourceHttpClient,
    private val config: TicketmasterRuntimeConfig,
    private val cacheStore: SourceCacheStore? = null,
    private val logger: StructuredLogger? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val cachePolicy: SourceCacheTtlPolicy = SourceCacheTtlPolicy(
        successTtl = Duration.ofMinutes(45),
        emptyTtl = Duration.ofMinutes(10),
    ),
) : GeneralSportsEventSource {
    override suspend fun search(query: GeneralSportsEventQuery): List<GeneralSportsEventCandidate> {
        val key = SourceCacheKey("source:v1:ticketmaster:sports-event:${sha256(query.canonicalCacheRequest())}")
        cacheStore?.get(key)?.let { bytes ->
            val cached = SportsEventCandidateListCacheCodec.decode(bytes)
            logAcquisition("search", "success", cached.size, cacheHit = true)
            return cached
        }

        val candidates = executeExternalSourceRequest(PROVIDER, "search") {
            val response = http.client.get("${config.baseUrl.trimEnd('/')}/events.json") {
                parameter("apikey", config.apiKey)
                parameter("keyword", query.keyword)
                parameter("classificationName", "sports")
                query.city?.let { parameter("city", it) }
                query.countryCode?.let { parameter("countryCode", it) }
                query.dateFrom?.let { parameter("startDateTime", it.atStartOfDay().toInstant(ZoneOffset.UTC).toString()) }
                query.dateTo?.let { parameter("endDateTime", it.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toString()) }
                parameter("size", MAX_RESULTS.toString())
            }
            response.rejectKnownExternalSourceStatus(PROVIDER, "search")
            val observedAt = clock.instant()
            response.body<TicketmasterDiscoveryResponse>()
                .embedded
                ?.events
                .orEmpty()
                .map { it.toSportsEventCandidate(observedAt) }
                .take(MAX_RESULTS)
        }

        cacheStore?.put(
            key = key,
            value = SportsEventCandidateListCacheCodec.encode(candidates),
            ttl = cachePolicy.ttlFor(if (candidates.isEmpty()) SourceCacheResultKind.SuccessEmpty else SourceCacheResultKind.Success),
        )
        logAcquisition("search", if (candidates.isEmpty()) "empty" else "success", candidates.size, cacheHit = false)
        return candidates
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
                "capability" value "general_sports_event"
                "operation" value operation
                "outcome" value outcome
                "result_count" value resultCount
                "cache_mode" value (if (cacheStore == null) "none" else "memory")
                "cache_hit" value cacheHit
                "external_call_avoided" value cacheHit
            },
        )
    }

    private fun GeneralSportsEventQuery.canonicalCacheRequest(): String =
        "keyword=${keyword.normalized()}|city=${city.orEmpty().normalized()}|country=${countryCode.orEmpty().uppercase()}" +
            "|from=${dateFrom ?: ""}|to=${dateTo ?: ""}"

    private fun String.normalized(): String =
        lowercase().replace(Regex("\\s+"), " ").trim()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private object SportsEventCandidateListCacheCodec : SourceCacheCodec<List<GeneralSportsEventCandidate>> {
        override fun encode(value: List<GeneralSportsEventCandidate>): ByteArray =
            Json.encodeToString(
                ListSerializer(SportsEventCandidateCacheDocument.serializer()),
                value.map(SportsEventCandidateCacheDocument::from),
            ).toByteArray()

        override fun decode(bytes: ByteArray): List<GeneralSportsEventCandidate> =
            Json.decodeFromString(
                ListSerializer(SportsEventCandidateCacheDocument.serializer()),
                bytes.decodeToString(),
            ).map(SportsEventCandidateCacheDocument::toDomain)
    }

    @Serializable
    private data class SportsEventCandidateCacheDocument(
        val externalEventId: String,
        val title: String,
        val sportName: String?,
        val startsAt: String,
        val endsAt: String?,
        val venueName: String?,
        val city: String?,
        val publicUrl: String?,
        val availability: AvailabilityFact?,
        val sources: List<CachedSourceRefDocument>,
    ) {
        fun toDomain(): GeneralSportsEventCandidate =
            GeneralSportsEventCandidate(
                externalEventId = externalEventId,
                title = title,
                sportName = sportName,
                startsAt = Instant.parse(startsAt),
                endsAt = endsAt?.let(Instant::parse),
                venueName = venueName,
                city = city,
                publicUrl = publicUrl,
                availability = availability,
                sources = sources.map(CachedSourceRefDocument::toDomain),
            )

        companion object {
            fun from(candidate: GeneralSportsEventCandidate): SportsEventCandidateCacheDocument =
                SportsEventCandidateCacheDocument(
                    externalEventId = candidate.externalEventId,
                    title = candidate.title,
                    sportName = candidate.sportName,
                    startsAt = candidate.startsAt.toString(),
                    endsAt = candidate.endsAt?.toString(),
                    venueName = candidate.venueName,
                    city = candidate.city,
                    publicUrl = candidate.publicUrl,
                    availability = candidate.availability,
                    sources = candidate.sources.map(CachedSourceRefDocument::from),
                )
        }
    }

    private companion object {
        const val PROVIDER = "ticketmaster"
        const val MAX_RESULTS = 10
    }
}
