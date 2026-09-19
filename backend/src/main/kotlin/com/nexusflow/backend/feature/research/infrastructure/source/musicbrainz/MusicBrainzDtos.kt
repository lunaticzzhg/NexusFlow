package com.nexusflow.backend.feature.research.infrastructure.source.musicbrainz

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class MusicBrainzEventSearchResponse(
    @SerialName("events")
    val events: List<MusicBrainzEventDto> = emptyList(),
)

@Serializable
internal data class MusicBrainzEventDto(
    @SerialName("id")
    val id: String? = null,
    @SerialName("name")
    val name: String? = null,
    @SerialName("type")
    val type: String? = null,
    @SerialName("time")
    val time: String? = null,
    @SerialName("life-span")
    val lifeSpan: MusicBrainzLifeSpanDto? = null,
    @SerialName("relations")
    val relations: List<MusicBrainzRelationDto> = emptyList(),
)

@Serializable
internal data class MusicBrainzLifeSpanDto(
    @SerialName("begin")
    val begin: String? = null,
    @SerialName("end")
    val end: String? = null,
)

@Serializable
internal data class MusicBrainzRelationDto(
    @SerialName("type")
    val type: String? = null,
    @SerialName("place")
    val place: MusicBrainzNamedEntityDto? = null,
    @SerialName("area")
    val area: MusicBrainzNamedEntityDto? = null,
    @SerialName("artist")
    val artist: MusicBrainzNamedEntityDto? = null,
)

@Serializable
internal data class MusicBrainzNamedEntityDto(
    @SerialName("name")
    val name: String? = null,
)

@Serializable
internal data class MusicBrainzArtistSearchResponse(
    @SerialName("artists")
    val artists: List<MusicBrainzArtistDto> = emptyList(),
)

@Serializable
internal data class MusicBrainzArtistDto(
    @SerialName("id")
    val id: String? = null,
    @SerialName("name")
    val name: String? = null,
    @SerialName("type")
    val type: String? = null,
    @SerialName("country")
    val country: String? = null,
    @SerialName("disambiguation")
    val disambiguation: String? = null,
    @SerialName("life-span")
    val lifeSpan: MusicBrainzLifeSpanDto? = null,
)

@Serializable
internal data class MusicBrainzReleaseSearchResponse(
    @SerialName("releases")
    val releases: List<MusicBrainzReleaseDto> = emptyList(),
)

@Serializable
internal data class MusicBrainzReleaseDto(
    @SerialName("id")
    val id: String? = null,
    @SerialName("title")
    val title: String? = null,
    @SerialName("date")
    val date: String? = null,
    @SerialName("country")
    val country: String? = null,
    @SerialName("status")
    val status: String? = null,
    @SerialName("disambiguation")
    val disambiguation: String? = null,
    @SerialName("artist-credit")
    val artistCredit: List<MusicBrainzArtistCreditDto> = emptyList(),
)

@Serializable
internal data class MusicBrainzRecordingSearchResponse(
    @SerialName("recordings")
    val recordings: List<MusicBrainzRecordingDto> = emptyList(),
)

@Serializable
internal data class MusicBrainzRecordingDto(
    @SerialName("id")
    val id: String? = null,
    @SerialName("title")
    val title: String? = null,
    @SerialName("length")
    val length: Long? = null,
    @SerialName("disambiguation")
    val disambiguation: String? = null,
    @SerialName("artist-credit")
    val artistCredit: List<MusicBrainzArtistCreditDto> = emptyList(),
    @SerialName("releases")
    val releases: List<MusicBrainzRecordingReleaseDto> = emptyList(),
)

@Serializable
internal data class MusicBrainzRecordingReleaseDto(
    @SerialName("date")
    val date: String? = null,
    @SerialName("country")
    val country: String? = null,
)

@Serializable
internal data class MusicBrainzArtistCreditDto(
    @SerialName("name")
    val name: String? = null,
    @SerialName("artist")
    val artist: MusicBrainzArtistCreditArtistDto? = null,
)

@Serializable
internal data class MusicBrainzArtistCreditArtistDto(
    @SerialName("name")
    val name: String? = null,
)
