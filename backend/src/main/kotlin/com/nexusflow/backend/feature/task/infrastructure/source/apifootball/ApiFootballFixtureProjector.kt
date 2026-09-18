package com.nexusflow.backend.feature.task.infrastructure.source.apifootball

import com.nexusflow.backend.feature.task.domain.OpportunityFactKey
import com.nexusflow.backend.feature.task.domain.SourceAuthority
import com.nexusflow.backend.feature.task.domain.SourceRef
import com.nexusflow.backend.feature.task.domain.source.FixtureStatus
import com.nexusflow.backend.feature.task.domain.source.FootballFixtureCandidate
import java.time.Instant

internal fun ApiFootballFixtureItemDto.toFootballFixtureCandidate(observedAt: Instant): FootballFixtureCandidate {
    val fixtureValue = fixture ?: throw IllegalArgumentException("API-Football fixture missing")
    val fixtureId = fixtureValue.id ?: throw IllegalArgumentException("API-Football fixture id missing")
    val startsAt = fixtureValue.date?.let(Instant::parse) ?: throw IllegalArgumentException("API-Football date missing")
    val home = teams?.home?.name?.trim()?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("API-Football home team missing")
    val away = teams.away?.name?.trim()?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("API-Football away team missing")
    val venueName = fixtureValue.venue?.name?.trim()?.take(MAX_LOCATION_CHARS)?.takeIf(String::isNotBlank)
    val venueCity = fixtureValue.venue?.city?.trim()?.take(MAX_LOCATION_CHARS)?.takeIf(String::isNotBlank)
    return FootballFixtureCandidate(
        externalFixtureId = fixtureId.toString(),
        competition = league?.name?.trim()?.take(MAX_COMPETITION_CHARS)?.takeIf(String::isNotBlank),
        homeTeam = home.take(MAX_TEAM_CHARS),
        awayTeam = away.take(MAX_TEAM_CHARS),
        startsAt = startsAt,
        venueName = venueName,
        venueCity = venueCity,
        status = fixtureValue.status?.short.toFixtureStatus(),
        sources = listOf(
            SourceRef(
                label = "API-Football",
                uri = "https://www.api-football.com/",
                sourceUpdatedAt = observedAt,
                sourceId = "api-football",
                authority = SourceAuthority.StructuredPrimary,
                factKeys = buildSet {
                    add(OpportunityFactKey.Title)
                    add(OpportunityFactKey.StartTime)
                    add(OpportunityFactKey.EndTime)
                    add(OpportunityFactKey.FixtureStatus)
                    add(OpportunityFactKey.Availability)
                    if (venueName != null || venueCity != null) add(OpportunityFactKey.Location)
                },
            ),
        ),
    )
}

private fun String?.toFixtureStatus(): FixtureStatus =
    when (this?.uppercase()) {
        "TBD", "NS" -> FixtureStatus.Scheduled
        "1H", "HT", "2H", "ET", "BT", "P", "SUSP", "INT" -> FixtureStatus.Live
        "FT", "AET", "PEN" -> FixtureStatus.Finished
        "PST" -> FixtureStatus.Postponed
        "CANC", "ABD", "AWD", "WO" -> FixtureStatus.Cancelled
        else -> FixtureStatus.Unknown
    }

private const val MAX_TEAM_CHARS = 120
private const val MAX_COMPETITION_CHARS = 160
private const val MAX_LOCATION_CHARS = 160
