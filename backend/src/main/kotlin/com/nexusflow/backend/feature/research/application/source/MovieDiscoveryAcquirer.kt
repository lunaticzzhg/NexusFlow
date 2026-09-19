package com.nexusflow.backend.feature.research.application.source

import com.nexusflow.backend.core.external.ExternalSourceException
import com.nexusflow.backend.feature.task.application.TaskDependencyUnavailableException
import com.nexusflow.backend.feature.task.domain.source.MovieDiscoveryCandidate
import com.nexusflow.backend.feature.task.domain.source.MovieDiscoveryMode
import com.nexusflow.backend.feature.task.domain.source.MovieDiscoveryQuery
import com.nexusflow.backend.feature.task.domain.source.MovieDiscoverySource
import com.nexusflow.backend.feature.task.domain.source.WebDiscoverySource
import com.nexusflow.backend.feature.task.domain.source.WebSearchHit
import com.nexusflow.backend.feature.task.domain.source.WebSearchQuery

class MovieDiscoveryAcquirer(
    private val primary: MovieDiscoverySource?,
    private val webDiscoverySource: WebDiscoverySource?,
) {
    suspend fun acquire(query: MovieDiscoveryQuery): MovieDiscoveryAcquisition {
        val primaryOutcome = primary?.discoverSafely(query)
        when (primaryOutcome) {
            is SourceOutcome.SuccessWithResults ->
                return MovieDiscoveryAcquisition(candidates = primaryOutcome.candidates, webHits = emptyList())
            SourceOutcome.SuccessEmpty,
            SourceOutcome.TechnicalFailure,
            null,
            -> Unit
        }

        val webHits = webDiscoverySource.searchSafely(query)
        if (webHits.isNotEmpty()) {
            return MovieDiscoveryAcquisition(candidates = emptyList(), webHits = webHits)
        }
        if (primary == null && webDiscoverySource == null) {
            throw TaskDependencyUnavailableException("Movie discovery source chain is not configured")
        }
        if (primaryOutcome == SourceOutcome.TechnicalFailure) {
            throw TaskDependencyUnavailableException("Movie discovery source chain is temporarily unavailable")
        }
        return MovieDiscoveryAcquisition(candidates = emptyList(), webHits = emptyList())
    }

    private suspend fun MovieDiscoverySource.discoverSafely(query: MovieDiscoveryQuery): SourceOutcome =
        try {
            val candidates = discover(query)
            if (candidates.isEmpty()) {
                SourceOutcome.SuccessEmpty
            } else {
                SourceOutcome.SuccessWithResults(candidates)
            }
        } catch (_: ExternalSourceException) {
            SourceOutcome.TechnicalFailure
        }

    private suspend fun WebDiscoverySource?.searchSafely(query: MovieDiscoveryQuery): List<WebSearchHit> =
        try {
            this?.search(WebSearchQuery(query.toWebQuery(), maxResults = query.maxResults)).orEmpty()
        } catch (_: ExternalSourceException) {
            emptyList()
        }

    private fun MovieDiscoveryQuery.toWebQuery(): String =
        listOfNotNull(
            when (mode) {
                MovieDiscoveryMode.NowPlaying -> "now playing movies"
                MovieDiscoveryMode.Upcoming -> "upcoming movies"
                MovieDiscoveryMode.Trending -> "trending movies"
            },
            region?.takeIf(String::isNotBlank),
            language?.takeIf(String::isNotBlank),
            dateFrom?.toString(),
            dateTo?.toString(),
        ).joinToString(" ")

    private sealed interface SourceOutcome {
        data class SuccessWithResults(val candidates: List<MovieDiscoveryCandidate>) : SourceOutcome
        data object SuccessEmpty : SourceOutcome
        data object TechnicalFailure : SourceOutcome
    }
}

data class MovieDiscoveryAcquisition(
    val candidates: List<MovieDiscoveryCandidate>,
    val webHits: List<WebSearchHit>,
)
