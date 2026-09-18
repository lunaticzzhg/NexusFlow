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
import com.nexusflow.backend.feature.task.domain.SourceAuthority
import com.nexusflow.backend.feature.task.domain.normalizedPlanningToken
import com.nexusflow.backend.feature.task.domain.source.MovieMetadataQuery
import com.nexusflow.backend.feature.task.domain.source.MovieShowtimeCandidate
import com.nexusflow.backend.feature.task.domain.source.MovieShowtimeQuery
import com.nexusflow.backend.feature.task.domain.source.MovieShowtimeSource
import com.nexusflow.backend.feature.task.domain.source.WebDiscoverySource
import com.nexusflow.backend.feature.task.domain.source.WebSearchQuery
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID

class MovieShowtimeAcquirer(
    private val metadataAcquirer: MovieMetadataAcquirer?,
    private val primary: MovieShowtimeSource?,
    private val webDiscoverySource: WebDiscoverySource?,
) {
    suspend fun acquire(
        query: MovieShowtimeQuery,
        referenceTime: Instant,
    ): List<Opportunity> {
        val knownTitles = query.knownTitlesWithMetadata()
        val showtimeQuery = query.copy(knownMovieTitles = knownTitles)
        val verticalOutcomes = mutableListOf<SourceOutcome>()
        val candidates = primary?.searchSafely(showtimeQuery, verticalOutcomes).orEmpty()
        val sufficient = candidates.filter { it.isSufficient(referenceTime, knownTitles) }
        if (sufficient.isNotEmpty()) return sufficient.map { it.toOpportunity(referenceTime) }

        try {
            webDiscoverySource?.search(WebSearchQuery(showtimeQuery.toWebQuery(), maxResults = 5))
        } catch (_: ExternalSourceException) {
            // Web discovery can find official cinema pages, but it is not an authoritative showtime source.
        }
        if (primary == null && webDiscoverySource == null) {
            throw TaskDependencyUnavailableException("Movie showtime source chain is not configured")
        }
        if (primary == null) {
            throw TaskDependencyUnavailableException("Movie showtime official source chain is not configured")
        }
        if (verticalOutcomes.isNotEmpty() && verticalOutcomes.all { it == SourceOutcome.TechnicalFailure }) {
            throw TaskDependencyUnavailableException("Movie showtime source chain is temporarily unavailable")
        }
        return emptyList()
    }

    private suspend fun MovieShowtimeQuery.knownTitlesWithMetadata(): Set<String> {
        val requestedTitle = title?.takeIf(String::isNotBlank)
        val baseTitles = knownMovieTitles + listOfNotNull(requestedTitle)
        val metadataTitles =
            if (requestedTitle == null) {
                emptyList()
            } else {
                try {
                    metadataAcquirer
                        ?.acquire(MovieMetadataQuery(title = requestedTitle, region = countryCode, language = "zh-CN"))
                        ?.map { it.title }
                        .orEmpty()
                } catch (_: ExternalSourceException) {
                    emptyList()
                } catch (_: TaskDependencyUnavailableException) {
                    emptyList()
                }
            }
        return (baseTitles + metadataTitles).mapNotNullTo(mutableSetOf()) { it.takeIf(String::isNotBlank) }
    }

    private suspend fun MovieShowtimeSource.searchSafely(
        query: MovieShowtimeQuery,
        outcomes: MutableList<SourceOutcome>,
    ): List<MovieShowtimeCandidate>? =
        try {
            search(query).also { candidates ->
                outcomes += if (candidates.isEmpty()) SourceOutcome.SuccessEmpty else SourceOutcome.SuccessWithResults
            }
        } catch (_: ExternalSourceException) {
            outcomes += SourceOutcome.TechnicalFailure
            null
        }

    private fun MovieShowtimeCandidate.isSufficient(
        referenceTime: Instant,
        knownTitles: Set<String>,
    ): Boolean =
        startsAt.isAfter(referenceTime) &&
            movieTitle.isNotBlank() &&
            cinemaName.isNotBlank() &&
            publicUrl.isNotBlank() &&
            source.authority == SourceAuthority.OfficialWeb &&
            source.factKeys.isNotEmpty() &&
            (knownTitles.isEmpty() || titleMatches(knownTitles))

    private fun MovieShowtimeCandidate.titleMatches(knownTitles: Set<String>): Boolean {
        val normalizedMovieTitle = movieTitle.normalizedPlanningToken()
        return knownTitles.any { known ->
            val normalizedKnown = known.normalizedPlanningToken()
            normalizedKnown.isNotBlank() &&
                (
                    normalizedMovieTitle == normalizedKnown ||
                        normalizedMovieTitle.contains(normalizedKnown) ||
                        normalizedKnown.contains(normalizedMovieTitle)
                )
        }
    }

    private fun MovieShowtimeCandidate.toOpportunity(referenceTime: Instant): Opportunity {
        val locationName = listOfNotNull(cinemaName, city).joinToString(", ")
        return Opportunity(
            id = OpportunityId(UUID.nameUUIDFromBytes("movie-showtime:$externalShowtimeId".toByteArray(StandardCharsets.UTF_8))),
            provider = source.sourceId,
            externalKey = externalShowtimeId,
            kind = OpportunityKind.Movies,
            title = "$movieTitle at $cinemaName",
            facts = OpportunityFacts(
                summary = null,
                startTime = startsAt,
                endTime = endsAt,
                location = LocationFact(locationName, locationName.lowercase()),
                activityMode = ActivityModeValue.OutOfHome,
                price = price,
                commute = null,
                availability = availability,
                attributes = mapOf(
                    "topics" to FactValue.Text(listOf("movie", "cinema", movieTitle).joinToString(",")),
                    "locations" to FactValue.Text(locationName),
                ),
            ),
            sources = listOf(source),
            observedAt = referenceTime,
            validUntil = minOf(startsAt, referenceTime.plus(Duration.ofHours(3))),
        )
    }

    private fun MovieShowtimeQuery.toWebQuery(): String =
        listOfNotNull(
            title?.takeIf(String::isNotBlank),
            city?.takeIf(String::isNotBlank),
            countryCode,
            dateFrom?.toString(),
            dateTo?.toString(),
            "official cinema showtime",
        )
            .joinToString(" ")

    private enum class SourceOutcome {
        SuccessWithResults,
        SuccessEmpty,
        TechnicalFailure,
    }
}
