package com.nexusflow.backend.feature.task.domain.source

import com.nexusflow.backend.feature.task.domain.SourceRef
import java.time.LocalDate

interface MovieMetadataSource {
    suspend fun search(query: MovieMetadataQuery): List<MovieMetadataCandidate>
}

data class MovieMetadataQuery(
    val title: String,
    val region: String? = null,
    val language: String? = null,
) {
    init {
        require(title.isNotBlank()) { "title must not be blank" }
    }
}

data class MovieMetadataCandidate(
    val externalMovieId: String,
    val title: String,
    val releaseDate: LocalDate?,
    val runtimeMinutes: Int?,
    val genres: Set<String>,
    val summary: String?,
    val source: SourceRef,
)
