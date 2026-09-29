@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.nexusflow.app.feature.task.presentation.detail

import com.nexusflow.app.core.network.FirstPartyApiSession
import com.nexusflow.app.core.network.FirstPartySessionRefresh
import com.nexusflow.app.core.network.realtime.RealtimeSseRequest
import com.nexusflow.app.core.network.realtime.RealtimeSseSessionFactory
import com.nexusflow.app.core.network.realtime.RealtimeSseTransport
import com.nexusflow.app.core.network.realtime.RealtimeSseTransportEvent
import com.nexusflow.app.core.observability.AppLogger
import com.nexusflow.app.core.observability.LogFields
import com.nexusflow.app.core.observability.LogLevel
import com.nexusflow.app.core.observability.LogTag
import com.nexusflow.app.feature.task.domain.ConversationDetail
import com.nexusflow.app.feature.task.domain.ConversationId
import com.nexusflow.app.feature.task.domain.CreateConversationCommand
import com.nexusflow.app.feature.task.domain.MessageRole
import com.nexusflow.app.feature.task.domain.RemoveRequirementCommand
import com.nexusflow.app.feature.task.domain.ResponseRun
import com.nexusflow.app.feature.task.domain.ResponseRunActivityKind
import com.nexusflow.app.feature.task.domain.ResponseRunId
import com.nexusflow.app.feature.task.domain.ResponseRunSnapshot
import com.nexusflow.app.feature.task.domain.ResponseRunStage
import com.nexusflow.app.feature.task.domain.ResponseRunStatus
import com.nexusflow.app.feature.task.domain.SelectPlanCommand
import com.nexusflow.app.feature.task.domain.SendConversationMessageCommand
import com.nexusflow.app.feature.task.domain.TaskDetail
import com.nexusflow.app.feature.task.domain.TaskMessage
import com.nexusflow.app.feature.task.domain.TaskRepository
import com.nexusflow.app.feature.task.domain.TaskSummary
import com.nexusflow.app.feature.task.domain.UpdateRequirementCommand
import com.nexusflow.contracts.appbackend.conversation.ResponseRunActivityKindResponse
import com.nexusflow.contracts.appbackend.conversation.ResponseRunEventEnvelope
import com.nexusflow.contracts.appbackend.conversation.ResponseRunEventPayload
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ResponseRunStreamControllerTest {
    @Test
    fun `retryable failure polls without replay and connects after next attempt`() =
        runTest {
            val retryable = responseRun(status = ResponseRunStatus.FailedRetryable, attempt = 1)
            val processing = retryable.copy(status = ResponseRunStatus.Processing, attempt = 2, retryable = false)
            val completed = processing.copy(status = ResponseRunStatus.Completed, assistantMessageId = "assistant-1")
            val repository =
                ControllerRepository(
                    listOf(
                        responseRunSnapshot(retryable, conversation(retryable)),
                        responseRunSnapshot(retryable, conversation(retryable)),
                        responseRunSnapshot(processing, conversation(processing)),
                        responseRunSnapshot(completed, conversation(completed, assistantText = "done")),
                    ),
                )
            val requests = mutableListOf<RealtimeSseRequest>()
            val controller = controller(repository, scope = this, requests = requests)

            controller.start(conversation(retryable).id, retryable)
            advanceUntilIdle()

            assertEquals(2, controller.state.value.attempt)
            assertEquals(1, requests.size)
            controller.handleEvent(event(processing, attempt = 2, seq = 1, payload = ResponseRunEventPayload.Completed("assistant-1")))
            advanceUntilIdle()

            assertEquals(listOf(retryable.id, retryable.id, retryable.id, retryable.id), repository.snapshotCommands)
            assertEquals(ResponseRunStatus.Completed, controller.state.value.terminalStatus)
            controller.stop()
        }

    @Test
    fun `applies thinking tool and delta while ignoring duplicate and stale attempt`() =
        runTest {
            val run = responseRun(status = ResponseRunStatus.Streaming, attempt = 1)
            val repository = ControllerRepository(listOf(responseRunSnapshot(run, conversation(run))))
            val controller = controller(repository, scope = this)

            controller.start(conversation(run).id, run)
            advanceUntilIdle()
            controller.handleEvent(event(run, attempt = 1, seq = 1, payload = ResponseRunEventPayload.Thinking))
            controller.handleEvent(
                event(
                    run,
                    attempt = 1,
                    seq = 2,
                    payload =
                        ResponseRunEventPayload.ToolStarted(
                            activityId = "tool-1",
                            kind = ResponseRunActivityKindResponse.PlaceSearch,
                        ),
                ),
            )
            controller.handleEvent(event(run, attempt = 1, seq = 3, payload = ResponseRunEventPayload.Delta("hello")))
            controller.handleEvent(event(run, attempt = 1, seq = 3, payload = ResponseRunEventPayload.Delta(" duplicate")))
            controller.handleEvent(event(run, attempt = 0, seq = 4, payload = ResponseRunEventPayload.Delta(" stale")))

            val state = controller.state.value
            assertEquals("hello", state.partialText)
            assertEquals(3, state.lastSeq)
            assertEquals(ResponseRunActivityKind.PlaceSearch, state.activities.last().kind)
            controller.stop()
        }

    @Test
    fun `seq gap recovers from snapshot`() =
        runTest {
            val run = responseRun(status = ResponseRunStatus.Streaming, attempt = 1)
            val repository =
                ControllerRepository(
                    listOf(
                        responseRunSnapshot(run, conversation(run), partialText = "Hi", lastSeq = 2),
                        responseRunSnapshot(run, conversation(run), partialText = "Hi there", lastSeq = 4),
                    ),
                )
            val controller = controller(repository, scope = this)

            controller.start(conversation(run).id, run)
            advanceUntilIdle()
            controller.handleEvent(event(run, attempt = 1, seq = 4, payload = ResponseRunEventPayload.Delta("ignored")))
            advanceUntilIdle()

            assertEquals(listOf(run.id, run.id), repository.snapshotCommands)
            assertEquals("Hi there", controller.state.value.partialText)
            assertEquals(4, controller.state.value.lastSeq)
            controller.stop()
        }

    @Test
    fun `new attempt recovers snapshot and resets partial`() =
        runTest {
            val run = responseRun(status = ResponseRunStatus.Streaming, attempt = 1)
            val nextRun = run.copy(attempt = 2)
            val repository =
                ControllerRepository(
                    listOf(
                        responseRunSnapshot(run, conversation(run), partialText = "old", lastSeq = 7),
                        responseRunSnapshot(nextRun, conversation(nextRun), partialText = "", lastSeq = 0),
                    ),
                )
            val controller = controller(repository, scope = this)

            controller.start(conversation(run).id, run)
            advanceUntilIdle()
            controller.handleEvent(event(run, attempt = 2, seq = 1, payload = ResponseRunEventPayload.Delta("new")))
            advanceUntilIdle()

            assertEquals(2, controller.state.value.attempt)
            assertEquals("", controller.state.value.partialText)
            assertEquals(0, controller.state.value.lastSeq)
            controller.stop()
        }

    @Test
    fun `terminal event recovers durable snapshot`() =
        runTest {
            val run = responseRun(status = ResponseRunStatus.Streaming, attempt = 1)
            val completed = run.copy(status = ResponseRunStatus.Completed, assistantMessageId = "assistant-1")
            val repository =
                ControllerRepository(
                    listOf(
                        responseRunSnapshot(run, conversation(run), partialText = "draft", lastSeq = 0),
                        responseRunSnapshot(completed, conversation(completed, assistantText = "final"), partialText = "", lastSeq = 1),
                    ),
                )
            val controller = controller(repository, scope = this)

            controller.start(conversation(run).id, run)
            advanceUntilIdle()
            controller.handleEvent(event(run, attempt = 1, seq = 1, payload = ResponseRunEventPayload.Completed("assistant-1")))
            advanceUntilIdle()

            assertEquals(ResponseRunStatus.Completed, controller.state.value.terminalStatus)
            assertEquals("", controller.state.value.partialText)
            controller.stop()
        }

    @Test
    fun `closed connection reconnects through snapshot`() =
        runTest {
            val run = responseRun(status = ResponseRunStatus.Processing, attempt = 1)
            val repository =
                ControllerRepository(
                    listOf(
                        responseRunSnapshot(run, conversation(run), lastSeq = 0),
                        responseRunSnapshot(run, conversation(run), partialText = "after reconnect", lastSeq = 2),
                    ),
                )
            val requests = mutableListOf<RealtimeSseRequest>()
            val controller = controller(repository, scope = this, closeAttempts = 1, requests = requests)

            controller.start(conversation(run).id, run)
            advanceUntilIdle()

            assertEquals(listOf(run.id, run.id), repository.snapshotCommands)
            assertEquals("after reconnect", controller.state.value.partialText)
            assertEquals(listOf(null, "${run.id.value}:1:2"), requests.map { it.lastEventId })
            controller.stop()
        }

    @Test
    fun `reconnect exhausted polls snapshot fallback until terminal`() =
        runTest {
            val run = responseRun(status = ResponseRunStatus.Processing, attempt = 1)
            val completed = run.copy(status = ResponseRunStatus.Completed, assistantMessageId = "assistant-1")
            val repository =
                ControllerRepository(
                    listOf(
                        responseRunSnapshot(run, conversation(run), lastSeq = 0),
                        responseRunSnapshot(run, conversation(run), partialText = "still thinking", lastSeq = 1),
                        responseRunSnapshot(completed, conversation(completed, assistantText = "done"), partialText = "", lastSeq = 1),
                    ),
                )
            val logger = RecordingLogger()
            val controller =
                controller(
                    repository = repository,
                    scope = this,
                    closeAttempts = Int.MAX_VALUE,
                    snapshotFallbackIntervalMillis = 1,
                    logger = logger,
                )

            controller.start(conversation(run).id, run)
            advanceUntilIdle()

            assertEquals(listOf(run.id, run.id, run.id), repository.snapshotCommands)
            assertEquals(ResponseRunStatus.Completed, controller.state.value.terminalStatus)
            assertEquals("", controller.state.value.partialText)
            assertEquals(true, "response_run_recovery_started" in logger.events)
            assertEquals(true, "response_run_terminal_snapshot_resolved" in logger.events)
            controller.stop()
        }

    @Test
    fun `logs ignored raw and stale response run events with reason`() =
        runTest {
            val run = responseRun(status = ResponseRunStatus.Streaming, attempt = 2)
            val repository = ControllerRepository(listOf(responseRunSnapshot(run, conversation(run), lastSeq = 3)))
            val logger = RecordingLogger()
            val controller =
                controller(
                    repository = repository,
                    scope = this,
                    logger = logger,
                    transportEvents =
                        listOf(
                            RealtimeSseTransportEvent(id = "evt-other", type = "other", data = "{}"),
                            RealtimeSseTransportEvent(id = "evt-bad", type = "response-run", data = "{"),
                        ),
                )

            controller.start(conversation(run).id, run)
            advanceUntilIdle()
            controller.handleEvent(event(run, attempt = 1, seq = 4, payload = ResponseRunEventPayload.Delta("stale")))
            controller.handleEvent(event(run, attempt = 2, seq = 3, payload = ResponseRunEventPayload.Delta("duplicate")))

            val ignored = logger.entries.filter { it.event == "response_run_event_ignored" }
            assertEquals(
                listOf("wrong_event_type", "decode_failed", "stale_attempt", "duplicate_seq"),
                ignored.map { it.fields["ignored_reason"] },
            )
            assertEquals("evt-bad", ignored.single { it.fields["ignored_reason"] == "decode_failed" }.fields["sse_event_id"])
            assertEquals("1", ignored.single { it.fields["ignored_reason"] == "stale_attempt" }.fields["incoming_attempt"])
            controller.stop()
        }

    @Test
    fun `silent open connection polls snapshot fallback until terminal`() =
        runTest {
            val run = responseRun(status = ResponseRunStatus.Processing, attempt = 1)
            val completed = run.copy(status = ResponseRunStatus.Completed, assistantMessageId = "assistant-1")
            val repository =
                ControllerRepository(
                    listOf(
                        responseRunSnapshot(run, conversation(run), lastSeq = 1),
                        responseRunSnapshot(completed, conversation(completed, assistantText = "done"), partialText = "", lastSeq = 1),
                    ),
                )
            val controller =
                controller(
                    repository = repository,
                    scope = this,
                    silentOpenFallbackDelayMillis = 1,
                    snapshotFallbackIntervalMillis = 1,
                )

            controller.start(conversation(run).id, run)
            advanceUntilIdle()

            assertEquals(listOf(run.id, run.id), repository.snapshotCommands)
            assertEquals(ResponseRunStatus.Completed, controller.state.value.terminalStatus)
            controller.stop()
        }
}

private fun controller(
    repository: ControllerRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    closeAttempts: Int = 0,
    requests: MutableList<RealtimeSseRequest> = mutableListOf(),
    snapshotFallbackIntervalMillis: Long = 1,
    silentOpenFallbackDelayMillis: Long? = null,
    logger: AppLogger? = null,
    transportEvents: List<RealtimeSseTransportEvent> = emptyList(),
): ResponseRunStreamController =
    ResponseRunStreamController(
        repository = repository,
        sseSessionFactory =
            RealtimeSseSessionFactory(
                transport =
                    object : RealtimeSseTransport {
                        var remainingCloseAttempts = closeAttempts

                        override suspend fun collect(
                            request: RealtimeSseRequest,
                            bearerToken: String,
                            onEvent: suspend (RealtimeSseTransportEvent) -> Unit,
                        ) {
                            requests += request
                            onEvent(RealtimeSseTransportEvent.Opened)
                            transportEvents.forEach { event -> onEvent(event) }
                            if (remainingCloseAttempts > 0) {
                                remainingCloseAttempts -= 1
                                return
                            }
                            awaitCancellation()
                        }
                    },
                sessionProvider = { TokenSession },
            ),
        scope = scope,
        reconnectDelayMillis = 0,
        maxReconnectAttempts = 1,
        snapshotFallbackIntervalMillis = snapshotFallbackIntervalMillis,
        silentOpenFallbackDelayMillis = silentOpenFallbackDelayMillis,
        logger = logger,
    )

private object TokenSession : FirstPartyApiSession {
    override suspend fun currentAccessToken(): String = "token"

    override suspend fun refreshAccessTokenIfCurrent(accessToken: String): FirstPartySessionRefresh =
        FirstPartySessionRefresh.TokenAvailable(accessToken)

    override suspend fun clearSessionIfCurrent(accessToken: String): Boolean = false
}

private fun responseRun(
    status: ResponseRunStatus,
    attempt: Int,
): ResponseRun =
    ResponseRun(
        id = ResponseRunId("run-1"),
        userMessageId = "user-1",
        turnIndex = 1,
        status = status,
        stage = ResponseRunStage.Turn,
        attempt = attempt,
        retryable = status == ResponseRunStatus.FailedRetryable || status == ResponseRunStatus.Failed,
        assistantMessageId = null,
        failureCategory = null,
        createdAt = Instant.parse("2026-08-28T10:17:00Z"),
        updatedAt = Instant.parse("2026-08-28T10:17:01Z"),
        completedAt = null,
    )

private fun responseRunSnapshot(
    run: ResponseRun,
    conversation: ConversationDetail,
    partialText: String = "",
    lastSeq: Long = 0,
): ResponseRunSnapshot =
    ResponseRunSnapshot(
        run = run,
        conversation = conversation,
        streamAttempt = run.attempt,
        lastSeq = lastSeq,
        partialText = partialText,
        activities = emptyList(),
        realtimeSnapshotAvailable = true,
    )

private fun conversation(
    run: ResponseRun,
    assistantText: String? = null,
): ConversationDetail {
    val user =
        TaskMessage(
            id = run.userMessageId,
            role = MessageRole.User,
            content = "question",
            clientMessageId = "client-1",
            turnIndex = run.turnIndex,
            understoodAt = null,
        )
    val assistant =
        assistantText?.let {
            TaskMessage(
                id = "assistant-1",
                role = MessageRole.Assistant,
                content = it,
                clientMessageId = null,
                turnIndex = run.turnIndex,
                understoodAt = null,
            )
        }
    return ConversationDetail(
        id = ConversationId("conversation-1"),
        messages = listOfNotNull(user, assistant),
        responseRuns = listOf(run),
        currentTask = null,
    )
}

private fun event(
    run: ResponseRun,
    attempt: Int,
    seq: Long,
    payload: ResponseRunEventPayload,
): ResponseRunEventEnvelope =
    ResponseRunEventEnvelope(
        runId = run.id.value,
        attempt = attempt,
        seq = seq,
        occurredAt = Instant.parse("2026-08-28T10:17:02Z"),
        payload = payload,
    )

private class RecordingLogger : AppLogger {
    val events = mutableListOf<String>()
    val entries = mutableListOf<Entry>()

    override fun log(
        level: LogLevel,
        tag: LogTag,
        event: String,
        fields: LogFields,
        cause: Throwable?,
    ) {
        events += event
        entries += Entry(event, fields.values)
    }

    data class Entry(
        val event: String,
        val fields: Map<String, String>,
    )
}

private class ControllerRepository(
    snapshotResults: List<ResponseRunSnapshot>,
) : TaskRepository {
    val snapshotCommands = mutableListOf<ResponseRunId>()
    private val snapshotQueue = ArrayDeque(snapshotResults.map { Result.success(it) })

    override suspend fun loadTaskSummaries(): Result<List<TaskSummary>> = error("unused")

    override suspend fun createConversation(command: CreateConversationCommand): Result<ConversationDetail> = error("unused")

    override suspend fun loadConversationDetail(conversationId: ConversationId): Result<ConversationDetail> = error("unused")

    override suspend fun loadTaskDetail(taskId: com.nexusflow.app.feature.task.domain.TaskId): Result<TaskDetail> = error("unused")

    override suspend fun sendConversationMessage(command: SendConversationMessageCommand): Result<ConversationDetail> = error("unused")

    override suspend fun loadResponseRunSnapshot(
        conversationId: ConversationId,
        responseRunId: ResponseRunId,
    ): Result<ResponseRunSnapshot> {
        snapshotCommands += responseRunId
        return snapshotQueue.removeFirst()
    }

    override suspend fun cancelResponseRun(
        conversationId: ConversationId,
        responseRunId: ResponseRunId,
    ): Result<ResponseRun> = error("unused")

    override suspend fun retryResponseRun(
        conversationId: ConversationId,
        responseRunId: ResponseRunId,
    ): Result<ResponseRun> = error("unused")

    override suspend fun updateRequirement(command: UpdateRequirementCommand): Result<TaskDetail> = error("unused")

    override suspend fun removeRequirement(command: RemoveRequirementCommand): Result<TaskDetail> = error("unused")

    override suspend fun selectPlan(command: SelectPlanCommand): Result<TaskDetail> = error("unused")
}
