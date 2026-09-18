package com.nexusflow.backend.feature.task.domain.source

import com.nexusflow.backend.feature.task.domain.AvailabilityFact
import com.nexusflow.backend.feature.task.domain.SourceRef
import java.time.Instant
import java.time.LocalDate

interface GeneralSportsEventSource {
    suspend fun search(query: GeneralSportsEventQuery): List<GeneralSportsEventCandidate>
}

data class GeneralSportsEventQuery(
    val keyword: String,
    val city: String? = null,
    val countryCode: String? = null,
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null,
) {
    init {
        require(keyword.isNotBlank()) { "general sports event query keyword must not be blank" }
    }
}

data class GeneralSportsEventCandidate(
    val externalEventId: String,
    val title: String,
    val sportName: String?,
    val startsAt: Instant,
    val endsAt: Instant?,
    val venueName: String?,
    val city: String?,
    val publicUrl: String?,
    val availability: AvailabilityFact?,
    val sources: List<SourceRef>,
)
