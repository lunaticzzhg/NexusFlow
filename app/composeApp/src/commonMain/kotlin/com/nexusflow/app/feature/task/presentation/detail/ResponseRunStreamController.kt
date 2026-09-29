package com.nexusflow.app.feature.task.presentation.detail

import com.nexusflow.app.core.network.realtime.RealtimeConnectionState
import com.nexusflow.app.core.network.realtime.RealtimeSseEvent
import com.nexusflow.app.core.network.realtime.RealtimeSseFailure
import com.nexusflow.app.core.network.realtime.RealtimeSseRequest
import com.nexusflow.app.core.network.realtime.RealtimeSseSession
import com.nexusflow.app.core.network.realtime.RealtimeSseSessionEvent
import com.nexusflow.app.core.network.realtime.RealtimeSseSessionFactory
import com.nexusflow.app.core.observability.AppLogger
import com.nexusflow.app.core.observability.LogTag
import com.nexusflow.app.core.observability.logFields
import com.nexusflow.app.feature.task.data.toDomain
import com.nexusflow.app.feature.task.domain.ConversationId
import com.nexusflow.app.feature.task.domain.ResponseRun
import com.nexusflow.app.feature.task.domain.ResponseRunActivity
import com.nexusflow.app.feature.task.domain.ResponseRunActivityKind
import com.nexusflow.app.feature.task.domain.ResponseRunId
import com.nexusflow.app.feature.task.domain.ResponseRunSnapshot
import com.nexusflow.app.feature.task.domain.ResponseRunStatus
import com.nexusflow.app.feature.task.domain.TaskRepository
import com.nexusflow.contracts.appbackend.conversation.ResponseRunActivityKindResponse
import com.nexusflow.contracts.appbackend.conversation.ResponseRunEventEnvelope
import com.nexusflow.contracts.appbackend.conversation.ResponseRunEventPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal class ResponseRunStreamController(
    private val repository: TaskRepository,
    private val sseSessionFactory: RealtimeSseSessionFactory,
    private val scope: CoroutineScope,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val reconnectDelayMillis: Long = DEFAULT_RECONNECT_DELAY_MILLIS,
    private val maxReconnectAttempts: Int = DEFAULT_MAX_RECONNECT_ATTEMPTS,
    private val snapshotFallbackIntervalMillis: Long = DEFAULT_SNAPSHOT_FALLBACK_INTERVAL_MILLIS,
    private val silentOpenFallbackDelayMillis: Long? = DEFAULT_SILENT_OPEN_FALLBACK_DELAY_MILLIS,
    private val logger: AppLogger? = null,
) {
    private val _state = MutableStateFlow(ResponseRunStreamState())
    val state: StateFlow<ResponseRunStreamState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<ResponseRunControllerEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<ResponseRunControllerEvent> = _events.asSharedFlow()

    private var activeConversationId: ConversationId? = null
    private var activeRunId: ResponseRunId? = null
    private var session: RealtimeSseSession? = null
    private var sessionJob: Job? = null
    private var recoveryJob: Job? = null
    private var reconnectJob: Job? = null
    private var silentOpenFallbackJob: Job? = null
    private var snapshotFallbackJob: Job? = null
    private var generation: Long = 0
    private var reconnectAttempts: Int = 0

    fun start(
        conversationId: ConversationId,
        run: ResponseRun,
    ) {
        if (activeConversationId == conversationId && activeRunId == run.id) {
            if (run.status.isOpenForStreaming() && state.value.connection is ResponseRunConnectionState.Idle) {
                recover(run.id)
            }
            return
        }
        stop()
        generation += 1
        activeConversationId = conversationId
        activeRunId = run.id
        reconnectAttempts = 0
        _state.value =
            ResponseRunStreamState(
                runId = run.id,
                attempt = run.attempt,
                terminalStatus = run.status.terminalStatusOrNull(),
                connection = ResponseRunConnectionState.Idle,
            )
        logInfo(
            event = "response_run_stream_started",
            conversationId = conversationId,
            runId = run.id,
            attempt = run.attempt,
            terminalStatus = run.status.terminalStatusOrNull(),
            connection = ResponseRunConnectionState.Idle,
        )
        if (run.status.isOpenForStreaming()) {
            recover(run.id)
        }
    }

    fun recover(runId: ResponseRunId) {
        val conversationId = activeConversationId ?: return
        if (activeRunId != runId) return
        val currentGeneration = generation
        recoveryJob?.cancel()
        logInfo(
            event = "response_run_recovery_started",
            conversationId = conversationId,
            runId = runId,
            attempt = state.value.attempt,
            lastSeq = state.value.lastSeq,
            terminalStatus = state.value.terminalStatus,
            connection = state.value.connection,
        )
        recoveryJob =
            scope.launch {
                recoverSnapshotAndConnect(
                    generationAtStart = currentGeneration,
                    conversationId = conversationId,
                    runId = runId,
                    reconnectAfterSnapshot = true,
                )
            }
    }

    private fun recoverTerminalSnapshot(runId: ResponseRunId) {
        val conversationId = activeConversationId ?: return
        if (activeRunId != runId) return
        val currentGeneration = generation
        recoveryJob?.cancel()
        recoveryJob =
            scope.launch {
                recoverSnapshotAndConnect(
                    generationAtStart = currentGeneration,
                    conversationId = conversationId,
                    runId = runId,
                    reconnectAfterSnapshot = false,
                )
            }
    }

    fun handleEvent(envelope: ResponseRunEventEnvelope) {
        val currentRunId =
            activeRunId ?: run {
                logEventIgnored(envelope, reason = "inactive_run")
                return
            }
        if (envelope.runId != currentRunId.value) {
            logEventIgnored(envelope, reason = "wrong_run_id")
            return
        }
        val current = state.value
        logInfo(
            event = "response_run_event_received",
            runId = currentRunId,
            attempt = current.attempt,
            lastSeq = current.lastSeq,
            incomingSeq = envelope.seq,
            terminalStatus = current.terminalStatus,
            connection = current.connection,
        )
        when {
            envelope.attempt < current.attempt -> {
                logEventIgnored(envelope, reason = "stale_attempt")
                return
            }
            envelope.attempt > current.attempt -> {
                recover(currentRunId)
                return
            }
            envelope.seq <= current.lastSeq -> {
                logEventIgnored(envelope, reason = "duplicate_seq")
                return
            }
            envelope.seq > current.lastSeq + 1 -> {
                logWarn(
                    event = "response_run_seq_gap_detected",
                    runId = currentRunId,
                    attempt = current.attempt,
                    lastSeq = current.lastSeq,
                    incomingSeq = envelope.seq,
                    connection = current.connection,
                )
                recover(currentRunId)
                return
            }
        }
        applySequentialEvent(envelope)
    }

    fun stop() {
        generation += 1
        session = null
        sessionJob?.cancel()
        sessionJob = null
        recoveryJob?.cancel()
        recoveryJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        silentOpenFallbackJob?.cancel()
        silentOpenFallbackJob = null
        snapshotFallbackJob?.cancel()
        snapshotFallbackJob = null
        activeConversationId = null
        activeRunId = null
        reconnectAttempts = 0
        _state.value = ResponseRunStreamState()
    }

    private suspend fun recoverSnapshotAndConnect(
        generationAtStart: Long,
        conversationId: ConversationId,
        runId: ResponseRunId,
        reconnectAfterSnapshot: Boolean,
    ) {
        _state.value = _state.value.copy(connection = ResponseRunConnectionState.Recovering)
        repository.loadResponseRunSnapshot(conversationId, runId).fold(
            onSuccess = { snapshot ->
                if (!isCurrent(generationAtStart, conversationId, runId)) return
                applySnapshot(snapshot)
                logInfo(
                    event = "response_run_snapshot_loaded",
                    conversationId = conversationId,
                    runId = runId,
                    attempt = snapshot.streamAttempt,
                    lastSeq = snapshot.lastSeq,
                    terminalStatus = snapshot.run.status.terminalStatusOrNull(),
                    connection = _state.value.connection,
                )
                _events.emit(ResponseRunControllerEvent.SnapshotResolved(snapshot))
                if (snapshot.run.status == ResponseRunStatus.FailedRetryable) {
                    closeSessionOnly()
                    startSnapshotFallback(generationAtStart, conversationId, runId)
                } else if (reconnectAfterSnapshot && snapshot.run.status.isOpenForStreaming()) {
                    connect(generationAtStart, conversationId, runId)
                } else {
                    closeSessionOnly()
                }
            },
            onFailure = {
                if (!isCurrent(generationAtStart, conversationId, runId)) return
                _state.value =
                    _state.value.copy(
                        connection = ResponseRunConnectionState.Error(ResponseRunStreamFailure.RetryableTransport),
                    )
                scheduleReconnect(generationAtStart, conversationId, runId)
            },
        )
    }

    private fun connect(
        generationAtStart: Long,
        conversationId: ConversationId,
        runId: ResponseRunId,
    ) {
        closeSessionOnly()
        snapshotFallbackJob?.cancel()
        snapshotFallbackJob = null
        val current = state.value
        session =
            sseSessionFactory.open(
                RealtimeSseRequest(
                    path =
                        "v1/conversations/${conversationId.value}/response-runs/" +
                            "${runId.value}/events",
                    lastEventId = current.lastEventIdOrNull(),
                ),
            )
        val openedSession = session ?: return
        sessionJob =
            scope.launch {
                openedSession.events.collect { event ->
                    if (!isCurrent(generationAtStart, conversationId, runId)) return@collect
                    when (event) {
                        is RealtimeSseSessionEvent.ConnectionChanged ->
                            handleConnectionChanged(generationAtStart, conversationId, runId, event.state)
                        is RealtimeSseSessionEvent.RawEvent -> handleRawEvent(event.event)
                    }
                }
            }
    }

    private fun handleConnectionChanged(
        generationAtStart: Long,
        conversationId: ConversationId,
        runId: ResponseRunId,
        connection: RealtimeConnectionState,
    ) {
        when (connection) {
            RealtimeConnectionState.Opening ->
                _state.value = _state.value.copy(connection = ResponseRunConnectionState.Opening)
            RealtimeConnectionState.Open -> {
                _state.value = _state.value.copy(connection = ResponseRunConnectionState.Open)
                logInfo(
                    event = "response_run_sse_opened",
                    conversationId = conversationId,
                    runId = runId,
                    attempt = _state.value.attempt,
                    lastSeq = _state.value.lastSeq,
                    connection = _state.value.connection,
                )
                scheduleSilentOpenFallback(generationAtStart, conversationId, runId)
            }
            RealtimeConnectionState.Closed -> {
                silentOpenFallbackJob?.cancel()
                silentOpenFallbackJob = null
                _state.value = _state.value.copy(connection = ResponseRunConnectionState.Closed)
                logInfo(
                    event = "response_run_sse_closed",
                    conversationId = conversationId,
                    runId = runId,
                    attempt = _state.value.attempt,
                    lastSeq = _state.value.lastSeq,
                    terminalStatus = _state.value.terminalStatus,
                    connection = _state.value.connection,
                )
                if (_state.value.terminalStatus == null) {
                    scheduleReconnect(generationAtStart, conversationId, runId)
                }
            }
            is RealtimeConnectionState.Error -> {
                silentOpenFallbackJob?.cancel()
                silentOpenFallbackJob = null
                val failure = connection.failure.toStreamFailure()
                _state.value = _state.value.copy(connection = ResponseRunConnectionState.Error(failure))
                if (failure == ResponseRunStreamFailure.RetryableTransport) {
                    scheduleReconnect(generationAtStart, conversationId, runId)
                }
            }
        }
    }

    private fun handleRawEvent(event: RealtimeSseEvent) {
        if (event.type != RESPONSE_RUN_EVENT_TYPE) {
            logRawEventIgnored(event, reason = "wrong_event_type")
            return
        }
        val data =
            event.data ?: run {
                logRawEventIgnored(event, reason = "empty_data")
                return
            }
        val envelope =
            try {
                json.decodeFromString<ResponseRunEventEnvelope>(data)
            } catch (_: SerializationException) {
                logRawEventIgnored(event, reason = "decode_failed")
                return
            } catch (_: IllegalArgumentException) {
                logRawEventIgnored(event, reason = "decode_failed")
                return
            }
        handleEvent(envelope)
    }

    private fun applySequentialEvent(envelope: ResponseRunEventEnvelope) {
        val payload = envelope.payload
        val current = state.value
        val nextBase = current.copy(attempt = envelope.attempt, lastSeq = envelope.seq)
        _state.value =
            when (payload) {
                ResponseRunEventPayload.Thinking ->
                    nextBase.copy(
                        activities =
                            nextBase.activities.upsert(
                                ResponseRunActivity(
                                    id = THINKING_ACTIVITY_ID,
                                    kind = ResponseRunActivityKind.Thinking,
                                    message = null,
                                    startedAt = envelope.occurredAt,
                                    completedAt = null,
                                    failed = false,
                                ),
                            ),
                    )
                is ResponseRunEventPayload.ToolStarted ->
                    nextBase.copy(
                        activities =
                            nextBase.activities.upsert(
                                ResponseRunActivity(
                                    id = payload.activityId,
                                    kind = payload.kind.toDomain(),
                                    message = null,
                                    startedAt = envelope.occurredAt,
                                    completedAt = null,
                                    failed = false,
                                ),
                            ),
                    )
                is ResponseRunEventPayload.ToolCompleted ->
                    nextBase.copy(
                        activities =
                            nextBase.activities.complete(
                                id = payload.activityId,
                                kind = payload.kind.toDomain(),
                                completedAt = envelope.occurredAt,
                                failed = false,
                            ),
                    )
                is ResponseRunEventPayload.ToolFailed ->
                    nextBase.copy(
                        activities =
                            nextBase.activities.complete(
                                id = payload.activityId,
                                kind = payload.kind.toDomain(),
                                completedAt = envelope.occurredAt,
                                failed = true,
                            ),
                    )
                ResponseRunEventPayload.StreamingStarted -> nextBase
                is ResponseRunEventPayload.Delta -> nextBase.copy(partialText = nextBase.partialText + payload.text)
                is ResponseRunEventPayload.Completed -> nextBase.copy(terminalStatus = ResponseRunStatus.Completed)
                is ResponseRunEventPayload.Failed ->
                    nextBase.copy(terminalStatus = if (payload.retryable) null else ResponseRunStatus.Failed)
                ResponseRunEventPayload.Cancelled -> nextBase.copy(terminalStatus = ResponseRunStatus.Cancelled)
                ResponseRunEventPayload.TimedOut -> nextBase.copy(terminalStatus = ResponseRunStatus.TimedOut)
                is ResponseRunEventPayload.Snapshot -> {
                    applySnapshot(payload.snapshot.toDomainSnapshot())
                    return
                }
            }
        if (_state.value.terminalStatus != null) {
            logInfo(
                event = "response_run_terminal_received",
                runId = _state.value.runId,
                attempt = _state.value.attempt,
                lastSeq = _state.value.lastSeq,
                terminalStatus = _state.value.terminalStatus,
                connection = _state.value.connection,
            )
            activeRunId?.let(::recoverTerminalSnapshot)
        } else if ((envelope.payload as? ResponseRunEventPayload.Failed)?.retryable == true) {
            activeRunId?.let(::recoverRetryableSnapshot)
        }
    }

    private fun recoverRetryableSnapshot(runId: ResponseRunId) {
        val conversationId = activeConversationId ?: return
        if (activeRunId != runId) return
        val currentGeneration = generation
        recoveryJob?.cancel()
        recoveryJob =
            scope.launch {
                recoverSnapshotAndConnect(currentGeneration, conversationId, runId, reconnectAfterSnapshot = true)
            }
    }

    private fun applySnapshot(snapshot: ResponseRunSnapshot) {
        val wasTerminal = _state.value.terminalStatus != null
        _state.value =
            _state.value.copy(
                runId = snapshot.run.id,
                attempt = snapshot.streamAttempt,
                lastSeq = snapshot.lastSeq,
                partialText = snapshot.partialText,
                activities = snapshot.activities,
                terminalStatus = snapshot.run.status.terminalStatusOrNull(),
                connection =
                    if (snapshot.run.status.isOpenForStreaming()) {
                        _state.value.connection
                    } else {
                        ResponseRunConnectionState.Closed
                    },
            )
        if (!wasTerminal && _state.value.terminalStatus != null) {
            silentOpenFallbackJob?.cancel()
            silentOpenFallbackJob = null
            logInfo(
                event = "response_run_terminal_snapshot_resolved",
                runId = snapshot.run.id,
                attempt = snapshot.streamAttempt,
                lastSeq = snapshot.lastSeq,
                terminalStatus = _state.value.terminalStatus,
                connection = _state.value.connection,
            )
            snapshotFallbackJob?.cancel()
            snapshotFallbackJob = null
        }
    }

    private fun scheduleReconnect(
        generationAtStart: Long,
        conversationId: ConversationId,
        runId: ResponseRunId,
    ) {
        if (!isCurrent(generationAtStart, conversationId, runId)) return
        if (reconnectAttempts >= maxReconnectAttempts) {
            startSnapshotFallback(generationAtStart, conversationId, runId)
            return
        }
        reconnectAttempts += 1
        reconnectJob?.cancel()
        reconnectJob =
            scope.launch {
                delay(reconnectDelayMillis)
                if (!isCurrent(generationAtStart, conversationId, runId)) return@launch
                recoverSnapshotAndConnect(
                    generationAtStart = generationAtStart,
                    conversationId = conversationId,
                    runId = runId,
                    reconnectAfterSnapshot = true,
                )
            }
    }

    private fun startSnapshotFallback(
        generationAtStart: Long,
        conversationId: ConversationId,
        runId: ResponseRunId,
    ) {
        if (snapshotFallbackJob?.isActive == true) return
        logInfo(
            event = "response_run_recovery_started",
            conversationId = conversationId,
            runId = runId,
            attempt = _state.value.attempt,
            lastSeq = _state.value.lastSeq,
            terminalStatus = _state.value.terminalStatus,
            connection = _state.value.connection,
        )
        snapshotFallbackJob =
            scope.launch {
                while (isCurrent(generationAtStart, conversationId, runId) && _state.value.terminalStatus == null) {
                    delay(snapshotFallbackIntervalMillis)
                    if (!isCurrent(generationAtStart, conversationId, runId)) return@launch
                    repository.loadResponseRunSnapshot(conversationId, runId).fold(
                        onSuccess = { snapshot ->
                            if (!isCurrent(generationAtStart, conversationId, runId)) return@fold
                            applySnapshot(snapshot)
                            logInfo(
                                event = "response_run_snapshot_loaded",
                                conversationId = conversationId,
                                runId = runId,
                                attempt = snapshot.streamAttempt,
                                lastSeq = snapshot.lastSeq,
                                terminalStatus = snapshot.run.status.terminalStatusOrNull(),
                                connection = _state.value.connection,
                            )
                            _events.emit(ResponseRunControllerEvent.SnapshotResolved(snapshot))
                            if (snapshot.run.status != ResponseRunStatus.FailedRetryable &&
                                snapshot.run.status.isOpenForStreaming()
                            ) {
                                snapshotFallbackJob = null
                                connect(generationAtStart, conversationId, runId)
                                return@launch
                            }
                        },
                        onFailure = {
                            if (!isCurrent(generationAtStart, conversationId, runId)) return@fold
                            _state.value =
                                _state.value.copy(
                                    connection = ResponseRunConnectionState.Error(ResponseRunStreamFailure.RetryableTransport),
                                )
                        },
                    )
                }
            }
    }

    private fun scheduleSilentOpenFallback(
        generationAtStart: Long,
        conversationId: ConversationId,
        runId: ResponseRunId,
    ) {
        val delayMillis = silentOpenFallbackDelayMillis ?: return
        if (_state.value.terminalStatus != null) return
        silentOpenFallbackJob?.cancel()
        silentOpenFallbackJob =
            scope.launch {
                delay(delayMillis)
                if (!isCurrent(generationAtStart, conversationId, runId)) return@launch
                if (_state.value.terminalStatus != null || _state.value.connection != ResponseRunConnectionState.Open) return@launch
                startSnapshotFallback(generationAtStart, conversationId, runId)
            }
    }

    private fun closeSessionOnly() {
        session = null
        sessionJob?.cancel()
        sessionJob = null
        silentOpenFallbackJob?.cancel()
        silentOpenFallbackJob = null
    }

    private fun isCurrent(
        generationAtStart: Long,
        conversationId: ConversationId,
        runId: ResponseRunId,
    ): Boolean =
        generation == generationAtStart &&
            activeConversationId == conversationId &&
            activeRunId == runId

    private fun logInfo(
        event: String,
        conversationId: ConversationId? = activeConversationId,
        runId: ResponseRunId? = activeRunId,
        attempt: Int? = null,
        lastSeq: Long? = null,
        incomingSeq: Long? = null,
        incomingRunId: String? = null,
        incomingAttempt: Int? = null,
        ignoredReason: String? = null,
        sseEventId: String? = null,
        sseEventType: String? = null,
        hasData: Boolean? = null,
        terminalStatus: ResponseRunStatus? = null,
        connection: ResponseRunConnectionState? = null,
    ) {
        logger?.info(
            tag = ResponseRunLogTag,
            event = event,
            fields =
                streamLogFields(
                    conversationId = conversationId,
                    runId = runId,
                    attempt = attempt,
                    lastSeq = lastSeq,
                    incomingSeq = incomingSeq,
                    terminalStatus = terminalStatus,
                    connection = connection,
                    incomingRunId = incomingRunId,
                    incomingAttempt = incomingAttempt,
                    ignoredReason = ignoredReason,
                    sseEventId = sseEventId,
                    sseEventType = sseEventType,
                    hasData = hasData,
                ),
        )
    }

    private fun logWarn(
        event: String,
        conversationId: ConversationId? = activeConversationId,
        runId: ResponseRunId? = activeRunId,
        attempt: Int? = null,
        lastSeq: Long? = null,
        incomingSeq: Long? = null,
        incomingRunId: String? = null,
        incomingAttempt: Int? = null,
        ignoredReason: String? = null,
        sseEventId: String? = null,
        sseEventType: String? = null,
        hasData: Boolean? = null,
        terminalStatus: ResponseRunStatus? = null,
        connection: ResponseRunConnectionState? = null,
    ) {
        logger?.warn(
            tag = ResponseRunLogTag,
            event = event,
            fields =
                streamLogFields(
                    conversationId = conversationId,
                    runId = runId,
                    attempt = attempt,
                    lastSeq = lastSeq,
                    incomingSeq = incomingSeq,
                    terminalStatus = terminalStatus,
                    connection = connection,
                    incomingRunId = incomingRunId,
                    incomingAttempt = incomingAttempt,
                    ignoredReason = ignoredReason,
                    sseEventId = sseEventId,
                    sseEventType = sseEventType,
                    hasData = hasData,
                ),
        )
    }

    private fun logEventIgnored(
        envelope: ResponseRunEventEnvelope,
        reason: String,
    ) {
        logInfo(
            event = "response_run_event_ignored",
            runId = activeRunId,
            attempt = state.value.attempt,
            lastSeq = state.value.lastSeq,
            incomingRunId = envelope.runId,
            incomingAttempt = envelope.attempt,
            incomingSeq = envelope.seq,
            ignoredReason = reason,
            terminalStatus = state.value.terminalStatus,
            connection = state.value.connection,
        )
    }

    private fun logRawEventIgnored(
        event: RealtimeSseEvent,
        reason: String,
    ) {
        val fields =
            streamLogFields(
                conversationId = activeConversationId,
                runId = activeRunId,
                attempt = state.value.attempt,
                lastSeq = state.value.lastSeq,
                incomingSeq = null,
                terminalStatus = state.value.terminalStatus,
                connection = state.value.connection,
                ignoredReason = reason,
                sseEventId = event.id,
                sseEventType = event.type,
                hasData = event.data != null,
            )
        if (reason == "decode_failed") {
            logger?.warn(ResponseRunLogTag, "response_run_event_ignored", fields)
        } else {
            logger?.info(ResponseRunLogTag, "response_run_event_ignored", fields)
        }
    }
}

internal data class ResponseRunStreamState(
    val runId: ResponseRunId? = null,
    val attempt: Int = 0,
    val lastSeq: Long = 0,
    val partialText: String = "",
    val activities: List<ResponseRunActivity> = emptyList(),
    val connection: ResponseRunConnectionState = ResponseRunConnectionState.Idle,
    val terminalStatus: ResponseRunStatus? = null,
)

internal sealed interface ResponseRunConnectionState {
    data object Idle : ResponseRunConnectionState

    data object Opening : ResponseRunConnectionState

    data object Open : ResponseRunConnectionState

    data object Recovering : ResponseRunConnectionState

    data object Closed : ResponseRunConnectionState

    data class Error(
        val failure: ResponseRunStreamFailure,
    ) : ResponseRunConnectionState
}

internal enum class ResponseRunStreamFailure {
    AuthRequired,
    Forbidden,
    Rejected,
    RetryableTransport,
}

internal sealed interface ResponseRunControllerEvent {
    data class SnapshotResolved(
        val snapshot: ResponseRunSnapshot,
    ) : ResponseRunControllerEvent
}

private fun ResponseRunStreamState.lastEventIdOrNull(): String? {
    val runId = runId ?: return null
    if (lastSeq <= 0) return null
    return "${runId.value}:$attempt:$lastSeq"
}

private fun ResponseRunStatus.isOpenForStreaming(): Boolean =
    when (this) {
        ResponseRunStatus.Queued,
        ResponseRunStatus.Processing,
        ResponseRunStatus.Streaming,
        ResponseRunStatus.FailedRetryable,
        -> true
        ResponseRunStatus.Completed,
        ResponseRunStatus.Failed,
        ResponseRunStatus.TimedOut,
        ResponseRunStatus.Cancelled,
        -> false
    }

private fun ResponseRunStatus.terminalStatusOrNull(): ResponseRunStatus? = if (isOpenForStreaming()) null else this

private fun List<ResponseRunActivity>.upsert(activity: ResponseRunActivity): List<ResponseRunActivity> =
    filterNot { it.id == activity.id } + activity

private fun List<ResponseRunActivity>.complete(
    id: String,
    kind: ResponseRunActivityKind,
    completedAt: kotlinx.datetime.Instant,
    failed: Boolean,
): List<ResponseRunActivity> {
    val existing = firstOrNull { it.id == id }
    val completed =
        existing?.copy(completedAt = completedAt, failed = failed)
            ?: ResponseRunActivity(
                id = id,
                kind = kind,
                message = null,
                startedAt = completedAt,
                completedAt = completedAt,
                failed = failed,
            )
    return upsert(completed)
}

private fun RealtimeSseFailure.toStreamFailure(): ResponseRunStreamFailure =
    when (this) {
        RealtimeSseFailure.AuthRequired -> ResponseRunStreamFailure.AuthRequired
        RealtimeSseFailure.Forbidden -> ResponseRunStreamFailure.Forbidden
        RealtimeSseFailure.Rejected -> ResponseRunStreamFailure.Rejected
        RealtimeSseFailure.RetryableTransport -> ResponseRunStreamFailure.RetryableTransport
    }

private fun ResponseRunActivityKindResponse.toDomain(): ResponseRunActivityKind =
    when (this) {
        ResponseRunActivityKindResponse.Thinking -> ResponseRunActivityKind.Thinking
        ResponseRunActivityKindResponse.Weather -> ResponseRunActivityKind.Weather
        ResponseRunActivityKindResponse.PlaceSearch -> ResponseRunActivityKind.PlaceSearch
        ResponseRunActivityKindResponse.Route -> ResponseRunActivityKind.Route
        ResponseRunActivityKindResponse.Movie -> ResponseRunActivityKind.Movie
        ResponseRunActivityKindResponse.Sports -> ResponseRunActivityKind.Sports
        ResponseRunActivityKindResponse.Music -> ResponseRunActivityKind.Music
        ResponseRunActivityKindResponse.Web -> ResponseRunActivityKind.Web
        ResponseRunActivityKindResponse.OtherResearch -> ResponseRunActivityKind.OtherResearch
    }

private fun com.nexusflow.contracts.appbackend.conversation.ResponseRunSnapshotResponse.toDomainSnapshot(): ResponseRunSnapshot = toDomain()

private const val RESPONSE_RUN_EVENT_TYPE = "response-run"
private const val THINKING_ACTIVITY_ID = "thinking"
private const val DEFAULT_RECONNECT_DELAY_MILLIS = 500L
private const val DEFAULT_MAX_RECONNECT_ATTEMPTS = 3
private const val DEFAULT_SILENT_OPEN_FALLBACK_DELAY_MILLIS = 3_000L
private const val DEFAULT_SNAPSHOT_FALLBACK_INTERVAL_MILLIS = 3_000L
private val ResponseRunLogTag = LogTag.of("ResponseRunStream")

private fun streamLogFields(
    conversationId: ConversationId?,
    runId: ResponseRunId?,
    attempt: Int?,
    lastSeq: Long?,
    incomingSeq: Long?,
    terminalStatus: ResponseRunStatus?,
    connection: ResponseRunConnectionState?,
    incomingRunId: String? = null,
    incomingAttempt: Int? = null,
    ignoredReason: String? = null,
    sseEventId: String? = null,
    sseEventType: String? = null,
    hasData: Boolean? = null,
) = logFields {
    "conversation_id" value conversationId?.value
    "response_run_id" value runId?.value
    "attempt" value attempt
    "last_seq" value lastSeq
    "incoming_run_id" value incomingRunId
    "incoming_attempt" value incomingAttempt
    "incoming_seq" value incomingSeq
    "ignored_reason" value ignoredReason
    "sse_event_id" value sseEventId
    "sse_event_type" value sseEventType
    "has_data" value hasData
    "terminal_status" value terminalStatus?.name?.lowercase()
    "connection_state" value connection?.logValue()
}

private fun ResponseRunConnectionState.logValue(): String =
    when (this) {
        ResponseRunConnectionState.Idle -> "idle"
        ResponseRunConnectionState.Opening -> "opening"
        ResponseRunConnectionState.Open -> "open"
        ResponseRunConnectionState.Recovering -> "recovering"
        ResponseRunConnectionState.Closed -> "closed"
        is ResponseRunConnectionState.Error -> "error_${failure.name.lowercase()}"
    }
