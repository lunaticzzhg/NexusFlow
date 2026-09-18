package com.nexusflow.backend.feature.task.domain.source

import com.nexusflow.backend.feature.task.domain.SourceRef
import java.time.Instant
import java.time.LocalDate

interface FootballFixtureSource {
    suspend fun search(query: FootballFixtureQuery): List<FootballFixtureCandidate>
}

data class FootballFixtureQuery(
    val teamName: String?,
    val dateFrom: LocalDate?,
    val dateTo: LocalDate?,
    /**
     * Backend-owned canonical competition slug, for example "premier_league".
     * Provider adapters must translate this value to provider-specific ids internally.
     */
    val league: String? = null,
) {
    init {
        require(!teamName.isNullOrBlank() || dateFrom != null || dateTo != null || !league.isNullOrBlank()) {
            "fixture query must include a team, league, or date window"
        }
    }
}

data class FootballFixtureCandidate(
    val externalFixtureId: String,
    val competition: String?,
    val homeTeam: String,
    val awayTeam: String,
    val startsAt: Instant,
    val venueName: String?,
    val venueCity: String?,
    val status: FixtureStatus,
    val sources: List<SourceRef>,
)

enum class FixtureStatus {
    Scheduled,
    Live,
    Finished,
    Postponed,
    Cancelled,
    Unknown,
}
