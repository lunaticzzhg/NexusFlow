package com.nexusflow.backend.feature.research.infrastructure.source.apifootball

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class ApiFootballFixturesResponse(
    @SerialName("response")
    val response: List<ApiFootballFixtureItemDto>? = null,
)

@Serializable
internal data class ApiFootballFixtureItemDto(
    @SerialName("fixture")
    val fixture: ApiFootballFixtureDto? = null,
    @SerialName("league")
    val league: ApiFootballLeagueDto? = null,
    @SerialName("teams")
    val teams: ApiFootballTeamsDto? = null,
)

@Serializable
internal data class ApiFootballFixtureDto(
    @SerialName("id")
    val id: Long? = null,
    @SerialName("date")
    val date: String? = null,
    @SerialName("status")
    val status: ApiFootballStatusDto? = null,
    @SerialName("venue")
    val venue: ApiFootballVenueDto? = null,
)

@Serializable
internal data class ApiFootballStatusDto(
    @SerialName("short")
    val short: String? = null,
)

@Serializable
internal data class ApiFootballVenueDto(
    @SerialName("name")
    val name: String? = null,
    @SerialName("city")
    val city: String? = null,
)

@Serializable
internal data class ApiFootballLeagueDto(
    @SerialName("name")
    val name: String? = null,
)

@Serializable
internal data class ApiFootballTeamsDto(
    @SerialName("home")
    val home: ApiFootballTeamDto? = null,
    @SerialName("away")
    val away: ApiFootballTeamDto? = null,
)

@Serializable
internal data class ApiFootballTeamDto(
    @SerialName("name")
    val name: String? = null,
)
