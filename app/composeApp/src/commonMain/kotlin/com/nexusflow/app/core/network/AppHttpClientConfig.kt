package com.nexusflow.app.core.network

import com.nexusflow.app.core.observability.AppLogger
import com.nexusflow.app.core.observability.AppTraceManager
import com.nexusflow.app.core.observability.LogTag
import com.nexusflow.app.core.observability.logFields
import com.nexusflow.observability.RandomTraceIdGenerator
import com.nexusflow.observability.TraceContextElement
import com.nexusflow.observability.TraceContextStorage
import com.nexusflow.observability.TraceHeaders
import com.nexusflow.observability.TraceId
import com.nexusflow.observability.TraceIdGenerator
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.Sender
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext
import kotlin.time.TimeSource

fun <T : HttpClientEngineConfig> HttpClientConfig<T>.configureAppHttpClient() {
    expectSuccess = false

    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            },
        )
    }
    install(HttpTimeout) {
        requestTimeoutMillis = NetworkDefaults.requestTimeout.inWholeMilliseconds
        connectTimeoutMillis = NetworkDefaults.connectTimeout.inWholeMilliseconds
        socketTimeoutMillis = NetworkDefaults.socketTimeout.inWholeMilliseconds
    }
    install(SSE)
}

/** Converts non-2xx responses from the configured first-party API into a stable transport error. */
internal fun HttpClient.installFirstPartyHttpStatusFailureInterceptor(apiBaseUrl: String) {
    installFirstPartyHttpInterceptors(apiBaseUrl, logger = null) { null }
}

internal fun HttpClient.installFirstPartyHttpInterceptors(
    apiBaseUrl: String,
    logger: AppLogger?,
    traceManager: AppTraceManager? = null,
    traceIdGenerator: TraceIdGenerator = RandomTraceIdGenerator,
    sessionProvider: () -> FirstPartyApiSession?,
) {
    val apiOrigin = Url(apiBaseUrl)
    plugin(HttpSend).intercept { request ->
        val requestUrl = request.url.build()
        if (!requestUrl.hasSameOrigin(apiOrigin)) {
            return@intercept execute(request)
        }

        val traceId =
            traceManager?.currentTraceId()
                ?: coroutineContext[TraceContextElement]?.traceId
                ?: request.headers[TraceHeaders.TraceId]
                    ?.takeIf(TraceId::isValid)
                    ?.let(TraceId::requireValid)
                ?: traceIdGenerator.newTraceId()
        request.headers.remove(TraceHeaders.TraceId)
        request.headers.append(TraceHeaders.TraceId, traceId.value)

        suspend fun executeWithTrace(): io.ktor.client.call.HttpClientCall {
            suspend fun executeScoped(): io.ktor.client.call.HttpClientCall =
                executeFirstPartyRequest(
                    request = request,
                    traceId = traceId,
                    logger = logger,
                    sessionProvider = sessionProvider,
                )
            return if (traceManager != null) {
                traceManager.withTrace(traceId) { executeScoped() }
            } else {
                withFallbackTrace(traceId) { executeScoped() }
            }
        }

        executeWithTrace()
    }
}

private suspend fun <T> withFallbackTrace(
    traceId: TraceId,
    block: suspend () -> T,
): T =
    withContext(TraceContextElement(traceId)) {
        val previousTraceId = TraceContextStorage.replace(traceId)
        try {
            block()
        } finally {
            TraceContextStorage.replace(previousTraceId)
        }
    }

private suspend fun Sender.executeFirstPartyRequest(
    request: HttpRequestBuilder,
    traceId: TraceId,
    logger: AppLogger?,
    sessionProvider: () -> FirstPartyApiSession?,
): io.ktor.client.call.HttpClientCall {
    val method = request.method.value
    val requestUrl = request.url.build()
    val path = requestUrl.safeHttpPath()
    val started = TimeSource.Monotonic.markNow()
    var finalStatus: Int? = null
    var replayed = false

    logger?.info(
        tag = HttpLogTag,
        event = "http_request_started",
        fields =
            logFields {
                "http_method" value method
                "http_path" value path
            },
    )

    try {
        val authenticationMode = request.attributes.getOrNull(ApiAuthenticationModeAttribute) ?: ApiAuthenticationMode.Automatic
        val isProtectedRequest = authenticationMode == ApiAuthenticationMode.Automatic && !requestUrl.isPublicAuthEndpoint()
        val provider = if (isProtectedRequest) sessionProvider() else null
        val firstToken = provider?.currentAccessToken()
        if (firstToken != null) {
            request.headers.remove(HttpHeaders.Authorization)
            request.headers.append(HttpHeaders.Authorization, "Bearer $firstToken")
        }

        val firstCall = execute(request)
        val finalCall =
            if (provider != null && firstToken != null && firstCall.response.status.value == HTTP_UNAUTHORIZED) {
                val replayToken =
                    when (val refresh = provider.refreshAccessTokenIfCurrent(firstToken)) {
                        is FirstPartySessionRefresh.TokenAvailable -> refresh.accessToken
                        FirstPartySessionRefresh.Unauthenticated,
                        FirstPartySessionRefresh.Unavailable,
                        -> null
                    }

                if (replayToken == null) {
                    firstCall
                } else {
                    request.headers.remove(HttpHeaders.Authorization)
                    request.headers.append(HttpHeaders.Authorization, "Bearer $replayToken")
                    replayed = true
                    logger?.info(
                        tag = HttpLogTag,
                        event = "http_request_replayed",
                        fields =
                            logFields {
                                "http_method" value method
                                "http_path" value path
                                "reason" value "unauthorized"
                            },
                    )
                    execute(request).also { replayCall ->
                        if (replayCall.response.status.value == HTTP_UNAUTHORIZED) {
                            provider.clearSessionIfCurrent(replayToken)
                        }
                    }
                }
            } else {
                firstCall
            }

        finalStatus = finalCall.response.status.value
        logger?.info(
            tag = HttpLogTag,
            event = "http_request_finished",
            fields =
                logFields {
                    "http_method" value method
                    "http_path" value path
                    "http_status" value finalStatus
                    "duration_ms" value started.elapsedNow().inWholeMilliseconds
                    "replayed" value replayed
                },
        )
        if (finalStatus !in 200..299) {
            throw HttpFailureException(
                failure = finalCall.response.status.value.toHttpFailure(),
                diagnostics = finalCall.response.bodyAsText().toHttpFailureDiagnostics(finalCall.response.status.value),
            )
        }
        return finalCall
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: Throwable) {
        if (finalStatus == null) {
            logger?.error(
                tag = HttpLogTag,
                event = "http_request_failed",
                fields =
                    logFields {
                        "http_method" value method
                        "http_path" value path
                        "duration_ms" value started.elapsedNow().inWholeMilliseconds
                    },
                cause = cause,
            )
        }
        throw cause
    }
}

private fun String.toHttpFailureDiagnostics(httpStatus: Int): HttpFailureDiagnostics {
    val response = runCatching { Json.parseToJsonElement(this).jsonObject }.getOrNull()
    return HttpFailureDiagnostics(
        code = response?.get("code")?.jsonPrimitive?.intOrNull ?: httpStatus,
        message = response?.get("message")?.jsonPrimitive?.contentOrNull?.takeUnless(String::isEmpty),
    )
}

private fun Url.hasSameOrigin(other: Url): Boolean =
    protocol.name.equals(other.protocol.name, ignoreCase = true) &&
        host.equals(other.host, ignoreCase = true) &&
        port == other.port

private fun Url.isPublicAuthEndpoint(): Boolean = encodedPath.removePrefix("/").startsWith("v1/auth/")

private fun Url.safeHttpPath(): String =
    encodedPath
        .split('/')
        .joinToString("/") { segment ->
            if (segment.isLikelyDynamicPathSegment()) "{id}" else segment
        }

private fun String.isLikelyDynamicPathSegment(): Boolean =
    length >= MIN_DYNAMIC_PATH_SEGMENT_LENGTH &&
        any(Char::isDigit) &&
        all { character ->
            character.isLetterOrDigit() || character == '-' || character == '_' || character == '%'
        }

private const val HTTP_UNAUTHORIZED = 401
private const val MIN_DYNAMIC_PATH_SEGMENT_LENGTH = 8
private val HttpLogTag = LogTag.of("http")

internal enum class ApiAuthenticationMode {
    Automatic,
    Explicit,
}

internal val ApiAuthenticationModeAttribute = AttributeKey<ApiAuthenticationMode>("ApiAuthenticationMode")

internal fun HttpRequestBuilder.apiAuthenticationMode(mode: ApiAuthenticationMode) {
    attributes.put(ApiAuthenticationModeAttribute, mode)
}
