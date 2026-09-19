package com.nexusflow.backend.feature.research.infrastructure.source.musicbrainz

import com.nexusflow.backend.feature.task.domain.OpportunityFactKey
import com.nexusflow.backend.feature.task.domain.SourceAuthority
import com.nexusflow.backend.feature.task.domain.SourceRef
import com.nexusflow.backend.feature.task.domain.source.MusicMetadataCandidate
import com.nexusflow.backend.feature.task.domain.source.MusicMetadataSearchType
import java.time.Instant
import java.time.LocalDate

internal fun MusicBrainzArtistDto.toMusicMetadataCandidate(observedAt: Instant): MusicMetadataCandidate {
    val artistId = id.cleaned() ?: throw IllegalArgumentException("MusicBrainz artist id missing")
    val displayName = name.cleaned(MAX_TITLE_CHARS) ?: throw IllegalArgumentException("MusicBrainz artist name missing")
    return MusicMetadataCandidate(
        externalId = artistId,
        type = MusicMetadataSearchType.Artist,
        title = displayName,
        artists = setOf(displayName),
        date = lifeSpan?.begin.toLocalDateOrNull(),
        countryCode = country.cleaned(MAX_COUNTRY_CHARS),
        status = type.cleaned(MAX_STATUS_CHARS),
        disambiguation = disambiguation.cleaned(MAX_DISAMBIGUATION_CHARS),
        lengthMillis = null,
        source = musicBrainzSourceRef(
            entityType = "artist",
            entityId = artistId,
            observedAt = observedAt,
        ),
    )
}

internal fun MusicBrainzReleaseDto.toMusicMetadataCandidate(observedAt: Instant): MusicMetadataCandidate {
    val releaseId = id.cleaned() ?: throw IllegalArgumentException("MusicBrainz release id missing")
    val displayTitle = title.cleaned(MAX_TITLE_CHARS) ?: throw IllegalArgumentException("MusicBrainz release title missing")
    return MusicMetadataCandidate(
        externalId = releaseId,
        type = MusicMetadataSearchType.Release,
        title = displayTitle,
        artists = artistCredit.artistNames(),
        date = date.toLocalDateOrNull(),
        countryCode = country.cleaned(MAX_COUNTRY_CHARS),
        status = status.cleaned(MAX_STATUS_CHARS),
        disambiguation = disambiguation.cleaned(MAX_DISAMBIGUATION_CHARS),
        lengthMillis = null,
        source = musicBrainzSourceRef(
            entityType = "release",
            entityId = releaseId,
            observedAt = observedAt,
        ),
    )
}

internal fun MusicBrainzRecordingDto.toMusicMetadataCandidate(observedAt: Instant): MusicMetadataCandidate {
    val recordingId = id.cleaned() ?: throw IllegalArgumentException("MusicBrainz recording id missing")
    val displayTitle = title.cleaned(MAX_TITLE_CHARS) ?: throw IllegalArgumentException("MusicBrainz recording title missing")
    val firstRelease = releases.firstOrNull()
    return MusicMetadataCandidate(
        externalId = recordingId,
        type = MusicMetadataSearchType.Recording,
        title = displayTitle,
        artists = artistCredit.artistNames(),
        date = firstRelease?.date.toLocalDateOrNull(),
        countryCode = firstRelease?.country.cleaned(MAX_COUNTRY_CHARS),
        status = null,
        disambiguation = disambiguation.cleaned(MAX_DISAMBIGUATION_CHARS),
        lengthMillis = length?.takeIf { it > 0 },
        source = musicBrainzSourceRef(
            entityType = "recording",
            entityId = recordingId,
            observedAt = observedAt,
        ),
    )
}

private fun List<MusicBrainzArtistCreditDto>.artistNames(): Set<String> =
    mapNotNullTo(linkedSetOf()) { credit ->
        (credit.artist?.name ?: credit.name).cleaned(MAX_ARTIST_CHARS)
    }

private fun musicBrainzSourceRef(
    entityType: String,
    entityId: String,
    observedAt: Instant,
): SourceRef =
    SourceRef(
        label = "MusicBrainz",
        uri = "https://musicbrainz.org/$entityType/$entityId",
        sourceUpdatedAt = observedAt,
        sourceId = "musicbrainz",
        authority = SourceAuthority.StructuredPrimary,
        factKeys = setOf(
            OpportunityFactKey.Title,
            OpportunityFactKey.Summary,
        ),
    )

private fun String?.cleaned(maxChars: Int = Int.MAX_VALUE): String? =
    this?.trim()?.take(maxChars)?.takeIf(String::isNotBlank)

private fun String?.toLocalDateOrNull(): LocalDate? =
    cleaned()?.let { value ->
        runCatching {
            LocalDate.parse(
                when (value.count { it == '-' }) {
                    0 -> "$value-01-01"
                    1 -> "$value-01"
                    else -> value
                },
            )
        }.getOrNull()
    }

private const val MAX_TITLE_CHARS = 180
private const val MAX_ARTIST_CHARS = 120
private const val MAX_COUNTRY_CHARS = 8
private const val MAX_STATUS_CHARS = 80
private const val MAX_DISAMBIGUATION_CHARS = 180
