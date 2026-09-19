package com.nexusflow.backend.feature.research.infrastructure.source.thesportsdb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class TheSportsDbEventSearchResponse(
    @SerialName("event")
    val events: List<TheSportsDbEventDto>? = null,
)

@Serializable
internal data class TheSportsDbEventDto(
    @SerialName("idEvent")
    val idEvent: String? = null,
    @SerialName("strEvent")
    val eventName: String? = null,
    @SerialName("strSport")
    val sportName: String? = null,
    @SerialName("strTimestamp")
    val timestamp: String? = null,
    @SerialName("dateEvent")
    val dateEvent: String? = null,
    @SerialName("strTime")
    val time: String? = null,
    @SerialName("strVenue")
    val venue: String? = null,
    @SerialName("strCity")
    val city: String? = null,
    @SerialName("strCountry")
    val country: String? = null,
    @SerialName("strLeague")
    val league: String? = null,
)
