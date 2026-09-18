package com.nexusflow.app.core.network.realtime

import com.nexusflow.app.core.network.FirstPartyApiSession
import com.nexusflow.app.core.network.FirstPartySessionRefresh
import com.nexusflow.app.core.network.HttpFailure
import com.nexusflow.app.core.network.HttpFailureException
import com.nexusflow.app.core.observability.AppLogger
import com.nexusflow.app.core.observability.LogFields
import com.nexusflow.app.core.observability.LogLevel
import com.nexusflow.app.core.observability.LogTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class RealtimeSseSessionFactoryTest {
    @Test
    fun `emits raw SSE event framing from transport events`() =
        runTest {
            val sessionProvider = RecordingFirstPartyApiSession("sse-token")
            val transport =
                RecordingRealtimeSseTransport(
                    events =
                        listOf(
                            RealtimeSseTransportEvent.Opened,
                            RealtimeSseTransportEvent(id = "evt-1", type = "response-run", data = """{"runId":"run-1"}"""),
                        ),
                )
            val logger = RecordingLogger()
            val session =
                factory(transport, sessionProvider, logger)
                    .open(RealtimeSseRequest("v1/conversations/c/response-runs/r/events", lastEventId = "r:1:4"))

            val events = session.events.toList()
            val raw = events.filterIsInstance<RealtimeSseSessionEvent.RawEvent>().single().event

            assertEquals("evt-1", raw.id)
            assertEquals("response-run", raw.type)
            assertEquals("""{"runId":"run-1"}""", raw.data)
            assertEquals(RealtimeConnectionState.Opening, (events.first() as RealtimeSseSessionEvent.ConnectionChanged).state)
            assertEquals(RealtimeConnectionState.Open, (events[1] as RealtimeSseSessionEvent.ConnectionChanged).state)
            assertEquals(RealtimeConnectionState.Closed, (events.last() as RealtimeSseSessionEvent.ConnectionChanged).state)
            assertEquals(listOf("sse-token"), transport.tokens)
            assertEquals(
                mapOf(
                    "path" to "v1/conversations/c/response-runs/r/events",
                    "last_event_id" to "r:1:4",
                    "event_id" to "evt-1",
                    "event_type" to "response-run",
                    "has_data" to "true",
                ),
                logger.entries.single { it.event == "sse_raw_event_received" }.fields,
            )
        }

    @Test
    fun `401 explicit SSE does not refresh or expose token in state`() =
        runTest {
            val sessionProvider =
                RecordingFirstPartyApiSession(
                    token = "sse-token",
                    refreshResult = FirstPartySessionRefresh.TokenAvailable("new-token"),
                )
            val transport = RecordingRealtimeSseTransport(failure = HttpFailureException(HttpFailure.Unauthorized))
            val session = factory(transport, sessionProvider).open(RealtimeSseRequest("v1/conversations/c/response-runs/r/events"))

            val events = session.events.toList()
            val state = (events.last() as RealtimeSseSessionEvent.ConnectionChanged).state

            assertEquals(listOf("sse-token"), transport.tokens)
            assertEquals(emptyList<String>(), sessionProvider.refreshRequests)
            assertEquals(emptyList<String>(), sessionProvider.clearRequests)
            assertEquals(RealtimeConnectionState.Error(RealtimeSseFailure.AuthRequired), state)
            assertFalse(events.toString().contains("sse-token"))
        }

    @Test
    fun `403 explicit SSE stops without refresh`() =
        runTest {
            val sessionProvider = RecordingFirstPartyApiSession("sse-token")
            val transport = RecordingRealtimeSseTransport(failure = HttpFailureException(HttpFailure.Forbidden))
            val session = factory(transport, sessionProvider).open(RealtimeSseRequest("v1/conversations/c/response-runs/r/events"))

            val events = session.events.toList()
            val state = (events.last() as RealtimeSseSessionEvent.ConnectionChanged).state

            assertEquals(emptyList<String>(), sessionProvider.refreshRequests)
            assertEquals(RealtimeConnectionState.Error(RealtimeSseFailure.Forbidden), state)
        }

    @Test
    fun `transport cancellation propagates`() =
        runTest {
            val sessionProvider = RecordingFirstPartyApiSession("sse-token")
            val transport = RecordingRealtimeSseTransport(failure = CancellationException("cancelled"))
            val session = factory(transport, sessionProvider).open(RealtimeSseRequest("v1/conversations/c/response-runs/r/events"))

            assertFailsWith<CancellationException> {
                session.events.toList()
            }
        }
}

private fun factory(
    transport: RealtimeSseTransport,
    session: RecordingFirstPartyApiSession,
    logger: AppLogger? = null,
): RealtimeSseSessionFactory =
    RealtimeSseSessionFactory(
        transport = transport,
        logger = logger,
    ) {
        session
    }

private class RecordingLogger : AppLogger {
    val entries = mutableListOf<Entry>()

    override fun log(
        level: LogLevel,
        tag: LogTag,
        event: String,
        fields: LogFields,
        cause: Throwable?,
    ) {
        entries += Entry(event, fields.values)
    }

    data class Entry(
        val event: String,
        val fields: Map<String, String>,
    )
}

private class RecordingRealtimeSseTransport(
    private val events: List<RealtimeSseTransportEvent> = emptyList(),
    private val failure: Throwable? = null,
) : RealtimeSseTransport {
    val tokens = mutableListOf<String>()

    override suspend fun collect(
        request: RealtimeSseRequest,
        bearerToken: String,
        onEvent: suspend (RealtimeSseTransportEvent) -> Unit,
    ) {
        tokens += bearerToken
        failure?.let { throw it }
        events.forEach { event -> onEvent(event) }
    }
}

private class RecordingFirstPartyApiSession(
    private val token: String?,
    private val refreshResult: FirstPartySessionRefresh = FirstPartySessionRefresh.Unavailable,
) : FirstPartyApiSession {
    val refreshRequests = mutableListOf<String>()
    val clearRequests = mutableListOf<String>()

    override suspend fun currentAccessToken(): String? = token

    override suspend fun refreshAccessTokenIfCurrent(accessToken: String): FirstPartySessionRefresh {
        refreshRequests += accessToken
        return refreshResult
    }

    override suspend fun clearSessionIfCurrent(accessToken: String): Boolean {
        clearRequests += accessToken
        return true
    }
}
