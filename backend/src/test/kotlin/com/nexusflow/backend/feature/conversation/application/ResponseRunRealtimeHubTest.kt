package com.nexusflow.backend.feature.conversation.application

import com.nexusflow.backend.feature.research.application.ReadToolActivityKind
import com.nexusflow.backend.feature.research.application.ReadToolCall
import com.nexusflow.backend.feature.research.application.ReadToolDefinition
import com.nexusflow.backend.feature.research.application.ReadToolEvidence
import com.nexusflow.backend.feature.research.application.ReadToolEvidencePayload
import com.nexusflow.backend.feature.research.application.ReadToolFact
import com.nexusflow.backend.feature.research.application.ReadToolFactKind
import com.nexusflow.backend.feature.research.application.ReadToolFactValue
import com.nexusflow.backend.feature.research.application.ReadToolKey
import com.nexusflow.backend.feature.research.application.ReadToolOutcome
import com.nexusflow.backend.feature.conversation.domain.ConversationId
import com.nexusflow.backend.feature.responserun.domain.ResponseRun
import com.nexusflow.backend.feature.responserun.domain.ResponseRunId
import com.nexusflow.backend.feature.responserun.domain.ResponseRunStage
import com.nexusflow.backend.feature.responserun.domain.ResponseRunStatus
import com.nexusflow.backend.feature.conversation.domain.MessageId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResponseRunRealtimeHubTest {
    private val now = Instant.parse("2026-08-28T10:15:30Z")
    private val hub = ResponseRunRealtimeHub(Clock.fixed(now, ZoneOffset.UTC))

    @Test
    fun `events use monotonic per attempt sequence and snapshot carries partial and activities`() {
        val run = responseRun(attempt = 1)

        val thinking = assertNotNull(hub.thinking(run))
        val started = assertNotNull(hub.toolStarted(run, ResponseRunActivityKind.Web, "Searching"))
        val activityId = (started.payload as ResponseRunEventPayload.ToolStarted).activityId
        val delta = assertNotNull(hub.delta(run, "hello"))
        val completed = assertNotNull(hub.toolCompleted(run, activityId, ResponseRunActivityKind.Web))

        assertEquals(listOf(1L, 2L, 3L, 4L), listOf(thinking.seq, started.seq, delta.seq, completed.seq))
        val snapshot = assertNotNull(hub.snapshot(run))
        assertEquals(1, snapshot.attempt)
        assertEquals(4, snapshot.lastSeq)
        assertEquals("hello", snapshot.partialText)
        assertEquals(1, snapshot.activities.size)
        assertEquals(activityId, snapshot.activities.single().id)
        assertEquals(now, snapshot.activities.single().completedAt)
    }

    @Test
    fun `stale attempt events are not emitted as active after a newer attempt begins`() {
        val current = responseRun(attempt = 2)
        val stale = current.copy(attempt = 1)

        hub.beginAttempt(current)
        assertNotNull(hub.delta(current, "new"))
        assertNull(hub.delta(stale, "old"))

        val snapshot = assertNotNull(hub.snapshot(current))
        assertEquals(2, snapshot.attempt)
        assertEquals(2, snapshot.lastSeq)
        assertEquals("new", snapshot.partialText)
        assertNull(hub.snapshot(stale))
    }

    @Test
    fun `events replay buffered events after requested sequence`() =
        runBlocking {
            val run = responseRun(attempt = 1)
            assertNotNull(hub.delta(run, "first"))
            val second = assertNotNull(hub.delta(run, "second"))

            val replayed = hub.events(run.id, afterAttempt = run.attempt, afterSeq = 1).take(1).toList()

            assertEquals(listOf(second), replayed)
        }

    @Test
    fun `events replay is bounded per run`() =
        runBlocking {
            val boundedHub = ResponseRunRealtimeHub(Clock.fixed(now, ZoneOffset.UTC), replayBufferSize = 2)
            val run = responseRun(attempt = 1)
            assertNotNull(boundedHub.delta(run, "one"))
            val second = assertNotNull(boundedHub.delta(run, "two"))
            val third = assertNotNull(boundedHub.delta(run, "three"))

            val replayed = boundedHub.events(run.id, afterAttempt = run.attempt, afterSeq = 0).take(2).toList()

            assertEquals(listOf(second, third), replayed)
        }


    @Test
    fun `open subscriber receives next attempt after attempt advances`() =
        runBlocking {
            val run1 = responseRun(attempt = 1)
            val first = assertNotNull(hub.delta(run1, "old"))
            val subscriptionObserved = CompletableDeferred<ResponseRunEvent>()
            val deferred = async {
                val collected = mutableListOf<ResponseRunEvent>()
                hub.events(run1.id, afterAttempt = 1, afterSeq = first.seq).take(3).collect { event ->
                    collected += event
                    if (event.attempt == 1 && event.payload is ResponseRunEventPayload.Delta) {
                        subscriptionObserved.complete(event)
                    }
                }
                collected
            }
            val liveOld = assertNotNull(hub.delta(run1, "live-old"))
            assertEquals(liveOld, withTimeout(1_000) { subscriptionObserved.await() })

            val run2 = run1.copy(attempt = 2)
            hub.beginAttempt(run2)
            val next = assertNotNull(hub.delta(run2, "new"))

            val received = withTimeout(1_000) { deferred.await() }
            assertEquals(liveOld, received[0])
            assertEquals(ResponseRunEventPayload.Thinking, received[1].payload)
            assertEquals(next, received[2])
            val snapshot = assertNotNull(hub.snapshot(run2))
            assertEquals(2, snapshot.attempt)
            assertEquals("new", snapshot.partialText)
        }

    @Test
    fun `old attempt publish cannot mutate new attempt after advance`() {
        val run1 = responseRun(attempt = 1)
        assertNotNull(hub.delta(run1, "old"))
        val run2 = run1.copy(attempt = 2)
        hub.beginAttempt(run2)

        assertNull(hub.delta(run1, "late-old"))
        val newDelta = assertNotNull(hub.delta(run2, "new"))

        val snapshot = assertNotNull(hub.snapshot(run2))
        assertEquals(2, newDelta.seq)
        assertEquals("new", snapshot.partialText)
        assertNull(hub.snapshot(run1))
    }

    @Test
    fun `replay with previous attempt cursor returns current attempt from beginning`() =
        runBlocking {
            val run1 = responseRun(attempt = 1)
            assertNotNull(hub.delta(run1, "old"))
            val run2 = run1.copy(attempt = 2)
            hub.beginAttempt(run2)
            val first = assertNotNull(hub.delta(run2, "first"))
            val second = assertNotNull(hub.delta(run2, "second"))

            val replayed = hub.events(run2.id, afterAttempt = 1, afterSeq = 99).take(3).toList()

            assertEquals(ResponseRunEventPayload.Thinking, replayed[0].payload)
            assertEquals(listOf(first, second), replayed.drop(1))
        }

    @Test
    fun `slow live subscriber is disconnected when its buffer overflows`() =
        runBlocking {
            val boundedHub = ResponseRunRealtimeHub(Clock.fixed(now, ZoneOffset.UTC), replayBufferSize = 128)
            val run = responseRun(attempt = 1)
            val firstObserved = CompletableDeferred<Unit>()
            val keepCollectorBlocked = CompletableDeferred<Unit>()
            val failure = async {
                assertFailsWith<ResponseRunSubscriberOverflowException> {
                    boundedHub.events(run.id, afterAttempt = run.attempt, afterSeq = 0).collect {
                        firstObserved.complete(Unit)
                        keepCollectorBlocked.await()
                    }
                }
            }

            assertNotNull(boundedHub.delta(run, "start"))
            withTimeout(1_000) { firstObserved.await() }
            repeat(256) { index ->
                boundedHub.delta(run, "overflow-$index")
            }
            keepCollectorBlocked.complete(Unit)

            withTimeout(1_000) { failure.await() }
            val current = responseRun(attempt = 1)
            assertNotNull(boundedHub.delta(current, "after-overflow"))
            val snapshot = assertNotNull(boundedHub.snapshot(current))
            assertTrue(snapshot.partialText.contains("after-overflow"))
        }

    @Test
    fun `concurrent publishers deliver a complete monotonic live sequence`() =
        runBlocking {
            val run = responseRun(attempt = 1)
            val expectedCount = 20
            val ready = CompletableDeferred<Unit>()
            val received = async {
                val events = mutableListOf<ResponseRunEvent>()
                hub.events(run.id, afterAttempt = run.attempt, afterSeq = 0).take(expectedCount).collect { event ->
                    ready.complete(Unit)
                    events += event
                }
                events
            }
            assertNotNull(hub.delta(run, "warmup"))
            withTimeout(1_000) { ready.await() }
            val start = CompletableDeferred<Unit>()
            val jobs = (1 until expectedCount).map { index ->
                launch(Dispatchers.Default) {
                    start.await()
                    assertNotNull(hub.delta(run, "delta-$index"))
                }
            }
            start.complete(Unit)
            jobs.forEach { it.join() }

            val events = withTimeout(1_000) { received.await() }

            assertEquals((1L..expectedCount.toLong()).toList(), events.map { it.seq })
            assertEquals(List(expectedCount) { run.attempt }, events.map { it.attempt })
            assertEquals(expectedCount, events.map { it.seq }.toSet().size)
        }

    @Test
    fun `read tool observer projects only safe activity fields`() =
        runBlocking {
            val run = responseRun(attempt = 1)
            val definition = ReadToolDefinition(
                key = ReadToolKey("weather.forecast"),
                description = "Weather forecast.",
                argumentHint = "Coordinates.",
                activityKind = ReadToolActivityKind.Weather,
            )
            val call = ReadToolCall(
                key = definition.key,
                arguments = JsonObject(mapOf("rawUrl" to JsonPrimitive("https://internal.example/private"))),
            )
            val observer = ResponseRunReadToolActivityObserver(hub, run)

            observer.onStarted(call, definition)
            observer.onFinished(call, definition, rawSuccessOutcome())

            val snapshot = assertNotNull(hub.snapshot(run))
            assertEquals(1, snapshot.activities.size)
            val activity = snapshot.activities.single()
            assertEquals(ResponseRunActivityKind.Weather, activity.kind)
            assertEquals(null, activity.message)
            assertEquals(false, activity.failed)
            assertEquals(now, activity.completedAt)
        }

    @Test
    fun `read tool observer maps unavailable to failed and missing input to completed`() =
        runBlocking {
            val run = responseRun(attempt = 1)
            val failedDefinition = ReadToolDefinition(
                key = ReadToolKey("web.search"),
                description = "Web search.",
                argumentHint = "Query.",
                activityKind = ReadToolActivityKind.Web,
            )
            val completedDefinition = ReadToolDefinition(
                key = ReadToolKey("route.estimate"),
                description = "Route estimate.",
                argumentHint = "Coordinates.",
                activityKind = ReadToolActivityKind.Route,
            )
            val observer = ResponseRunReadToolActivityObserver(hub, run)
            val failedCall = ReadToolCall(failedDefinition.key, JsonObject(emptyMap()))
            val completedCall = ReadToolCall(completedDefinition.key, JsonObject(emptyMap()))

            observer.onStarted(failedCall, failedDefinition)
            observer.onFinished(failedCall, failedDefinition, ReadToolOutcome.Unavailable("internal exception detail"))
            observer.onStarted(completedCall, completedDefinition)
            observer.onFinished(completedCall, completedDefinition, ReadToolOutcome.MissingInput(setOf("origin")))

            val activities = assertNotNull(hub.snapshot(run)).activities.associateBy { it.kind }
            assertEquals(true, activities.getValue(ResponseRunActivityKind.Web).failed)
            assertEquals(false, activities.getValue(ResponseRunActivityKind.Route).failed)
        }

    private fun responseRun(attempt: Int): ResponseRun =
        ResponseRun(
            id = ResponseRunId(UUID.fromString("00000000-0000-0000-0000-000000006001")),
            conversationId = ConversationId(UUID.fromString("00000000-0000-0000-0000-000000006002")),
            userMessageId = MessageId(UUID.fromString("00000000-0000-0000-0000-000000006003")),
            turnIndex = 1,
            status = ResponseRunStatus.Processing,
            stage = ResponseRunStage.Turn,
            attempt = attempt,
            availableAt = now,
            leaseOwner = null,
            leaseExpiresAt = null,
            deadlineAt = now.plusSeconds(30),
            expectedTaskId = null,
            expectedTaskRevision = null,
            assistantMessageId = null,
            failureCategory = null,
            originTraceId = null,
            createdAt = now,
            startedAt = now,
            updatedAt = now,
            completedAt = null,
        )

    private fun rawSuccessOutcome(): ReadToolOutcome.Success =
        ReadToolOutcome.Success(
            ReadToolEvidencePayload(
                listOf(
                    ReadToolEvidence(
                        sourceId = "raw-source",
                        sourceUrl = "https://internal.example/raw-result",
                        sourceKey = "weather.forecast",
                        facts = listOf(ReadToolFact(ReadToolFactKind.SOURCE_URL, ReadToolFactValue.Text("raw-provider-url"))),
                    ),
                ),
            ),
        )
}
