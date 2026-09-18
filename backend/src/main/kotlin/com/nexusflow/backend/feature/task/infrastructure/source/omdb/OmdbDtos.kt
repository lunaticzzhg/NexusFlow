package com.nexusflow.backend.feature.task.infrastructure.source.omdb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class OmdbMovieResponse(
    @SerialName("Response")
    val response: String? = null,
    @SerialName("imdbID")
    val imdbId: String? = null,
    @SerialName("Title")
    val title: String? = null,
    @SerialName("Released")
    val released: String? = null,
    @SerialName("Runtime")
    val runtime: String? = null,
    @SerialName("Genre")
    val genre: String? = null,
    @SerialName("Plot")
    val plot: String? = null,
)

@Serializable
internal data class OmdbSearchResponse(
    @SerialName("Response")
    val response: String? = null,
    @SerialName("Search")
    val search: List<OmdbSearchResultDto>? = null,
)

@Serializable
internal data class OmdbSearchResultDto(
    @SerialName("imdbID")
    val imdbId: String? = null,
)
