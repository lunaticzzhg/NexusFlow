package com.nexusflow.backend.feature.task.domain.source

import com.nexusflow.backend.feature.task.domain.AvailabilityFact
import com.nexusflow.backend.feature.task.domain.SourceRef
import java.time.Instant
import java.time.LocalDate

interface LiveMusicEventSource {
    suspend fun search(query: LiveMusicEventQuery): List<LiveMusicEventCandidate>
}

data class LiveMusicEventQuery(
    val keyword: String,
    val city: String? = null,
    val countryCode: String? = null,
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null,
) {
    init {
        require(keyword.isNotBlank()) { "live music event query keyword must not be blank" }
    }
}

data class LiveMusicEventCandidate(
    val externalEventId: String,
    val title: String,
    val artists: Set<String>,
    val startsAt: Instant,
    val endsAt: Instant?,
    val venueName: String?,
    val city: String?,
    val latitude: Double?,
    val longitude: Double?,
    val publicUrl: String?,
    val availability: AvailabilityFact?,
    val sources: List<SourceRef>,
)
