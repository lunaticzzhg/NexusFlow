package com.nexusflow.backend.feature.research.infrastructure.source.omdb

import com.nexusflow.backend.feature.task.domain.OpportunityFactKey
import com.nexusflow.backend.feature.task.domain.SourceAuthority
import com.nexusflow.backend.feature.task.domain.SourceRef
import com.nexusflow.backend.feature.task.domain.source.MovieMetadataCandidate
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun OmdbMovieResponse.toMovieMetadataCandidate(observedAt: Instant): MovieMetadataCandidate? {
    if (response.equals("False", ignoreCase = true)) return null
    val id = imdbId?.trim()?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("OMDb imdbID missing")
    val displayTitle = title?.trim()?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("OMDb title missing")
    return MovieMetadataCandidate(
        externalMovieId = id,
        title = displayTitle.take(MAX_TITLE_CHARS),
        releaseDate = released.toReleaseDateOrNull(),
        runtimeMinutes = runtime.toRuntimeMinutesOrNull(),
        genres = genre
            ?.split(",")
            ?.mapNotNullTo(mutableSetOf()) { it.trim().take(MAX_GENRE_CHARS).takeIf(String::isNotBlank) }
            ?: emptySet(),
        summary = plot?.trim()?.take(MAX_SUMMARY_CHARS)?.takeIf { it.isNotBlank() && it != "N/A" },
        source = SourceRef(
            label = "OMDb",
            uri = "https://www.imdb.com/title/$id/",
            sourceUpdatedAt = observedAt,
            sourceId = "omdb",
            authority = SourceAuthority.StructuredSecondary,
            factKeys = setOf(OpportunityFactKey.Title, OpportunityFactKey.Summary, OpportunityFactKey.MovieMetadata),
        ),
    )
}

private fun String?.toRuntimeMinutesOrNull(): Int? =
    this
        ?.lowercase(Locale.ROOT)
        ?.substringBefore("min")
        ?.trim()
        ?.toIntOrNull()
        ?.takeIf { it > 0 }

private fun String?.toReleaseDateOrNull(): LocalDate? =
    this?.takeIf { it.isNotBlank() && it != "N/A" }?.let { value ->
        runCatching { LocalDate.parse(value, DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.US)) }.getOrNull()
    }

private const val MAX_TITLE_CHARS = 160
private const val MAX_SUMMARY_CHARS = 800
private const val MAX_GENRE_CHARS = 80
