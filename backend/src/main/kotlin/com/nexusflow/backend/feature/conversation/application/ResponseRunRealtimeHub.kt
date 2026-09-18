package com.nexusflow.backend.feature.conversation.application

import com.nexusflow.backend.feature.conversation.domain.ResponseRun
import com.nexusflow.backend.feature.conversation.domain.ResponseRunId
import com.nexusflow.backend.feature.conversation.domain.ResponseRunStatus
import com.nexusflow.backend.feature.task.domain.MessageId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ResponseRunRealtimeHub(
    private val clock: Clock = Clock.systemUTC(),
    private val replayBufferSize: Int = DEFAULT_REPLAY_BUFFER_SIZE,
) {
    init {
        require(replayBufferSize > 0) { "replayBufferSize must be positive" }
    }

    private val sharedEvents = MutableSharedFlow<ResponseRunEvent>(extraBufferCapacity = 256)
    private val statesByRun = ConcurrentHashMap<ResponseRunId, MutableRealtimeState>()

    fun beginAttempt(
        run: ResponseRun,
        now: Instant = clock.instant(),
    ) {
        statesByRun.compute(run.id) { _, existing ->
            when {
                existing == null || run.attempt > existing.attempt -> MutableRealtimeState(run.attempt)
                run.attempt == existing.attempt -> existing
                else -> existing
            }
        }
        if (run.status == ResponseRunStatus.Processing) {
            thinking(run, now)
        }
    }

    fun thinking(
        run: ResponseRun,
        now: Instant = clock.instant(),
    ): ResponseRunEvent? =
        publish(run, ResponseRunEventPayload.Thinking, now)

    fun streamingStarted(
        run: ResponseRun,
        now: Instant = clock.instant(),
    ): ResponseRunEvent? =
        publish(run, ResponseRunEventPayload.StreamingStarted, now)

    fun delta(
        run: ResponseRun,
        text: String,
        now: Instant = clock.instant(),
    ): ResponseRunEvent? =
        publish(run, ResponseRunEventPayload.Delta(text), now) { state ->
            state.partialText += text
        }

    fun toolStarted(
        run: ResponseRun,
        kind: ResponseRunActivityKind,
        message: String? = null,
        now: Instant = clock.instant(),
    ): ResponseRunEvent? {
        val activityId = UUID.randomUUID().toString()
        return publish(run, ResponseRunEventPayload.ToolStarted(activityId, kind), now) { state ->
            state.activities[activityId] = ResponseRunActivity(
                id = activityId,
                kind = kind,
                message = message,
                startedAt = now,
            )
        }
    }

    fun toolCompleted(
        run: ResponseRun,
        activityId: String,
        kind: ResponseRunActivityKind,
        now: Instant = clock.instant(),
    ): ResponseRunEvent? =
        publish(run, ResponseRunEventPayload.ToolCompleted(activityId, kind), now) { state ->
            state.activities.computeIfPresent(activityId) { _, activity ->
                activity.copy(completedAt = now)
            }
        }

    fun toolFailed(
        run: ResponseRun,
        activityId: String,
        kind: ResponseRunActivityKind,
        now: Instant = clock.instant(),
    ): ResponseRunEvent? =
        publish(run, ResponseRunEventPayload.ToolFailed(activityId, kind), now) { state ->
            state.activities.compute(activityId) { _, activity ->
                (activity ?: ResponseRunActivity(activityId, kind, null, now)).copy(
                    completedAt = now,
                    failed = true,
                )
            }
        }

    fun terminal(
        run: ResponseRun,
        now: Instant = clock.instant(),
    ): ResponseRunEvent? {
        val payload = when (run.status) {
            ResponseRunStatus.Completed -> ResponseRunEventPayload.Completed(run.assistantMessageId)
            ResponseRunStatus.FailedRetryable -> ResponseRunEventPayload.Failed(retryable = true)
            ResponseRunStatus.Failed -> ResponseRunEventPayload.Failed(retryable = false)
            ResponseRunStatus.TimedOut -> ResponseRunEventPayload.TimedOut
            ResponseRunStatus.Cancelled -> ResponseRunEventPayload.Cancelled
            ResponseRunStatus.Queued,
            ResponseRunStatus.Processing,
            ResponseRunStatus.Streaming,
            -> return null
        }
        return publish(run, payload, now)
    }

    fun recordRun(
        run: ResponseRun,
        message: String? = null,
        now: Instant = clock.instant(),
    ): ResponseRunEvent? =
        when (run.status) {
            ResponseRunStatus.Queued -> {
                beginAttempt(run, now)
                null
            }
            ResponseRunStatus.Processing -> {
                beginAttempt(run, now)
                null
            }
            ResponseRunStatus.Streaming -> {
                beginAttempt(run, now)
                streamingStarted(run, now)
            }
            ResponseRunStatus.Completed,
            ResponseRunStatus.FailedRetryable,
            ResponseRunStatus.Failed,
            ResponseRunStatus.TimedOut,
            ResponseRunStatus.Cancelled,
            -> terminal(run, now)
        }

    fun snapshot(run: ResponseRun): RealtimeSnapshot? {
        val state = statesByRun[run.id]?.takeIf { it.attempt == run.attempt } ?: return null
        return state.snapshot()
    }

    fun events(
        responseRunId: ResponseRunId,
        afterSeq: Long? = null,
    ): Flow<ResponseRunEvent> =
        channelFlow {
            val liveEvents = Channel<ResponseRunEvent>(Channel.UNLIMITED)
            val liveCollector =
                launch {
                    sharedEvents.filter { it.runId == responseRunId }.collect { event ->
                        liveEvents.send(event)
                    }
                }
            var lastSentSeq = afterSeq ?: 0L
            replayEvents(responseRunId, afterSeq).forEach { event ->
                if (event.seq > lastSentSeq) {
                    send(event)
                    lastSentSeq = event.seq
                }
            }
            val liveSender =
                launch {
                    for (event in liveEvents) {
                        if (event.seq > lastSentSeq) {
                            send(event)
                            lastSentSeq = event.seq
                        }
                    }
                }
            awaitClose {
                liveCollector.cancel()
                liveSender.cancel()
                liveEvents.close()
            }
        }

    private fun publish(
        run: ResponseRun,
        payload: ResponseRunEventPayload,
        now: Instant,
        update: (MutableRealtimeState) -> Unit = {},
    ): ResponseRunEvent? {
        val state = statesByRun.compute(run.id) { _, existing ->
            when {
                existing == null || run.attempt > existing.attempt -> MutableRealtimeState(run.attempt)
                run.attempt == existing.attempt -> existing
                else -> existing
            }
        } ?: return null
        if (state.attempt != run.attempt) return null
        update(state)
        val event = ResponseRunEvent(
            runId = run.id,
            attempt = run.attempt,
            seq = state.nextSeq(),
            occurredAt = now,
            payload = payload,
        )
        state.addReplayEvent(event, replayBufferSize)
        sharedEvents.tryEmit(event)
        return event
    }

    private fun replayEvents(
        responseRunId: ResponseRunId,
        afterSeq: Long?,
    ): List<ResponseRunEvent> =
        statesByRun[responseRunId]?.replayEvents(afterSeq ?: 0L).orEmpty()
}

data class RealtimeSnapshot(
    val attempt: Int,
    val lastSeq: Long,
    val partialText: String,
    val activities: List<ResponseRunActivity>,
)

data class ResponseRunActivity(
    val id: String,
    val kind: ResponseRunActivityKind,
    val message: String?,
    val startedAt: Instant,
    val completedAt: Instant? = null,
    val failed: Boolean = false,
)

enum class ResponseRunActivityKind {
    Thinking,
    Weather,
    PlaceSearch,
    Route,
    Movie,
    Sports,
    Music,
    Web,
    OtherResearch,
}

data class ResponseRunEvent(
    val runId: ResponseRunId,
    val attempt: Int,
    val seq: Long,
    val occurredAt: Instant,
    val payload: ResponseRunEventPayload,
)

sealed interface ResponseRunEventPayload {
    data object Thinking : ResponseRunEventPayload

    data class ToolStarted(
        val activityId: String,
        val kind: ResponseRunActivityKind,
    ) : ResponseRunEventPayload

    data class ToolCompleted(
        val activityId: String,
        val kind: ResponseRunActivityKind,
    ) : ResponseRunEventPayload

    data class ToolFailed(
        val activityId: String,
        val kind: ResponseRunActivityKind,
    ) : ResponseRunEventPayload

    data object StreamingStarted : ResponseRunEventPayload

    data class Delta(
        val text: String,
    ) : ResponseRunEventPayload

    data class Completed(
        val assistantMessageId: MessageId?,
    ) : ResponseRunEventPayload

    data class Failed(
        val retryable: Boolean,
    ) : ResponseRunEventPayload

    data object Cancelled : ResponseRunEventPayload

    data object TimedOut : ResponseRunEventPayload
}

private class MutableRealtimeState(
    val attempt: Int,
) {
    private var seq: Long = 0
    private val replayEvents = ArrayDeque<ResponseRunEvent>()
    var partialText: String = ""
    val activities: MutableMap<String, ResponseRunActivity> = linkedMapOf()

    fun nextSeq(): Long {
        seq += 1
        return seq
    }

    fun snapshot(): RealtimeSnapshot =
        RealtimeSnapshot(
            attempt = attempt,
            lastSeq = seq,
            partialText = partialText,
            activities = activities.values.toList(),
        )

    fun addReplayEvent(
        event: ResponseRunEvent,
        maxSize: Int,
    ) {
        replayEvents += event
        while (replayEvents.size > maxSize) {
            replayEvents.removeFirst()
        }
    }

    fun replayEvents(afterSeq: Long): List<ResponseRunEvent> =
        replayEvents.filter { it.seq > afterSeq }
}

private const val DEFAULT_REPLAY_BUFFER_SIZE = 256
