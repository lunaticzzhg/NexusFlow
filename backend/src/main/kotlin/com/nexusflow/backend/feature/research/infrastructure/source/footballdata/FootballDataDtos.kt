package com.nexusflow.backend.feature.research.infrastructure.source.footballdata

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class FootballDataMatchesResponse(
    @SerialName("matches")
    val matches: List<FootballDataMatchDto>? = null,
)

@Serializable
internal data class FootballDataMatchDto(
    @SerialName("id")
    val id: Long? = null,
    @SerialName("utcDate")
    val utcDate: String? = null,
    @SerialName("status")
    val status: String? = null,
    @SerialName("competition")
    val competition: FootballDataNamedDto? = null,
    @SerialName("homeTeam")
    val homeTeam: FootballDataNamedDto? = null,
    @SerialName("awayTeam")
    val awayTeam: FootballDataNamedDto? = null,
    @SerialName("venue")
    val venue: String? = null,
)

@Serializable
internal data class FootballDataNamedDto(
    @SerialName("name")
    val name: String? = null,
)
