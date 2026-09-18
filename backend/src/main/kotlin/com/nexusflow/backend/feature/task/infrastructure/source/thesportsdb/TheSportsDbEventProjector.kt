package com.nexusflow.backend.feature.task.infrastructure.source.thesportsdb

import com.nexusflow.backend.feature.task.domain.OpportunityFactKey
import com.nexusflow.backend.feature.task.domain.SourceAuthority
import com.nexusflow.backend.feature.task.domain.SourceRef
import com.nexusflow.backend.feature.task.domain.source.GeneralSportsEventCandidate
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

internal fun TheSportsDbEventDto.toGeneralSportsEventCandidate(observedAt: Instant): GeneralSportsEventCandidate {
    val eventId = idEvent?.trim()?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("TheSportsDB event id missing")
    val displayTitle = eventName?.trim()?.take(MAX_TITLE_CHARS)?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("TheSportsDB event name missing")
    val startsAt = parseStartInstant(timestamp, dateEvent, time)
        ?: throw IllegalArgumentException("TheSportsDB event start missing")
    val venueName = venue.boundedLocation()
    val cityName = city.boundedLocation() ?: country.boundedLocation()
    return GeneralSportsEventCandidate(
        externalEventId = eventId,
        title = displayTitle,
        sportName = sportName?.trim()?.take(MAX_SPORT_CHARS)?.takeIf(String::isNotBlank),
        startsAt = startsAt,
        endsAt = null,
        venueName = venueName,
        city = cityName,
        publicUrl = "https://www.thesportsdb.com/event/$eventId",
        availability = null,
        sources = listOf(
            SourceRef(
                label = "TheSportsDB",
                uri = "https://www.thesportsdb.com/event/$eventId",
                sourceUpdatedAt = observedAt,
                sourceId = "thesportsdb",
                authority = SourceAuthority.StructuredSecondary,
                factKeys = buildSet {
                    add(OpportunityFactKey.Title)
                    add(OpportunityFactKey.StartTime)
                    add(OpportunityFactKey.ActivityMode)
                    add(OpportunityFactKey.LiveEventMetadata)
                    if (venueName != null || cityName != null) add(OpportunityFactKey.Location)
                },
            ),
        ),
    )
}

private fun parseStartInstant(
    timestamp: String?,
    date: String?,
    time: String?,
): Instant? =
    timestamp?.takeIf(String::isNotBlank)?.let { value ->
        runCatching { Instant.parse(value) }.getOrNull()
    } ?: parseLocalDateTime(date, time)

private fun parseLocalDateTime(
    date: String?,
    time: String?,
): Instant? {
    val localDate = date?.takeIf(String::isNotBlank)?.let { value ->
        runCatching { LocalDate.parse(value) }.getOrNull()
    } ?: return null
    val localTime = time?.takeIf(String::isNotBlank)?.let { value ->
        runCatching { LocalTime.parse(value.substringBefore("+")) }.getOrNull()
    } ?: LocalTime.MIDNIGHT
    return localDate.atTime(localTime).toInstant(ZoneOffset.UTC)
}

private fun String?.boundedLocation(): String? =
    this?.trim()?.take(MAX_LOCATION_CHARS)?.takeIf(String::isNotBlank)

private const val MAX_TITLE_CHARS = 180
private const val MAX_LOCATION_CHARS = 160
private const val MAX_SPORT_CHARS = 80
