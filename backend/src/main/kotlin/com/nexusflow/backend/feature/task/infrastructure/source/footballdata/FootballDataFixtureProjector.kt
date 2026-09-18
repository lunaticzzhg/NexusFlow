package com.nexusflow.backend.feature.task.infrastructure.source.footballdata

import com.nexusflow.backend.feature.task.domain.OpportunityFactKey
import com.nexusflow.backend.feature.task.domain.SourceAuthority
import com.nexusflow.backend.feature.task.domain.SourceRef
import com.nexusflow.backend.feature.task.domain.source.FixtureStatus
import com.nexusflow.backend.feature.task.domain.source.FootballFixtureCandidate
import java.time.Instant

internal fun FootballDataMatchDto.toFootballFixtureCandidate(observedAt: Instant): FootballFixtureCandidate {
    val matchId = id ?: throw IllegalArgumentException("football-data match id missing")
    val startsAt = utcDate?.let(Instant::parse) ?: throw IllegalArgumentException("football-data utcDate missing")
    val home = homeTeam?.name?.trim()?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("football-data home team missing")
    val away = awayTeam?.name?.trim()?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("football-data away team missing")
    val venueName = venue?.trim()?.take(MAX_LOCATION_CHARS)?.takeIf(String::isNotBlank)
    return FootballFixtureCandidate(
        externalFixtureId = matchId.toString(),
        competition = competition?.name?.trim()?.take(MAX_COMPETITION_CHARS)?.takeIf(String::isNotBlank),
        homeTeam = home.take(MAX_TEAM_CHARS),
        awayTeam = away.take(MAX_TEAM_CHARS),
        startsAt = startsAt,
        venueName = venueName,
        venueCity = null,
        status = status.toFixtureStatus(),
        sources = listOf(
            SourceRef(
                label = "football-data.org",
                uri = "https://www.football-data.org/",
                sourceUpdatedAt = observedAt,
                sourceId = "football-data",
                authority = SourceAuthority.StructuredSecondary,
                factKeys = buildSet {
                    add(OpportunityFactKey.Title)
                    add(OpportunityFactKey.StartTime)
                    add(OpportunityFactKey.EndTime)
                    add(OpportunityFactKey.FixtureStatus)
                    add(OpportunityFactKey.Availability)
                    if (venueName != null) add(OpportunityFactKey.Location)
                },
            ),
        ),
    )
}

private fun String?.toFixtureStatus(): FixtureStatus =
    when (this?.uppercase()) {
        "SCHEDULED", "TIMED" -> FixtureStatus.Scheduled
        "IN_PLAY", "PAUSED" -> FixtureStatus.Live
        "FINISHED" -> FixtureStatus.Finished
        "POSTPONED", "SUSPENDED" -> FixtureStatus.Postponed
        "CANCELLED" -> FixtureStatus.Cancelled
        else -> FixtureStatus.Unknown
    }

private const val MAX_TEAM_CHARS = 120
private const val MAX_COMPETITION_CHARS = 160
private const val MAX_LOCATION_CHARS = 160
