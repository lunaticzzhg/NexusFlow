package com.nexusflow.backend.feature.research.infrastructure.source.ticketmaster

import com.nexusflow.backend.feature.task.domain.AvailabilityFact
import com.nexusflow.backend.feature.task.domain.OpportunityFactKey
import com.nexusflow.backend.feature.task.domain.SourceAuthority
import com.nexusflow.backend.feature.task.domain.SourceRef
import com.nexusflow.backend.feature.task.domain.source.GeneralSportsEventCandidate
import com.nexusflow.backend.feature.task.domain.source.LiveMusicEventCandidate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

internal fun TicketmasterEventDto.toLiveMusicEventCandidate(observedAt: Instant): LiveMusicEventCandidate {
    val eventId = id?.trim()?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("Ticketmaster event id missing")
    val displayTitle = name?.trim()?.take(MAX_TITLE_CHARS)?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("Ticketmaster event name missing")
    val venue = embedded?.venues?.firstOrNull()
    val venueName = venue?.name?.boundedLocation()
    val city = venue?.city?.name.boundedLocation()
    return LiveMusicEventCandidate(
        externalEventId = eventId,
        title = displayTitle,
        artists = embedded?.attractions
            ?.mapNotNullTo(mutableSetOf()) { it.name?.trim()?.take(MAX_ARTIST_CHARS)?.takeIf(String::isNotBlank) }
            .orEmpty(),
        startsAt = startsAt() ?: throw IllegalArgumentException("Ticketmaster event start missing"),
        endsAt = dates?.end?.dateTime.toInstantOrNull(),
        venueName = venueName,
        city = city,
        latitude = venue?.location?.latitude.toCoordinateOrNull(-90.0, 90.0),
        longitude = venue?.location?.longitude.toCoordinateOrNull(-180.0, 180.0),
        publicUrl = url.boundedUrl(),
        availability = AvailabilityFact.Available,
        sources = listOf(sourceRef(eventId, observedAt, SourceAuthority.StructuredPrimary, venueName, city)),
    )
}

internal fun TicketmasterEventDto.toSportsEventCandidate(observedAt: Instant): GeneralSportsEventCandidate {
    val eventId = id?.trim()?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("Ticketmaster event id missing")
    val displayTitle = name?.trim()?.take(MAX_TITLE_CHARS)?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("Ticketmaster event name missing")
    val venue = embedded?.venues?.firstOrNull()
    val venueName = venue?.name?.boundedLocation()
    val city = venue?.city?.name.boundedLocation()
    return GeneralSportsEventCandidate(
        externalEventId = eventId,
        title = displayTitle,
        sportName = classifications
            .asSequence()
            .mapNotNull { it.genre?.name ?: it.subGenre?.name }
            .firstOrNull()
            ?.trim()
            ?.take(MAX_SPORT_CHARS)
            ?.takeIf(String::isNotBlank),
        startsAt = startsAt() ?: throw IllegalArgumentException("Ticketmaster event start missing"),
        endsAt = dates?.end?.dateTime.toInstantOrNull(),
        venueName = venueName,
        city = city,
        publicUrl = url.boundedUrl(),
        availability = AvailabilityFact.Available,
        sources = listOf(sourceRef(eventId, observedAt, SourceAuthority.StructuredPrimary, venueName, city)),
    )
}

private fun TicketmasterEventDto.sourceRef(
    eventId: String,
    observedAt: Instant,
    authority: SourceAuthority,
    venueName: String?,
    city: String?,
): SourceRef =
    SourceRef(
        label = "Ticketmaster",
        uri = "https://www.ticketmaster.com/event/$eventId",
        sourceUpdatedAt = observedAt,
        sourceId = "ticketmaster",
        authority = authority,
        factKeys = buildSet {
            add(OpportunityFactKey.Title)
            add(OpportunityFactKey.StartTime)
            add(OpportunityFactKey.EndTime)
            add(OpportunityFactKey.ActivityMode)
            add(OpportunityFactKey.Availability)
            add(OpportunityFactKey.LiveEventMetadata)
            if (venueName != null || city != null) add(OpportunityFactKey.Location)
        },
    )

private fun TicketmasterEventDto.startsAt(): Instant? =
    dates?.start?.dateTime.toInstantOrNull()
        ?: dates?.start?.localDate
            ?.takeIf(String::isNotBlank)
            ?.let { value -> runCatching { LocalDate.parse(value).atStartOfDay().toInstant(ZoneOffset.UTC) }.getOrNull() }

private fun String?.toInstantOrNull(): Instant? =
    this?.takeIf(String::isNotBlank)?.let { value ->
        runCatching { Instant.parse(value) }.getOrNull()
    }

private fun String?.toCoordinateOrNull(
    min: Double,
    max: Double,
): Double? =
    this?.toDoubleOrNull()?.takeIf { it in min..max }

private fun String?.boundedLocation(): String? =
    this?.trim()?.take(MAX_LOCATION_CHARS)?.takeIf(String::isNotBlank)

private fun String?.boundedUrl(): String? =
    this?.trim()?.take(MAX_URL_CHARS)?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

private const val MAX_TITLE_CHARS = 180
private const val MAX_LOCATION_CHARS = 160
private const val MAX_ARTIST_CHARS = 120
private const val MAX_SPORT_CHARS = 80
private const val MAX_URL_CHARS = 2_048
