package com.nexusflow.backend.feature.research.application.source

import com.nexusflow.backend.core.external.ExternalSourceException
import com.nexusflow.backend.feature.task.application.TaskDependencyUnavailableException
import com.nexusflow.backend.feature.task.domain.ActivityModeValue
import com.nexusflow.backend.feature.task.domain.AvailabilityFact
import com.nexusflow.backend.feature.task.domain.FactValue
import com.nexusflow.backend.feature.task.domain.LocationFact
import com.nexusflow.backend.feature.task.domain.Opportunity
import com.nexusflow.backend.feature.task.domain.OpportunityFacts
import com.nexusflow.backend.feature.task.domain.OpportunityId
import com.nexusflow.backend.feature.task.domain.OpportunityKind
import com.nexusflow.backend.feature.task.domain.source.FixtureStatus
import com.nexusflow.backend.feature.task.domain.source.FootballFixtureCandidate
import com.nexusflow.backend.feature.task.domain.source.FootballFixtureQuery
import com.nexusflow.backend.feature.task.domain.source.FootballFixtureSource
import com.nexusflow.backend.feature.task.domain.source.WebDiscoverySource
import com.nexusflow.backend.feature.task.domain.source.WebSearchQuery
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID

class FootballFixtureAcquirer(
    private val primary: FootballFixtureSource?,
    private val secondary: FootballFixtureSource?,
    private val webDiscoverySource: WebDiscoverySource?,
) {
    suspend fun acquire(
        query: FootballFixtureQuery,
        referenceTime: Instant,
    ): List<Opportunity> =
        acquireFixtures(query, referenceTime).map { it.toOpportunity(referenceTime) }

    suspend fun acquireFixtures(
        query: FootballFixtureQuery,
        referenceTime: Instant,
    ): List<FootballFixtureCandidate> {
        val verticalOutcomes = mutableListOf<SourceOutcome>()
        val primaryCandidates = primary?.searchSafely(query, verticalOutcomes).orEmpty()
        val sufficientPrimary = primaryCandidates.filter { it.isSufficient(referenceTime) }
        if (sufficientPrimary.isNotEmpty()) return sufficientPrimary

        val secondaryCandidates = secondary?.searchSafely(query, verticalOutcomes).orEmpty()
        val merged = mergeCandidates(primaryCandidates, secondaryCandidates)
        val sufficientMerged = merged.filter { it.isSufficient(referenceTime) }
        if (sufficientMerged.isNotEmpty()) return sufficientMerged

        try {
            webDiscoverySource?.search(WebSearchQuery(query.toWebQuery(), maxResults = 5))
        } catch (_: ExternalSourceException) {
            // Web discovery is not an authoritative FootballFixture source in Slice 2.
        }
        if (primary == null && secondary == null && webDiscoverySource == null) {
            throw TaskDependencyUnavailableException("Football fixture source chain is not configured")
        }
        if (primary == null && secondary == null) {
            throw TaskDependencyUnavailableException("Football fixture vertical source chain is not configured")
        }
        if (verticalOutcomes.isNotEmpty() && verticalOutcomes.all { it == SourceOutcome.TechnicalFailure }) {
            throw TaskDependencyUnavailableException("Football fixture source chain is temporarily unavailable")
        }
        return emptyList()
    }

    private suspend fun FootballFixtureSource.searchSafely(
        query: FootballFixtureQuery,
        outcomes: MutableList<SourceOutcome>,
    ): List<FootballFixtureCandidate>? =
        try {
            search(query).also { candidates ->
                outcomes += if (candidates.isEmpty()) SourceOutcome.SuccessEmpty else SourceOutcome.SuccessWithResults
            }
        } catch (_: ExternalSourceException) {
            outcomes += SourceOutcome.TechnicalFailure
            null
        }

    private fun mergeCandidates(
        primaryCandidates: List<FootballFixtureCandidate>,
        secondaryCandidates: List<FootballFixtureCandidate>,
    ): List<FootballFixtureCandidate> {
        if (primaryCandidates.isEmpty()) return secondaryCandidates
        if (secondaryCandidates.isEmpty()) return primaryCandidates
        val secondaryByIdentity = secondaryCandidates.associateBy { it.identityKey() }
        return primaryCandidates.mapNotNull { primaryCandidate ->
            val secondaryCandidate = secondaryByIdentity[primaryCandidate.identityKey()] ?: return@mapNotNull primaryCandidate
            if (primaryCandidate.startsAt != secondaryCandidate.startsAt) return@mapNotNull null
            primaryCandidate.copy(
                competition = primaryCandidate.competition ?: secondaryCandidate.competition,
                venueName = primaryCandidate.venueName ?: secondaryCandidate.venueName,
                venueCity = primaryCandidate.venueCity ?: secondaryCandidate.venueCity,
                sources = primaryCandidate.sources + secondaryCandidate.sources,
            )
        } + secondaryCandidates.filter { candidate ->
            primaryCandidates.none { it.identityKey() == candidate.identityKey() }
        }
    }

    private fun FootballFixtureCandidate.isSufficient(referenceTime: Instant): Boolean =
        startsAt.isAfter(referenceTime) &&
            status in setOf(FixtureStatus.Scheduled, FixtureStatus.Live)

    private fun FootballFixtureCandidate.toOpportunity(referenceTime: Instant): Opportunity {
        val locationName = listOfNotNull(venueName, venueCity).joinToString(", ")
        val title = "$homeTeam vs $awayTeam"
        return Opportunity(
            id = OpportunityId(UUID.nameUUIDFromBytes("football-fixture:$externalFixtureId".toByteArray(StandardCharsets.UTF_8))),
            provider = sources.firstOrNull()?.label ?: "Football fixture source",
            externalKey = externalFixtureId,
            kind = OpportunityKind.Sports,
            title = title,
            facts = OpportunityFacts(
                summary = listOfNotNull(competition, status.name).joinToString(" - ").ifBlank { null },
                startTime = startsAt,
                endTime = startsAt.plus(Duration.ofHours(2)),
                location = locationName.takeIf(String::isNotBlank)?.let { LocationFact(it, it.lowercase()) },
                activityMode = ActivityModeValue.OutOfHome,
                price = null,
                commute = null,
                availability = AvailabilityFact.Available,
                attributes = buildMap {
                    put("topics", FactValue.Text(listOf("sports", "football", homeTeam, awayTeam).joinToString(",")))
                    locationName.takeIf(String::isNotBlank)?.let { put("locations", FactValue.Text(it)) }
                    put("fixtureStatus", FactValue.Text(status.name))
                },
            ),
            sources = sources.distinct(),
            observedAt = referenceTime,
            validUntil = startsAt,
        )
    }

    private fun FootballFixtureCandidate.identityKey(): String =
        listOf(homeTeam, awayTeam).map { it.lowercase().trim() }.sorted().joinToString("|")

    private fun FootballFixtureQuery.toWebQuery(): String =
        listOfNotNull(teamName, league, dateFrom?.toString(), dateTo?.toString(), "official football fixture").joinToString(" ")

    private enum class SourceOutcome {
        SuccessWithResults,
        SuccessEmpty,
        TechnicalFailure,
    }
}
