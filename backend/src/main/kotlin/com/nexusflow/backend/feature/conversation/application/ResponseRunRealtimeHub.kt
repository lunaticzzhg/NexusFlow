package com.nexusflow.backend.feature.conversation.application

import com.nexusflow.backend.feature.conversation.domain.MessageId
import com.nexusflow.backend.feature.responserun.domain.ResponseRun
import com.nexusflow.backend.feature.responserun.domain.ResponseRunId
import com.nexusflow.backend.feature.responserun.domain.ResponseRunStatus
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
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

    private val statesByRun = ConcurrentHashMap<ResponseRunId, MutableRealtimeState>()

    fun beginAttempt(
        run: ResponseRun,
        now: Instant = clock.instant(),
    ) {
        stateFor(run.id, run.attempt)
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
        val state = statesByRun[run.id] ?: return null
        return state.snapshot(run.attempt)
    }

    fun events(
        responseRunId: ResponseRunId,
        afterAttempt: Int,
        afterSeq: Long? = null,
    ): Flow<ResponseRunEvent> =
        channelFlow {
            val subscription = stateFor(responseRunId, afterAttempt).subscribe(afterAttempt, afterSeq ?: 0L)
            val sender = launch {
                var lastSentAttempt = afterAttempt
                var lastSentSeq = afterSeq ?: 0L
                try {
                    subscription.replay.forEach { event ->
                        if (event.isAfter(lastSentAttempt, lastSentSeq)) {
                            send(event)
                            lastSentAttempt = event.attempt
                            lastSentSeq = event.seq
                        }
                    }
                    for (event in subscription.channel) {
                        if (event.isAfter(lastSentAttempt, lastSentSeq)) {
                            send(event)
                            lastSentAttempt = event.attempt
                            lastSentSeq = event.seq
                        }
                    }
                } finally {
                    subscription.close()
                }
            }
            awaitClose {
                sender.cancel()
                subscription.close()
            }
        }

    private fun publish(
        run: ResponseRun,
        payload: ResponseRunEventPayload,
        now: Instant,
        update: (MutableRealtimeState) -> Unit = {},
    ): ResponseRunEvent? {
        val state = stateFor(run.id, run.attempt)
        return state.updateAndPublishEvent(run, payload, now, replayBufferSize, update)
    }

    private fun stateFor(
        responseRunId: ResponseRunId,
        attempt: Int,
    ): MutableRealtimeState =
        statesByRun.compute(responseRunId) { _, existing ->
            existing ?: MutableRealtimeState(responseRunId, attempt)
        }!!.also { state ->
            state.advanceToAttempt(attempt)
        }
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
    private val responseRunId: ResponseRunId,
    initialAttempt: Int,
) {
    var attempt: Int = initialAttempt
        private set
    private var seq: Long = 0
    private val replayEvents = ArrayDeque<ResponseRunEvent>()
    private val subscribers = linkedSetOf<Channel<ResponseRunEvent>>()
    var partialText: String = ""
    val activities: MutableMap<String, ResponseRunActivity> = linkedMapOf()

    @Synchronized
    fun advanceToAttempt(nextAttempt: Int) {
        if (nextAttempt <= attempt) return
        attempt = nextAttempt
        seq = 0
        replayEvents.clear()
        partialText = ""
        activities.clear()
    }

    @Synchronized
    fun updateAndPublishEvent(
        run: ResponseRun,
        payload: ResponseRunEventPayload,
        now: Instant,
        maxSize: Int,
        update: (MutableRealtimeState) -> Unit,
    ): ResponseRunEvent? {
        if (run.attempt != attempt) return null
        update(this)
        seq += 1
        val event = ResponseRunEvent(
            runId = run.id,
            attempt = run.attempt,
            seq = seq,
            occurredAt = now,
            payload = payload,
        )
        replayEvents += event
        while (replayEvents.size > maxSize) {
            replayEvents.removeFirst()
        }
        val closedSubscribers = mutableListOf<Channel<ResponseRunEvent>>()
        subscribers.forEach { subscriber ->
            subscriber.trySend(event).onFailure {
                subscriber.close(ResponseRunSubscriberOverflowException(responseRunId.value.toString()))
                closedSubscribers += subscriber
            }
        }
        subscribers.removeAll(closedSubscribers.toSet())
        return event
    }

    @Synchronized
    fun subscribe(
        afterAttempt: Int,
        afterSeq: Long,
    ): RealtimeSubscription {
        val channel = Channel<ResponseRunEvent>(SUBSCRIBER_BUFFER_SIZE)
        val replay = replayEventsFor(afterAttempt, afterSeq)
        subscribers += channel
        return RealtimeSubscription(channel, replay) {
            synchronized(this) {
                subscribers -= channel
            }
            channel.close()
        }
    }

    @Synchronized
    fun snapshot(expectedAttempt: Int): RealtimeSnapshot? {
        if (expectedAttempt != attempt) return null
        return RealtimeSnapshot(
            attempt = attempt,
            lastSeq = seq,
            partialText = partialText,
            activities = activities.values.toList(),
        )
    }

    private fun replayEventsFor(
        afterAttempt: Int,
        afterSeq: Long,
    ): List<ResponseRunEvent> =
        when {
            afterAttempt < attempt -> replayEvents.toList()
            afterAttempt == attempt -> replayEvents.filter { it.seq > afterSeq }
            else -> emptyList()
        }
}

private class RealtimeSubscription(
    val channel: Channel<ResponseRunEvent>,
    val replay: List<ResponseRunEvent>,
    private val onClose: () -> Unit,
) {
    fun close() = onClose()
}

class ResponseRunSubscriberOverflowException(responseRunId: String) :
    IllegalStateException("response run realtime subscriber overflowed: responseRunId=$responseRunId")

private const val DEFAULT_REPLAY_BUFFER_SIZE = 256
private const val SUBSCRIBER_BUFFER_SIZE = 64

private fun ResponseRunEvent.isAfter(
    lastAttempt: Int,
    lastSeq: Long,
): Boolean =
    attempt > lastAttempt || (attempt == lastAttempt && seq > lastSeq)
