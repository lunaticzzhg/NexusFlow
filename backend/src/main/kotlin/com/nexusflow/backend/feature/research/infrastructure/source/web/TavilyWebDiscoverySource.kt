package com.nexusflow.backend.feature.research.infrastructure.source.web

import com.nexusflow.backend.core.config.TavilyRuntimeConfig
import com.nexusflow.backend.core.external.ExternalSourceHttpClient
import com.nexusflow.backend.core.external.ExternalSourceInvalidPayloadException
import com.nexusflow.backend.core.external.ExternalSourceRateLimitedException
import com.nexusflow.backend.core.external.ExternalSourceTimeoutException
import com.nexusflow.backend.core.external.ExternalSourceUnauthorizedException
import com.nexusflow.backend.core.external.ExternalSourceUnavailableException
import com.nexusflow.backend.core.external.SourceCacheCodec
import com.nexusflow.backend.core.external.SourceCacheKey
import com.nexusflow.backend.core.external.SourceCacheResultKind
import com.nexusflow.backend.core.external.SourceCacheStore
import com.nexusflow.backend.core.external.SourceCacheTtlPolicy
import com.nexusflow.backend.feature.task.domain.source.WebDiscoverySource
import com.nexusflow.backend.feature.task.domain.source.WebExtractedPage
import com.nexusflow.backend.feature.task.domain.source.WebSearchHit
import com.nexusflow.backend.feature.task.domain.source.WebSearchQuery
import com.nexusflow.observability.StructuredLogger
import com.nexusflow.observability.logFields
import io.ktor.client.call.body
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant

class TavilyWebDiscoverySource(
    private val http: ExternalSourceHttpClient,
    private val config: TavilyRuntimeConfig,
    private val cacheStore: SourceCacheStore? = null,
    private val logger: StructuredLogger? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val cachePolicy: SourceCacheTtlPolicy = SourceCacheTtlPolicy(
        successTtl = Duration.ofMinutes(30),
        emptyTtl = Duration.ofMinutes(5),
    ),
) : WebDiscoverySource {
    override suspend fun search(request: WebSearchQuery): List<WebSearchHit> {
        val key = SourceCacheKey("source:v1:tavily:web-search:${sha256(request.canonicalCacheRequest())}")
        cacheStore?.get(key)?.let { bytes ->
            val hits = WebSearchHitListCacheCodec.decode(bytes)
            logAcquisition("search", "success", hits.size, cacheHit = true)
            return hits
        }

        val hits = execute("search") {
            val response = http.client.post("${config.baseUrl.trimEnd('/')}/search") {
                bearerAuth(config.apiKey)
                contentType(ContentType.Application.Json)
                setBody(TavilySearchRequest(query = request.query, maxResults = request.maxResults))
            }
            when (response.status) {
                HttpStatusCode.Unauthorized,
                HttpStatusCode.Forbidden,
                -> throw ExternalSourceUnauthorizedException(PROVIDER, "search")
                HttpStatusCode.TooManyRequests -> throw ExternalSourceRateLimitedException(PROVIDER, "search")
                in HttpStatusCode.InternalServerError..HttpStatusCode.GatewayTimeout ->
                    throw ExternalSourceUnavailableException(PROVIDER, "search")
                else -> {
                    if (response.status.value !in 200..299) throw ExternalSourceUnavailableException(PROVIDER, "search")
                    response.body<TavilySearchResponse>().toWebSearchHits()
                }
            }
        }

        cacheStore?.put(
            key = key,
            value = WebSearchHitListCacheCodec.encode(hits),
            ttl = cachePolicy.ttlFor(if (hits.isEmpty()) SourceCacheResultKind.SuccessEmpty else SourceCacheResultKind.Success),
        )
        logAcquisition("search", if (hits.isEmpty()) "empty" else "success", hits.size, cacheHit = false)
        return hits
    }

    override suspend fun extract(urls: List<String>): List<WebExtractedPage> {
        val canonicalUrls = urls.mapNotNull { it.trim().takeIf(String::isNotBlank) }.distinct().take(3)
        if (canonicalUrls.isEmpty()) return emptyList()

        val pages = mutableListOf<WebExtractedPage>()
        val missingUrls = mutableListOf<String>()
        canonicalUrls.forEach { url ->
            val key = SourceCacheKey("source:v1:tavily:web-extract:${sha256(url)}")
            val cached = cacheStore?.get(key)
            if (cached != null) {
                pages += WebExtractedPageCacheCodec.decode(cached)
                logAcquisition("extract", "success", 1, cacheHit = true)
            } else {
                missingUrls += url
            }
        }
        if (missingUrls.isEmpty()) return pages

        val fetched = execute("extract") {
            val response = http.client.post("${config.baseUrl.trimEnd('/')}/extract") {
                bearerAuth(config.apiKey)
                contentType(ContentType.Application.Json)
                setBody(TavilyExtractRequest(urls = missingUrls))
            }
            when (response.status) {
                HttpStatusCode.Unauthorized,
                HttpStatusCode.Forbidden,
                -> throw ExternalSourceUnauthorizedException(PROVIDER, "extract")
                HttpStatusCode.TooManyRequests -> throw ExternalSourceRateLimitedException(PROVIDER, "extract")
                in HttpStatusCode.InternalServerError..HttpStatusCode.GatewayTimeout ->
                    throw ExternalSourceUnavailableException(PROVIDER, "extract")
                else -> {
                    if (response.status.value !in 200..299) throw ExternalSourceUnavailableException(PROVIDER, "extract")
                    response.body<TavilyExtractResponse>().toWebExtractedPages(clock.instant())
                }
            }
        }
        fetched.forEach { page ->
            cacheStore?.put(
                key = SourceCacheKey("source:v1:tavily:web-extract:${sha256(page.url)}"),
                value = WebExtractedPageCacheCodec.encode(page),
                ttl = cachePolicy.successTtl,
            )
        }
        logAcquisition("extract", if (fetched.isEmpty()) "empty" else "success", fetched.size, cacheHit = false)
        return pages + fetched
    }

    private suspend fun <T> execute(
        operation: String,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: HttpRequestTimeoutException) {
            throw ExternalSourceTimeoutException(PROVIDER, operation, cause)
        } catch (cause: ConnectTimeoutException) {
            throw ExternalSourceTimeoutException(PROVIDER, operation, cause)
        } catch (cause: SocketTimeoutException) {
            throw ExternalSourceTimeoutException(PROVIDER, operation, cause)
        } catch (cause: ContentConvertException) {
            throw ExternalSourceInvalidPayloadException(PROVIDER, operation, cause)
        } catch (cause: SerializationException) {
            throw ExternalSourceInvalidPayloadException(PROVIDER, operation, cause)
        } catch (cause: IllegalArgumentException) {
            throw ExternalSourceInvalidPayloadException(PROVIDER, operation, cause)
        } catch (cause: IOException) {
            throw ExternalSourceUnavailableException(PROVIDER, operation, cause)
        }

    private fun TavilySearchResponse.toWebSearchHits(): List<WebSearchHit> {
        val projected = results ?: throw IllegalArgumentException("results missing")
        return projected
            .mapNotNull { result ->
                val title = result.title.toBoundedWebTitle()
                val url = result.url?.trim()?.take(MAX_URL_CHARS)
                if (title == null || url.isNullOrBlank()) {
                    null
                } else {
                    WebSearchHit(
                        title = title,
                        url = url,
                        content = result.content.toBoundedWebSnippet(),
                        score = result.score,
                    )
                }
            }
            .take(MAX_SEARCH_RESULTS)
    }

    private fun TavilyExtractResponse.toWebExtractedPages(observedAt: Instant): List<WebExtractedPage> {
        val projected = results ?: throw IllegalArgumentException("results missing")
        return projected
            .mapNotNull { result ->
                val url = result.url?.trim()?.take(MAX_URL_CHARS)
                val content = result.rawContent.toBoundedExtractedText()
                if (url.isNullOrBlank() || content == null) {
                    null
                } else {
                    WebExtractedPage(
                        url = url,
                        title = null,
                        content = content,
                        observedAt = observedAt,
                    )
                }
            }
            .take(MAX_EXTRACT_RESULTS)
    }

    private fun logAcquisition(
        operation: String,
        outcome: String,
        resultCount: Int,
        cacheHit: Boolean,
    ) {
        logger?.info(
            component = "external_source",
            event = "source_acquisition_finished",
            fields =
                logFields {
                    "source_id" value PROVIDER
                    "capability" value "web_discovery"
                    "operation" value operation
                    "outcome" value outcome
                    "result_count" value resultCount
                    "cache_mode" value (if (cacheStore == null) "none" else "memory")
                    "cache_hit" value cacheHit
                    "external_call_avoided" value cacheHit
                },
        )
    }

    private fun WebSearchQuery.canonicalCacheRequest(): String =
        "query=${query.lowercase().replace(Regex("\\s+"), " ").trim()}|maxResults=$maxResults"

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private object WebSearchHitListCacheCodec : SourceCacheCodec<List<WebSearchHit>> {
        override fun encode(value: List<WebSearchHit>): ByteArray =
            Json.encodeToString(
                WebSearchHitCacheDocument.serializer().let { documentSerializer ->
                    ListSerializer(documentSerializer)
                },
                value.map(WebSearchHitCacheDocument::from),
            ).toByteArray()

        override fun decode(bytes: ByteArray): List<WebSearchHit> =
            Json.decodeFromString(
                ListSerializer(WebSearchHitCacheDocument.serializer()),
                bytes.decodeToString(),
            ).map(WebSearchHitCacheDocument::toDomain)
    }

    private object WebExtractedPageCacheCodec : SourceCacheCodec<WebExtractedPage> {
        override fun encode(value: WebExtractedPage): ByteArray =
            Json.encodeToString(WebExtractedPageCacheDocument.serializer(), WebExtractedPageCacheDocument.from(value))
                .toByteArray()

        override fun decode(bytes: ByteArray): WebExtractedPage =
            Json.decodeFromString(WebExtractedPageCacheDocument.serializer(), bytes.decodeToString()).toDomain()
    }

    @Serializable
    private data class WebSearchHitCacheDocument(
        val title: String,
        val url: String,
        val content: String?,
        val score: Double?,
    ) {
        fun toDomain(): WebSearchHit = WebSearchHit(title, url, content, score)

        companion object {
            fun from(hit: WebSearchHit): WebSearchHitCacheDocument =
                WebSearchHitCacheDocument(hit.title, hit.url, hit.content, hit.score)
        }
    }

    @Serializable
    private data class WebExtractedPageCacheDocument(
        val url: String,
        val title: String?,
        val content: String,
        val observedAt: String,
    ) {
        fun toDomain(): WebExtractedPage = WebExtractedPage(url, title, content, Instant.parse(observedAt))

        companion object {
            fun from(page: WebExtractedPage): WebExtractedPageCacheDocument =
                WebExtractedPageCacheDocument(page.url, page.title, page.content, page.observedAt.toString())
        }
    }

    private companion object {
        const val PROVIDER = "tavily"
        const val MAX_SEARCH_RESULTS = 8
        const val MAX_EXTRACT_RESULTS = 3
        const val MAX_URL_CHARS = 2_048
    }
}
