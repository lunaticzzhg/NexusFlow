package com.nexusflow.app.core.network.realtime

import com.nexusflow.app.core.network.ApiAuthenticationMode
import com.nexusflow.app.core.network.FirstPartyApiSession
import com.nexusflow.app.core.network.HttpFailure
import com.nexusflow.app.core.network.HttpFailureException
import com.nexusflow.app.core.network.apiAuthenticationMode
import com.nexusflow.app.core.observability.AppLogger
import com.nexusflow.app.core.observability.LogLevel
import com.nexusflow.app.core.observability.LogTag
import com.nexusflow.app.core.observability.logFields
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.sse.SSEClientException
import io.ktor.client.plugins.sse.sse
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

internal class RealtimeSseSessionFactory(
    private val transport: RealtimeSseTransport,
    private val logger: AppLogger? = null,
    private val sessionProvider: () -> FirstPartyApiSession?,
) {
    constructor(
        httpClient: HttpClient,
        apiBaseUrl: String,
        logger: AppLogger? = null,
        sessionProvider: () -> FirstPartyApiSession?,
    ) : this(
        transport = KtorRealtimeSseTransport(httpClient, apiBaseUrl),
        logger = logger,
        sessionProvider = sessionProvider,
    )

    fun open(request: RealtimeSseRequest): RealtimeSseSession {
        val stopSignal = CompletableDeferred<Unit>()
        val events =
            channelFlow {
                var stopRequested = false
                val connection =
                    launch {
                        try {
                            logger.logSseInfo("sse_opening", request)
                            send(RealtimeSseSessionEvent.ConnectionChanged(RealtimeConnectionState.Opening))
                            val token = sessionProvider()?.currentAccessToken()
                            if (token == null) {
                                logger.logSseWarn(
                                    event = "sse_error",
                                    request = request,
                                    failure = RealtimeSseFailure.AuthRequired,
                                )
                                send(
                                    RealtimeSseSessionEvent.ConnectionChanged(
                                        RealtimeConnectionState.Error(RealtimeSseFailure.AuthRequired),
                                    ),
                                )
                                return@launch
                            }
                            transport.collect(
                                request = request,
                                bearerToken = token,
                            ) { event ->
                                if (event == RealtimeSseTransportEvent.Opened) {
                                    logger.logSseInfo("sse_opened", request)
                                    send(RealtimeSseSessionEvent.ConnectionChanged(RealtimeConnectionState.Open))
                                    return@collect
                                }
                                logger.logRawSseEvent(request, event)
                                send(
                                    RealtimeSseSessionEvent.RawEvent(
                                        RealtimeSseEvent(
                                            id = event.id,
                                            type = event.type,
                                            data = event.data,
                                        ),
                                    ),
                                )
                            }
                            logger.logSseInfo("sse_closed", request)
                            send(RealtimeSseSessionEvent.ConnectionChanged(RealtimeConnectionState.Closed))
                        } catch (cause: CancellationException) {
                            throw cause
                        } catch (cause: HttpFailureException) {
                            logger.logSseWarn(
                                event = "sse_error",
                                request = request,
                                failure = cause.toRealtimeFailure(),
                                cause = cause,
                            )
                            send(
                                RealtimeSseSessionEvent.ConnectionChanged(
                                    RealtimeConnectionState.Error(cause.toRealtimeFailure()),
                                ),
                            )
                        } catch (cause: SSEClientException) {
                            logger.logSseWarn(
                                event = "sse_error",
                                request = request,
                                failure = cause.toRealtimeFailure(),
                                httpStatus = cause.response?.status?.value,
                                cause = cause,
                            )
                            send(
                                RealtimeSseSessionEvent.ConnectionChanged(
                                    RealtimeConnectionState.Error(cause.toRealtimeFailure()),
                                ),
                            )
                        } catch (cause: Throwable) {
                            logger.logSseWarn(
                                event = "sse_error",
                                request = request,
                                failure = RealtimeSseFailure.RetryableTransport,
                                cause = cause,
                            )
                            send(
                                RealtimeSseSessionEvent.ConnectionChanged(
                                    RealtimeConnectionState.Error(RealtimeSseFailure.RetryableTransport),
                                ),
                            )
                        }
                    }
                connection.invokeOnCompletion { cause ->
                    if (cause == null || stopRequested) {
                        close()
                    } else {
                        close(cause)
                    }
                }
                stopSignal.invokeOnCompletion {
                    stopRequested = true
                    connection.cancel()
                }
                awaitClose {
                    stopRequested = true
                    stopSignal.complete(Unit)
                    connection.cancel()
                }
            }
        return RealtimeSseSession(events = events, stopAction = { stopSignal.complete(Unit) })
    }
}

internal interface RealtimeSseTransport {
    suspend fun collect(
        request: RealtimeSseRequest,
        bearerToken: String,
        onEvent: suspend (RealtimeSseTransportEvent) -> Unit,
    )
}

internal data class RealtimeSseTransportEvent(
    val id: String?,
    val type: String?,
    val data: String?,
) {
    companion object {
        val Opened = RealtimeSseTransportEvent(id = null, type = null, data = null)
    }
}

private class KtorRealtimeSseTransport(
    private val httpClient: HttpClient,
    private val apiBaseUrl: String,
) : RealtimeSseTransport {
    override suspend fun collect(
        request: RealtimeSseRequest,
        bearerToken: String,
        onEvent: suspend (RealtimeSseTransportEvent) -> Unit,
    ) {
        httpClient.sse(
            request = {
                url(request.toUrl(apiBaseUrl))
                apiAuthenticationMode(ApiAuthenticationMode.Explicit)
                header(HttpHeaders.Authorization, "Bearer $bearerToken")
                header(HttpHeaders.Accept, SSE_ACCEPT)
                request.lastEventId?.let { header(LAST_EVENT_ID_HEADER, it) }
                timeout {
                    requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                    socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                }
            },
        ) {
            onEvent(RealtimeSseTransportEvent.Opened)
            incoming.collect { event ->
                onEvent(
                    RealtimeSseTransportEvent(
                        id = event.id,
                        type = event.event,
                        data = event.data,
                    ),
                )
            }
        }
    }
}

internal data class RealtimeSseRequest(
    val path: String,
    val lastEventId: String? = null,
)

internal class RealtimeSseSession(
    val events: Flow<RealtimeSseSessionEvent>,
    private val stopAction: () -> Unit,
) {
    fun stop() {
        stopAction()
    }
}

internal sealed interface RealtimeSseSessionEvent {
    data class ConnectionChanged(
        val state: RealtimeConnectionState,
    ) : RealtimeSseSessionEvent

    data class RawEvent(
        val event: RealtimeSseEvent,
    ) : RealtimeSseSessionEvent
}

internal data class RealtimeSseEvent(
    val id: String?,
    val type: String?,
    val data: String?,
)

internal sealed interface RealtimeConnectionState {
    data object Opening : RealtimeConnectionState

    data object Open : RealtimeConnectionState

    data object Closed : RealtimeConnectionState

    data class Error(
        val failure: RealtimeSseFailure,
    ) : RealtimeConnectionState
}

internal enum class RealtimeSseFailure {
    AuthRequired,
    Forbidden,
    Rejected,
    RetryableTransport,
}

private fun RealtimeSseRequest.toUrl(apiBaseUrl: String): String = apiBaseUrl.trimEnd('/') + "/" + path.trimStart('/')

private fun HttpFailureException.toRealtimeFailure(): RealtimeSseFailure =
    when (failure) {
        HttpFailure.Unauthorized -> RealtimeSseFailure.AuthRequired
        HttpFailure.Forbidden -> RealtimeSseFailure.Forbidden
        is HttpFailure.ServerError,
        HttpFailure.RateLimited,
        -> RealtimeSseFailure.RetryableTransport
        HttpFailure.NotFound,
        HttpFailure.Conflict,
        is HttpFailure.ClientError,
        -> RealtimeSseFailure.Rejected
    }

private fun SSEClientException.toRealtimeFailure(): RealtimeSseFailure =
    when (val status = response?.status?.value) {
        HttpStatusCode.Unauthorized.value -> RealtimeSseFailure.AuthRequired
        HttpStatusCode.Forbidden.value -> RealtimeSseFailure.Forbidden
        HttpStatusCode.TooManyRequests.value -> RealtimeSseFailure.RetryableTransport
        null -> RealtimeSseFailure.RetryableTransport
        in 500..599 -> RealtimeSseFailure.RetryableTransport
        in 400..499 -> RealtimeSseFailure.Rejected
        else -> RealtimeSseFailure.RetryableTransport
    }

private const val SSE_ACCEPT = "text/event-stream"
private const val LAST_EVENT_ID_HEADER = "Last-Event-ID"
private val RealtimeSseLogTag = LogTag.of("RealtimeSse")

private fun AppLogger?.logSseInfo(
    event: String,
    request: RealtimeSseRequest,
) {
    this?.info(
        tag = RealtimeSseLogTag,
        event = event,
        fields = request.toLogFields(),
    )
}

private fun AppLogger?.logSseWarn(
    event: String,
    request: RealtimeSseRequest,
    failure: RealtimeSseFailure,
    httpStatus: Int? = null,
    cause: Throwable? = null,
) {
    this?.log(
        level = LogLevel.WARN,
        tag = RealtimeSseLogTag,
        event = event,
        fields =
            logFields {
                addRequestFields(request)
                "failure" value failure.name.lowercase()
                "http_status" value httpStatus
            },
        cause = cause,
    )
}

private fun AppLogger?.logRawSseEvent(
    request: RealtimeSseRequest,
    event: RealtimeSseTransportEvent,
) {
    this?.info(
        tag = RealtimeSseLogTag,
        event = "sse_raw_event_received",
        fields =
            logFields {
                addRequestFields(request)
                "event_id" value event.id
                "event_type" value event.type
                "has_data" value (event.data != null)
            },
    )
}

private fun RealtimeSseRequest.toLogFields() =
    logFields {
        addRequestFields(this@toLogFields)
    }

private fun com.nexusflow.app.core.observability.LogFieldsBuilder.addRequestFields(request: RealtimeSseRequest) {
    "path" value request.path
    "last_event_id" value request.lastEventId
}
