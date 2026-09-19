package com.nexusflow.backend.feature.research.application.source

import com.nexusflow.backend.core.external.ExternalSourceException
import com.nexusflow.backend.feature.task.application.TaskDependencyUnavailableException
import com.nexusflow.backend.feature.task.domain.ActivityModeValue
import com.nexusflow.backend.feature.task.domain.FactValue
import com.nexusflow.backend.feature.task.domain.LocationFact
import com.nexusflow.backend.feature.task.domain.Opportunity
import com.nexusflow.backend.feature.task.domain.OpportunityFacts
import com.nexusflow.backend.feature.task.domain.OpportunityId
import com.nexusflow.backend.feature.task.domain.OpportunityKind
import com.nexusflow.backend.feature.task.domain.source.GeneralSportsEventCandidate
import com.nexusflow.backend.feature.task.domain.source.GeneralSportsEventQuery
import com.nexusflow.backend.feature.task.domain.source.GeneralSportsEventSource
import com.nexusflow.backend.feature.task.domain.source.WebDiscoverySource
import com.nexusflow.backend.feature.task.domain.source.WebSearchQuery
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID

class GeneralSportsEventAcquirer(
    private val primary: GeneralSportsEventSource?,
    private val secondary: GeneralSportsEventSource?,
    private val webDiscoverySource: WebDiscoverySource?,
) {
    suspend fun acquire(
        query: GeneralSportsEventQuery,
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
            // Web discovery is not an authoritative GeneralSportsEvent source in Slice 3.
        }
        if (primary == null && secondary == null && webDiscoverySource == null) {
            throw TaskDependencyUnavailableException("General sports event source chain is not configured")
        }
        if (primary == null && secondary == null) {
            throw TaskDependencyUnavailableException("General sports event vertical source chain is not configured")
        }
        if (verticalOutcomes.isNotEmpty() && verticalOutcomes.all { it == SourceOutcome.TechnicalFailure }) {
            throw TaskDependencyUnavailableException("General sports event source chain is temporarily unavailable")
        }
        return emptyList()
    }

    private suspend fun GeneralSportsEventSource.searchSafely(
        query: GeneralSportsEventQuery,
        outcomes: MutableList<SourceOutcome>,
    ): List<GeneralSportsEventCandidate>? =
        try {
            search(query).also { candidates ->
                outcomes += if (candidates.isEmpty()) SourceOutcome.SuccessEmpty else SourceOutcome.SuccessWithResults
            }
        } catch (_: ExternalSourceException) {
            outcomes += SourceOutcome.TechnicalFailure
            null
        }

    private fun mergeCandidates(
        primaryCandidates: List<GeneralSportsEventCandidate>,
        secondaryCandidates: List<GeneralSportsEventCandidate>,
    ): List<GeneralSportsEventCandidate> {
        if (primaryCandidates.isEmpty()) return secondaryCandidates
        if (secondaryCandidates.isEmpty()) return primaryCandidates
        val secondaryByIdentity = secondaryCandidates.associateBy { it.identityKey() }
        return primaryCandidates.map { primaryCandidate ->
            val secondaryCandidate = secondaryByIdentity[primaryCandidate.identityKey()] ?: return@map primaryCandidate
            primaryCandidate.copy(
                sportName = primaryCandidate.sportName ?: secondaryCandidate.sportName,
                endsAt = primaryCandidate.endsAt ?: secondaryCandidate.endsAt,
                venueName = primaryCandidate.venueName ?: secondaryCandidate.venueName,
                city = primaryCandidate.city ?: secondaryCandidate.city,
                publicUrl = primaryCandidate.publicUrl ?: secondaryCandidate.publicUrl,
                availability = primaryCandidate.availability ?: secondaryCandidate.availability,
                sources = primaryCandidate.sources + secondaryCandidate.sources,
            )
        } + secondaryCandidates.filter { candidate ->
            primaryCandidates.none { it.identityKey() == candidate.identityKey() }
        }
    }

    private fun GeneralSportsEventCandidate.isSufficient(referenceTime: Instant): Boolean =
        startsAt.isAfter(referenceTime) &&
            title.isNotBlank() &&
            (!venueName.isNullOrBlank() || !city.isNullOrBlank()) &&
            sources.isNotEmpty()

    private fun GeneralSportsEventCandidate.toOpportunity(referenceTime: Instant): Opportunity {
        val locationName = listOfNotNull(venueName, city).joinToString(", ")
        return Opportunity(
            id = OpportunityId(UUID.nameUUIDFromBytes("general-sports-event:$externalEventId".toByteArray(StandardCharsets.UTF_8))),
            provider = sources.firstOrNull()?.sourceId ?: "general-sports-event",
            externalKey = externalEventId,
            kind = OpportunityKind.Sports,
            title = title,
            facts = OpportunityFacts(
                summary = sportName,
                startTime = startsAt,
                endTime = endsAt ?: startsAt.plus(Duration.ofHours(2)),
                location = LocationFact(locationName, locationName.lowercase()),
                activityMode = ActivityModeValue.OutOfHome,
                price = null,
                commute = null,
                availability = availability,
                attributes = mapOf(
                    "topics" to FactValue.Text(listOfNotNull("sports", sportName).joinToString(",")),
                    "locations" to FactValue.Text(locationName),
                ),
            ),
            sources = sources.distinct(),
            observedAt = referenceTime,
            validUntil = minOf(startsAt, referenceTime.plus(Duration.ofHours(12))),
        )
    }

    private fun GeneralSportsEventCandidate.identityKey(): String =
        "${title.lowercase().trim()}|$startsAt"

    private fun GeneralSportsEventQuery.toWebQuery(): String =
        listOfNotNull(keyword, city, countryCode, dateFrom?.toString(), dateTo?.toString(), "official sports event")
            .joinToString(" ")

    private enum class SourceOutcome {
        SuccessWithResults,
        SuccessEmpty,
        TechnicalFailure,
    }
}
