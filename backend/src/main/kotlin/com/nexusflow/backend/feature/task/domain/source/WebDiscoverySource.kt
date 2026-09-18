package com.nexusflow.backend.feature.task.domain.source

import java.time.Instant

interface WebDiscoverySource {
    suspend fun search(request: WebSearchQuery): List<WebSearchHit>

    suspend fun extract(urls: List<String>): List<WebExtractedPage>
}

data class WebSearchQuery(
    val query: String,
    val maxResults: Int = 5,
) {
    init {
        require(query.isNotBlank()) { "query must not be blank" }
        require(maxResults in 1..8) { "maxResults must be between 1 and 8" }
    }
}

data class WebSearchHit(
    val title: String,
    val url: String,
    val content: String?,
    val score: Double?,
)

data class WebExtractedPage(
    val url: String,
    val title: String?,
    val content: String,
    val observedAt: Instant,
)
