package com.nexusflow.backend.feature.task.application.source

import com.nexusflow.backend.core.external.ExternalSourceException
import com.nexusflow.backend.feature.task.application.TaskDependencyUnavailableException
import com.nexusflow.backend.feature.task.domain.ActivityModeValue
import com.nexusflow.backend.feature.task.domain.FactValue
import com.nexusflow.backend.feature.task.domain.LocationFact
import com.nexusflow.backend.feature.task.domain.Opportunity
import com.nexusflow.backend.feature.task.domain.OpportunityFacts
import com.nexusflow.backend.feature.task.domain.OpportunityId
import com.nexusflow.backend.feature.task.domain.OpportunityKind
import com.nexusflow.backend.feature.task.domain.source.LiveMusicEventCandidate
import com.nexusflow.backend.feature.task.domain.source.LiveMusicEventQuery
import com.nexusflow.backend.feature.task.domain.source.LiveMusicEventSource
import com.nexusflow.backend.feature.task.domain.source.WebDiscoverySource
import com.nexusflow.backend.feature.task.domain.source.WebSearchQuery
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID

class LiveMusicEventAcquirer(
    private val primary: LiveMusicEventSource?,
    private val secondary: LiveMusicEventSource?,
    private val webDiscoverySource: WebDiscoverySource?,
) {
    suspend fun acquire(
        query: LiveMusicEventQuery,
        referenceTime: Instant,
    ): List<Opportunity> {
        val verticalOutcomes = mutableListOf<SourceOutcome>()
        val primaryCandidates = primary?.searchSafely(query, verticalOutcomes).orEmpty()
        val sufficientPrimary = primaryCandidates.filter { it.isSufficient(referenceTime) }
        if (sufficientPrimary.isNotEmpty()) return sufficientPrimary.map { it.toOpportunity(referenceTime) }

        val secondaryCandidates = secondary?.searchSafely(query, verticalOutcomes).orEmpty()
        val merged = mergeCandidates(primaryCandidates, secondaryCandidates)
        val sufficientMerged = merged.filter { it.isSufficient(referenceTime) }
        if (sufficientMerged.isNotEmpty()) return sufficientMerged.map { it.toOpportunity(referenceTime) }

        try {
            webDiscoverySource?.search(WebSearchQuery(query.toWebQuery(), maxResults = 5))
        } catch (_: ExternalSourceException) {
            // Web discovery is not an authoritative LiveMusicEvent source in Slice 3.
        }
        if (primary == null && secondary == null && webDiscoverySource == null) {
            throw TaskDependencyUnavailableException("Live music event source chain is not configured")
        }
        if (primary == null && secondary == null) {
            throw TaskDependencyUnavailableException("Live music event vertical source chain is not configured")
        }
        if (verticalOutcomes.isNotEmpty() && verticalOutcomes.all { it == SourceOutcome.TechnicalFailure }) {
            throw TaskDependencyUnavailableException("Live music event source chain is temporarily unavailable")
        }
        return emptyList()
    }

    private suspend fun LiveMusicEventSource.searchSafely(
        query: LiveMusicEventQuery,
        outcomes: MutableList<SourceOutcome>,
    ): List<LiveMusicEventCandidate>? =
        try {
            search(query).also { candidates ->
                outcomes += if (candidates.isEmpty()) SourceOutcome.SuccessEmpty else SourceOutcome.SuccessWithResults
            }
        } catch (_: ExternalSourceException) {
            outcomes += SourceOutcome.TechnicalFailure
            null
        }

    private fun mergeCandidates(
        primaryCandidates: List<LiveMusicEventCandidate>,
        secondaryCandidates: List<LiveMusicEventCandidate>,
    ): List<LiveMusicEventCandidate> {
        if (primaryCandidates.isEmpty()) return secondaryCandidates
        if (secondaryCandidates.isEmpty()) return primaryCandidates
        val secondaryByIdentity = secondaryCandidates.associateBy { it.identityKey() }
        return primaryCandidates.map { primaryCandidate ->
            val secondaryCandidate = secondaryByIdentity[primaryCandidate.identityKey()] ?: return@map primaryCandidate
            primaryCandidate.copy(
                artists = primaryCandidate.artists.ifEmpty { secondaryCandidate.artists },
                endsAt = primaryCandidate.endsAt ?: secondaryCandidate.endsAt,
                venueName = primaryCandidate.venueName ?: secondaryCandidate.venueName,
                city = primaryCandidate.city ?: secondaryCandidate.city,
                latitude = primaryCandidate.latitude ?: secondaryCandidate.latitude,
                longitude = primaryCandidate.longitude ?: secondaryCandidate.longitude,
                publicUrl = primaryCandidate.publicUrl ?: secondaryCandidate.publicUrl,
                availability = primaryCandidate.availability ?: secondaryCandidate.availability,
                sources = primaryCandidate.sources + secondaryCandidate.sources,
            )
        } + secondaryCandidates.filter { candidate ->
            primaryCandidates.none { it.identityKey() == candidate.identityKey() }
        }
    }

    private fun LiveMusicEventCandidate.isSufficient(referenceTime: Instant): Boolean =
        startsAt.isAfter(referenceTime) &&
            title.isNotBlank() &&
            (!venueName.isNullOrBlank() || !city.isNullOrBlank()) &&
            sources.isNotEmpty()

    private fun LiveMusicEventCandidate.toOpportunity(referenceTime: Instant): Opportunity {
        val locationName = listOfNotNull(venueName, city).joinToString(", ")
        return Opportunity(
            id = OpportunityId(UUID.nameUUIDFromBytes("live-music-event:$externalEventId".toByteArray(StandardCharsets.UTF_8))),
            provider = sources.firstOrNull()?.sourceId ?: "live-music-event",
            externalKey = externalEventId,
            kind = OpportunityKind.LiveEvents,
            title = title,
            facts = OpportunityFacts(
                summary = artists.takeIf(Set<String>::isNotEmpty)?.joinToString(", "),
                startTime = startsAt,
                endTime = endsAt ?: startsAt.plus(Duration.ofHours(3)),
                location = LocationFact(locationName, locationName.lowercase()),
                activityMode = ActivityModeValue.OutOfHome,
                price = null,
                commute = null,
                availability = availability,
                attributes = mapOf(
                    "topics" to FactValue.Text((setOf("concert", "music", "live music", "event") + artists).joinToString(",")),
                    "locations" to FactValue.Text(locationName),
                ),
            ),
            sources = sources.distinct(),
            observedAt = referenceTime,
            validUntil = minOf(startsAt, referenceTime.plus(Duration.ofHours(12))),
        )
    }

    private fun LiveMusicEventCandidate.identityKey(): String =
        "${title.lowercase().trim()}|$startsAt"

    private fun LiveMusicEventQuery.toWebQuery(): String =
        listOfNotNull(keyword, city, countryCode, dateFrom?.toString(), dateTo?.toString(), "official concert live music event")
            .joinToString(" ")

    private enum class SourceOutcome {
        SuccessWithResults,
        SuccessEmpty,
        TechnicalFailure,
    }
}
