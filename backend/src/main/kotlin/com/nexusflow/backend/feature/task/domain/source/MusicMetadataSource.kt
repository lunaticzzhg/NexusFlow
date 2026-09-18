package com.nexusflow.backend.feature.task.domain.source

import com.nexusflow.backend.feature.task.domain.SourceRef
import java.time.LocalDate

interface MusicMetadataSource {
    suspend fun search(query: MusicMetadataQuery): List<MusicMetadataCandidate>
}

data class MusicMetadataQuery(
    val query: String,
    val type: MusicMetadataSearchType? = null,
    val maxResults: Int = 5,
) {
    init {
        require(query.isNotBlank()) { "music metadata query must not be blank" }
        require(maxResults in 1..8) { "music metadata maxResults must be between 1 and 8" }
    }
}

enum class MusicMetadataSearchType {
    Artist,
    Release,
    Recording,
}

data class MusicMetadataCandidate(
    val externalId: String,
    val type: MusicMetadataSearchType,
    val title: String,
    val artists: Set<String>,
    val date: LocalDate?,
    val countryCode: String?,
    val status: String?,
    val disambiguation: String?,
    val lengthMillis: Long?,
    val source: SourceRef,
)
