package com.nexusflow.backend.feature.task.infrastructure.source.musicbrainz

import com.nexusflow.backend.feature.task.domain.OpportunityFactKey
import com.nexusflow.backend.feature.task.domain.SourceAuthority
import com.nexusflow.backend.feature.task.domain.SourceRef
import com.nexusflow.backend.feature.task.domain.source.LiveMusicEventCandidate
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

internal fun MusicBrainzEventDto.toLiveMusicEventCandidate(observedAt: Instant): LiveMusicEventCandidate {
    val eventId = id?.trim()?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("MusicBrainz event id missing")
    val displayTitle = name?.trim()?.take(MAX_TITLE_CHARS)?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("MusicBrainz event name missing")
    val place = relations.firstNotNullOfOrNull { it.place?.name?.boundedLocation() }
    val area = relations.firstNotNullOfOrNull { it.area?.name?.boundedLocation() }
    return LiveMusicEventCandidate(
        externalEventId = eventId,
        title = displayTitle,
        artists = relations
            .mapNotNullTo(mutableSetOf()) { it.artist?.name?.trim()?.take(MAX_ARTIST_CHARS)?.takeIf(String::isNotBlank) },
        startsAt = parseBeginInstant(lifeSpan?.begin, time)
            ?: throw IllegalArgumentException("MusicBrainz event start missing"),
        endsAt = parseEndInstant(lifeSpan?.end),
        venueName = place,
        city = area,
        latitude = null,
        longitude = null,
        publicUrl = "https://musicbrainz.org/event/$eventId",
        availability = null,
        sources = listOf(
            SourceRef(
                label = "MusicBrainz",
                uri = "https://musicbrainz.org/event/$eventId",
                sourceUpdatedAt = observedAt,
                sourceId = "musicbrainz",
                authority = SourceAuthority.StructuredSecondary,
                factKeys = buildSet {
                    add(OpportunityFactKey.Title)
                    add(OpportunityFactKey.StartTime)
                    add(OpportunityFactKey.EndTime)
                    add(OpportunityFactKey.LiveEventMetadata)
                    if (place != null || area != null) add(OpportunityFactKey.Location)
                },
            ),
        ),
    )
}

private fun parseBeginInstant(
    date: String?,
    time: String?,
): Instant? {
    val localDate = date?.takeIf(String::isNotBlank)?.let { value ->
        runCatching { LocalDate.parse(value) }.getOrNull()
    } ?: return null
    val localTime = time?.takeIf(String::isNotBlank)?.let { value ->
        runCatching { LocalTime.parse(value) }.getOrNull()
    } ?: LocalTime.MIDNIGHT
    return localDate.atTime(localTime).toInstant(ZoneOffset.UTC)
}

private fun parseEndInstant(date: String?): Instant? =
    date?.takeIf(String::isNotBlank)?.let { value ->
        runCatching { LocalDate.parse(value).plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC) }.getOrNull()
    }

private fun String?.boundedLocation(): String? =
    this?.trim()?.take(MAX_LOCATION_CHARS)?.takeIf(String::isNotBlank)

private const val MAX_TITLE_CHARS = 180
private const val MAX_LOCATION_CHARS = 160
private const val MAX_ARTIST_CHARS = 120
