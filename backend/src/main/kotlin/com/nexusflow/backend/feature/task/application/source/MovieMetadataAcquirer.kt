package com.nexusflow.backend.feature.task.application.source

import com.nexusflow.backend.core.external.ExternalSourceException
import com.nexusflow.backend.feature.task.application.TaskDependencyUnavailableException
import com.nexusflow.backend.feature.task.domain.source.MovieMetadataCandidate
import com.nexusflow.backend.feature.task.domain.source.MovieMetadataQuery
import com.nexusflow.backend.feature.task.domain.source.MovieMetadataSource

class MovieMetadataAcquirer(
    private val primary: MovieMetadataSource?,
    private val secondary: MovieMetadataSource?,
) {
    suspend fun acquire(query: MovieMetadataQuery): List<MovieMetadataCandidate> {
        val verticalOutcomes = mutableListOf<SourceOutcome>()
        primary?.searchSafely(query, verticalOutcomes)?.takeIf(List<MovieMetadataCandidate>::isNotEmpty)?.let { return it }
        secondary?.searchSafely(query, verticalOutcomes)?.takeIf(List<MovieMetadataCandidate>::isNotEmpty)?.let { return it }
        if (primary == null && secondary == null) {
            throw TaskDependencyUnavailableException("Movie metadata vertical source chain is not configured")
        }
        if (verticalOutcomes.isNotEmpty() && verticalOutcomes.all { it == SourceOutcome.TechnicalFailure }) {
            throw TaskDependencyUnavailableException("Movie metadata source chain is temporarily unavailable")
        }
        return emptyList()
    }

    private suspend fun MovieMetadataSource.searchSafely(
        query: MovieMetadataQuery,
        outcomes: MutableList<SourceOutcome>,
    ): List<MovieMetadataCandidate>? =
        try {
            search(query).also { candidates ->
                outcomes += if (candidates.isEmpty()) SourceOutcome.SuccessEmpty else SourceOutcome.SuccessWithResults
            }
        } catch (_: ExternalSourceException) {
            outcomes += SourceOutcome.TechnicalFailure
            null
        }

    private enum class SourceOutcome {
        SuccessWithResults,
        SuccessEmpty,
        TechnicalFailure,
    }
}
