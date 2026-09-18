package com.nexusflow.backend.feature.task.domain.source

import com.nexusflow.backend.feature.task.domain.AvailabilityFact
import com.nexusflow.backend.feature.task.domain.MoneyFact
import com.nexusflow.backend.feature.task.domain.SourceRef
import java.time.Instant
import java.time.LocalDate

interface MovieShowtimeSource {
    suspend fun search(query: MovieShowtimeQuery): List<MovieShowtimeCandidate>
}

data class MovieShowtimeQuery(
    val title: String?,
    val city: String?,
    val countryCode: String = "CN",
    val dateFrom: LocalDate?,
    val dateTo: LocalDate?,
    val knownMovieTitles: Set<String> = emptySet(),
) {
    init {
        require(countryCode.isNotBlank()) { "countryCode must not be blank" }
    }
}

data class MovieShowtimeCandidate(
    val externalShowtimeId: String,
    val movieTitle: String,
    val cinemaName: String,
    val startsAt: Instant,
    val endsAt: Instant?,
    val city: String?,
    val price: MoneyFact?,
    val availability: AvailabilityFact?,
    val publicUrl: String,
    val source: SourceRef,
)
