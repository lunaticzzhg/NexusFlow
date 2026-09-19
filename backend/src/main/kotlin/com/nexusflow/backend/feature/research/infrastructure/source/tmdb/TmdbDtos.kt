package com.nexusflow.backend.feature.research.infrastructure.source.tmdb

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class TmdbSearchResponse(
    @SerialName("results")
    val results: List<TmdbMovieSummaryDto>? = null,
)

@Serializable
internal data class TmdbMovieSummaryDto(
    @SerialName("id")
    val id: Int? = null,
    @SerialName("title")
    val title: String? = null,
    @SerialName("original_title")
    val originalTitle: String? = null,
    @SerialName("release_date")
    val releaseDate: String? = null,
    @SerialName("genre_ids")
    val genreIds: List<Int> = emptyList(),
    @SerialName("overview")
    val overview: String? = null,
    @SerialName("popularity")
    val popularity: Double? = null,
)

@Serializable
internal data class TmdbMovieDetailDto(
    @SerialName("runtime")
    val runtime: Int? = null,
    @SerialName("genres")
    val genres: List<TmdbGenreDto> = emptyList(),
)

@Serializable
internal data class TmdbGenreDto(
    @SerialName("name")
    val name: String? = null,
)
