package com.nexusflow.backend.feature.research.infrastructure.source.tmdb

import com.nexusflow.backend.feature.task.domain.OpportunityFactKey
import com.nexusflow.backend.feature.task.domain.SourceAuthority
import com.nexusflow.backend.feature.task.domain.SourceRef
import com.nexusflow.backend.feature.task.domain.source.MovieDiscoveryCandidate
import com.nexusflow.backend.feature.task.domain.source.MovieMetadataCandidate
import java.time.Instant
import java.time.LocalDate

internal fun TmdbMovieSummaryDto.toMovieMetadataCandidate(
    detail: TmdbMovieDetailDto?,
    observedAt: Instant,
): MovieMetadataCandidate {
    val movieId = id ?: throw IllegalArgumentException("TMDB movie id missing")
    val displayTitle = title?.trim()?.takeIf(String::isNotBlank)
        ?: originalTitle?.trim()?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("TMDB movie title missing")
    return MovieMetadataCandidate(
        externalMovieId = movieId.toString(),
        title = displayTitle.take(MAX_TITLE_CHARS),
        releaseDate = releaseDate.toLocalDateOrNull(),
        runtimeMinutes = detail?.runtime?.takeIf { it > 0 },
        genres = detail?.genres
            ?.mapNotNullTo(mutableSetOf()) { it.name?.trim()?.take(MAX_GENRE_CHARS)?.takeIf(String::isNotBlank) }
            ?: genreIds.mapTo(mutableSetOf()) { "tmdb:$it" },
        summary = overview?.trim()?.take(MAX_SUMMARY_CHARS)?.takeIf(String::isNotBlank),
        source = SourceRef(
            label = "TMDB",
            uri = "https://www.themoviedb.org/movie/$movieId",
            sourceUpdatedAt = observedAt,
            sourceId = "tmdb",
            authority = SourceAuthority.StructuredPrimary,
            factKeys = setOf(OpportunityFactKey.Title, OpportunityFactKey.Summary, OpportunityFactKey.MovieMetadata),
        ),
    )
}

internal fun TmdbMovieSummaryDto.toMovieDiscoveryCandidate(
    observedAt: Instant,
): MovieDiscoveryCandidate {
    val movieId = id ?: throw IllegalArgumentException("TMDB movie id missing")
    val displayTitle = title?.trim()?.takeIf(String::isNotBlank)
        ?: originalTitle?.trim()?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("TMDB movie title missing")
    return MovieDiscoveryCandidate(
        externalMovieId = movieId.toString(),
        title = displayTitle.take(MAX_TITLE_CHARS),
        originalTitle = originalTitle?.trim()?.take(MAX_TITLE_CHARS)?.takeIf(String::isNotBlank),
        releaseDate = releaseDate.toLocalDateOrNull(),
        summary = overview?.trim()?.take(MAX_SUMMARY_CHARS)?.takeIf(String::isNotBlank),
        genreIds = genreIds.map { "tmdb:$it" },
        popularity = popularity,
        publicUrl = "https://www.themoviedb.org/movie/$movieId",
        source = SourceRef(
            label = "TMDB",
            uri = "https://www.themoviedb.org/movie/$movieId",
            sourceUpdatedAt = observedAt,
            sourceId = "tmdb",
            authority = SourceAuthority.StructuredPrimary,
            factKeys = setOf(OpportunityFactKey.Title, OpportunityFactKey.Summary, OpportunityFactKey.MovieMetadata),
        ),
    )
}

private fun String?.toLocalDateOrNull(): LocalDate? =
    this?.takeIf(String::isNotBlank)?.let { value ->
        runCatching { LocalDate.parse(value) }.getOrNull()
    }

private const val MAX_TITLE_CHARS = 160
private const val MAX_SUMMARY_CHARS = 800
private const val MAX_GENRE_CHARS = 80
