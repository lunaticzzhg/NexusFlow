package com.nexusflow.backend.feature.task.infrastructure.source.web

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class TavilySearchRequest(
    @SerialName("query")
    val query: String,
    @SerialName("search_depth")
    val searchDepth: String = "basic",
    @SerialName("max_results")
    val maxResults: Int,
    @SerialName("include_answer")
    val includeAnswer: Boolean = false,
    @SerialName("include_images")
    val includeImages: Boolean = false,
    @SerialName("include_raw_content")
    val includeRawContent: Boolean = false,
)

@Serializable
internal data class TavilySearchResponse(
    @SerialName("results")
    val results: List<TavilySearchResult>? = null,
)

@Serializable
internal data class TavilySearchResult(
    @SerialName("title")
    val title: String? = null,
    @SerialName("url")
    val url: String? = null,
    @SerialName("content")
    val content: String? = null,
    @SerialName("score")
    val score: Double? = null,
)

@Serializable
internal data class TavilyExtractRequest(
    @SerialName("urls")
    val urls: List<String>,
    @SerialName("include_images")
    val includeImages: Boolean = false,
    @SerialName("extract_depth")
    val extractDepth: String = "basic",
)

@Serializable
internal data class TavilyExtractResponse(
    @SerialName("results")
    val results: List<TavilyExtractResult>? = null,
)

@Serializable
internal data class TavilyExtractResult(
    @SerialName("url")
    val url: String? = null,
    @SerialName("raw_content")
    val rawContent: String? = null,
)
