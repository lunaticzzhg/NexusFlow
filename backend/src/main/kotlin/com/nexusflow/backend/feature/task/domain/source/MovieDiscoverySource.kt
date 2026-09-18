package com.nexusflow.backend.feature.task.domain.source

import com.nexusflow.backend.feature.task.domain.SourceRef
import java.time.LocalDate

interface MovieDiscoverySource {
    suspend fun discover(query: MovieDiscoveryQuery): List<MovieDiscoveryCandidate>
}

data class MovieDiscoveryQuery(
    val mode: MovieDiscoveryMode,
    val region: String?,
    val language: String?,
    val dateFrom: LocalDate?,
    val dateTo: LocalDate?,
    val maxResults: Int,
) {
    init {
        require(maxResults in 1..8) { "maxResults must be between 1 and 8" }
    }
}

data class MovieDiscoveryCandidate(
    val externalMovieId: String,
    val title: String,
    val originalTitle: String?,
    val releaseDate: LocalDate?,
    val summary: String?,
    val genreIds: List<String>,
    val popularity: Double?,
    val publicUrl: String?,
    val source: SourceRef,
)

enum class MovieDiscoveryMode {
    NowPlaying,
    Upcoming,
    Trending,
}
