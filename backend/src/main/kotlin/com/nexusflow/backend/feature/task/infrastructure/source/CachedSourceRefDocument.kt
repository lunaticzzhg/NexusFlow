package com.nexusflow.backend.feature.task.infrastructure.source

import com.nexusflow.backend.feature.task.domain.OpportunityFactKey
import com.nexusflow.backend.feature.task.domain.SourceAuthority
import com.nexusflow.backend.feature.task.domain.SourceRef
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
internal data class CachedSourceRefDocument(
    val label: String,
    val uri: String?,
    val sourceUpdatedAt: String?,
    val sourceId: String,
    val authority: String,
    val factKeys: List<String>,
) {
    fun toDomain(): SourceRef =
        SourceRef(
            label = label,
            uri = uri,
            sourceUpdatedAt = sourceUpdatedAt?.let(Instant::parse),
            sourceId = sourceId,
            authority = SourceAuthority.valueOf(authority),
            factKeys = factKeys.mapTo(mutableSetOf()) { OpportunityFactKey.valueOf(it) },
        )

    companion object {
        fun from(source: SourceRef): CachedSourceRefDocument =
            CachedSourceRefDocument(
                label = source.label,
                uri = source.uri,
                sourceUpdatedAt = source.sourceUpdatedAt?.toString(),
                sourceId = source.sourceId,
                authority = source.authority.name,
                factKeys = source.factKeys.map { it.name }.sorted(),
            )
    }
}
