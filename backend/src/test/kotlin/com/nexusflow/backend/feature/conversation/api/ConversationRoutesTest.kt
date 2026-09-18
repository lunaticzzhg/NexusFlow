package com.nexusflow.backend.feature.conversation.api

import com.nexusflow.backend.core.http.configureHttpPlatform
import com.nexusflow.backend.core.identity.ActorContext
import com.nexusflow.backend.core.identity.ActorResolver
import com.nexusflow.backend.core.identity.UnauthenticatedException
import com.nexusflow.backend.feature.conversation.application.ConversationService
import com.nexusflow.backend.feature.conversation.application.ResponseRunRealtimeHub
import com.nexusflow.backend.feature.conversation.application.ResponseRunService
import com.nexusflow.backend.feature.conversation.domain.AppendConversationUserMessageCommand
import com.nexusflow.backend.feature.conversation.domain.AppendConversationUserMessageResult
import com.nexusflow.backend.feature.conversation.domain.CancelResponseRunCommand
import com.nexusflow.backend.feature.conversation.domain.CancelResponseRunResult
import com.nexusflow.backend.feature.conversation.domain.ClaimNextResponseRunCommand
import com.nexusflow.backend.feature.conversation.domain.ClaimedResponseRun
import com.nexusflow.backend.feature.conversation.domain.CompleteResponseRunAttemptCommand
import com.nexusflow.backend.feature.conversation.domain.ConversationDetail
import com.nexusflow.backend.feature.conversation.domain.ConversationId
import com.nexusflow.backend.feature.conversation.domain.ConversationRepository
import com.nexusflow.backend.feature.conversation.domain.CreateConversationCommand
import com.nexusflow.backend.feature.conversation.domain.CreateConversationResult
import com.nexusflow.backend.feature.conversation.domain.FailResponseRunAttemptCommand
import com.nexusflow.backend.feature.conversation.domain.HeartbeatResponseRunLeaseCommand
import com.nexusflow.backend.feature.conversation.domain.MarkResponseRunRetryableCommand
import com.nexusflow.backend.feature.conversation.domain.ResponseRun
import com.nexusflow.backend.feature.conversation.domain.ResponseRunFailureCategory
import com.nexusflow.backend.feature.conversation.domain.ResponseRunId
import com.nexusflow.backend.feature.conversation.domain.ResponseRunResult
import com.nexusflow.backend.feature.conversation.domain.RetryResponseRunCommand
import com.nexusflow.backend.feature.conversation.domain.RetryResponseRunResult
import com.nexusflow.backend.feature.conversation.domain.StoreResponseRunResult
import com.nexusflow.backend.feature.conversation.domain.StoreResponseRunResultCommand
import com.nexusflow.backend.feature.conversation.infrastructure.JdbcConversationRepository
import com.nexusflow.backend.feature.conversation.domain.ConsumeConversationAnswerResultCommand
import com.nexusflow.backend.feature.conversation.domain.ConsumeResponseRunResult
import com.nexusflow.backend.feature.task.RecordingConversationDecision
import com.nexusflow.backend.feature.task.ScriptedUnderstanding
import com.nexusflow.backend.feature.task.TaskFlowIds
import com.nexusflow.backend.feature.task.cleanMigrateAndSeed
import com.nexusflow.backend.feature.task.conversationAnswerService
import com.nexusflow.backend.feature.task.createConversationServices
import com.nexusflow.backend.feature.task.directConversationDecision
import com.nexusflow.backend.feature.task.postgresDataSource
import com.nexusflow.contracts.appbackend.common.KResponse
import com.nexusflow.contracts.appbackend.conversation.ConversationDetailResponse
import com.nexusflow.contracts.appbackend.conversation.CreateConversationRequest
import com.nexusflow.contracts.appbackend.conversation.CreateConversationResponse
import com.nexusflow.contracts.appbackend.conversation.ResponseRunEventEnvelope
import com.nexusflow.contracts.appbackend.conversation.ResponseRunEventPayload
import com.nexusflow.contracts.appbackend.conversation.ResponseRunStatusResponse
import com.nexusflow.contracts.appbackend.conversation.ResponseRunResponse
import com.nexusflow.contracts.appbackend.conversation.ResponseRunSnapshotResponse
import com.nexusflow.contracts.appbackend.conversation.SendConversationMessageRequest
import com.nexusflow.contracts.appbackend.conversation.SendConversationMessageResponse
import com.nexusflow.contracts.appbackend.task.MessageRole
import com.nexusflow.observability.LogFields
import com.nexusflow.observability.LogLevel
import com.nexusflow.observability.StructuredLogger
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.sql.SQLException
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConversationRoutesTest {
    @Test
    fun `conversation routes fast ack durable messages and expose queued response runs`() {
        val dataSource = postgresDataSource("Conversation routes")
        try {
            cleanMigrateAndSeed(dataSource)
            val understanding = ScriptedUnderstanding({
                error("Understanding must not run on POST accept")
            })
            val decision = RecordingConversationDecision({ directConversationDecision("不应同步回答。") })
            val services = createConversationServices(
                dataSource = dataSource,
                conversationAnswerService = conversationAnswerService(decision),
                understanding = understanding,
            )

            testApplication {
                application {
                    configureHttpPlatform()
                    routing {
                        conversationRoutes(services.conversationService, HeaderActorResolver)
                    }
                }

                val created = postJson<CreateConversationRequest, CreateConversationResponse>(
                    "/v1/conversations",
                    CreateConversationRequest("route-create", "你好", "Asia/Shanghai"),
                )
                assertNull(created.data.currentTask)
                assertEquals(listOf("你好"), created.data.conversation.messages.map { it.content })
                assertEquals(listOf(MessageRole.User), created.data.conversation.messages.map { it.role })
                assertEquals(listOf(1L), created.data.conversation.messages.map { it.turnIndex })
                assertEquals(listOf(1L), created.data.conversation.responseRuns.map { it.turnIndex })
                assertEquals(listOf(ResponseRunStatusResponse.Queued), created.data.conversation.responseRuns.map { it.status })

                val sent = postJson<SendConversationMessageRequest, SendConversationMessageResponse>(
                    "/v1/conversations/${created.data.conversation.id}/messages",
                    SendConversationMessageRequest("route-send", "继续", "Asia/Shanghai"),
                )
                assertNull(sent.data.currentTask)
                assertEquals(listOf("你好", "继续"), sent.data.conversation.messages.map { it.content })
                assertEquals(listOf(1L, 2L), sent.data.conversation.messages.map { it.turnIndex })
                assertEquals(listOf(1L, 2L), sent.data.conversation.responseRuns.map { it.turnIndex })
                assertEquals(
                    listOf(ResponseRunStatusResponse.Queued, ResponseRunStatusResponse.Queued),
                    sent.data.conversation.responseRuns.map { it.status },
                )

                val loaded = getJson<ConversationDetailResponse>("/v1/conversations/${created.data.conversation.id}")
                assertEquals(sent.data.conversation.messages, loaded.data.conversation.messages)
                assertEquals(sent.data.conversation.responseRuns, loaded.data.conversation.responseRuns)
                assertNull(loaded.data.currentTask)
                assertEquals(emptyList(), understanding.calls)
                assertEquals(emptyList(), decision.requests)
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `duplicate route posts return same messages and runs without invoking AI`() {
        val dataSource = postgresDataSource("Conversation routes replay")
        try {
            cleanMigrateAndSeed(dataSource)
            val understanding = ScriptedUnderstanding({
                error("Understanding must not run for duplicate replay")
            })
            val services = createConversationServices(
                dataSource = dataSource,
                understanding = understanding,
            )

            testApplication {
                application {
                    configureHttpPlatform()
                    routing { conversationRoutes(services.conversationService, HeaderActorResolver) }
                }

                val first = postJson<CreateConversationRequest, CreateConversationResponse>(
                    "/v1/conversations",
                    CreateConversationRequest("route-replay-create", "你好", "Asia/Shanghai"),
                )
                val replay = postJson<CreateConversationRequest, CreateConversationResponse>(
                    "/v1/conversations",
                    CreateConversationRequest("route-replay-create", "你好", "Asia/Shanghai"),
                )
                val sent = postJson<SendConversationMessageRequest, SendConversationMessageResponse>(
                    "/v1/conversations/${first.data.conversation.id}/messages",
                    SendConversationMessageRequest("route-replay-message", "再说一次", "Asia/Shanghai"),
                )
                val sentReplay = postJson<SendConversationMessageRequest, SendConversationMessageResponse>(
                    "/v1/conversations/${first.data.conversation.id}/messages",
                    SendConversationMessageRequest("route-replay-message", "再说一次", "Asia/Shanghai"),
                )

                assertEquals(first.data.conversation.id, replay.data.conversation.id)
                assertEquals(first.data.conversation.messages, replay.data.conversation.messages)
                assertEquals(first.data.conversation.responseRuns, replay.data.conversation.responseRuns)
                assertEquals(sent.data.conversation.messages, sentReplay.data.conversation.messages)
                assertEquals(sent.data.conversation.responseRuns, sentReplay.data.conversation.responseRuns)
                assertEquals(2, sentReplay.data.conversation.messages.size)
                assertEquals(2, sentReplay.data.conversation.responseRuns.size)
                assertEquals(emptyList(), understanding.calls)
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `response run snapshot exposes durable run and current realtime projection`() {
        val dataSource = postgresDataSource("Response run snapshot routes")
        try {
            cleanMigrateAndSeed(dataSource)
            val services = createConversationServices(dataSource = dataSource, understanding = null)
            val conversationRepository = JdbcConversationRepository(dataSource)
            val realtimeHub = ResponseRunRealtimeHub(TaskFlowIds.FixedClock)
            val responseRunService = ResponseRunService(conversationRepository, services.repository, realtimeHub, TaskFlowIds.FixedClock)

            testApplication {
                application {
                    configureHttpPlatform()
                    routing {
                        conversationRoutes(services.conversationService, HeaderActorResolver, responseRunService)
                    }
                }

                val created = postJson<CreateConversationRequest, CreateConversationResponse>(
                    "/v1/conversations",
                    CreateConversationRequest("route-snapshot-create", "你好", "Asia/Shanghai"),
                )
                val runId = created.data.conversation.responseRuns.single().id
                val run = assertNotNull(conversationRepository.findResponseRun(ResponseRunId(UUID.fromString(runId))))
                realtimeHub.delta(run, "partial")

                val snapshot = getJson<ResponseRunSnapshotResponse>(
                    "/v1/conversations/${created.data.conversation.id}/response-runs/$runId",
                )

                assertEquals(runId, snapshot.data.run.id)
                assertEquals(created.data.conversation.id, snapshot.data.conversation.id)
                assertEquals(run.attempt, snapshot.data.streamAttempt)
                assertEquals(1, snapshot.data.lastSeq)
                assertEquals("partial", snapshot.data.partialText)
                assertEquals(true, snapshot.data.realtimeSnapshotAvailable)
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `response run sse emits snapshot event before hub events`() {
        val dataSource = postgresDataSource("Response run sse route")
        try {
            cleanMigrateAndSeed(dataSource)
            val services = createConversationServices(dataSource = dataSource, understanding = null)
            val conversationRepository = JdbcConversationRepository(dataSource)
            val responseRunService = ResponseRunService(
                conversationRepository,
                services.repository,
                ResponseRunRealtimeHub(TaskFlowIds.FixedClock),
                TaskFlowIds.FixedClock,
            )
            val logger = RecordingStructuredLogger()

            testApplication {
                application {
                    configureHttpPlatform()
                    routing {
                        conversationRoutes(services.conversationService, HeaderActorResolver, responseRunService, logger)
                    }
                }
                val sseClient = createClient {
                    install(SSE)
                }
                val created = postJson<CreateConversationRequest, CreateConversationResponse>(
                    "/v1/conversations",
                    CreateConversationRequest("route-sse-create", "你好", "Asia/Shanghai"),
                )
                val runId = created.data.conversation.responseRuns.single().id

                sseClient.sse("/v1/conversations/${created.data.conversation.id}/response-runs/$runId/events", {
                    headers.append("X-Orbit-Tenant", TaskFlowIds.TenantOne.toString())
                    headers.append("X-Orbit-User", TaskFlowIds.UserOne.toString())
                    headers.append("X-Orbit-Scopes", "orbit.tasks.read orbit.tasks.write")
                }) {
                    val first = incoming.first()
                    val envelope = JsonFormat.decodeFromString<ResponseRunEventEnvelope>(first.data!!)
                    assertEquals("response-run", first.event)
                    assertEquals("$runId:0:0", first.id)
                    assertEquals(ResponseRunEventPayload.Snapshot::class, envelope.payload::class)
                    assertEquals(runId, envelope.runId)
                }
                assertEquals(
                    runId,
                    logger.entries.single { it.event == "response_run_sse_snapshot_sent" }.fields["response_run_id"],
                )
                assertEquals(
                    "snapshot",
                    logger.entries.single { it.event == "response_run_sse_snapshot_sent" }.fields["payload_type"],
                )
                assertEquals(
                    runId,
                    logger.entries.single { it.event == "response_run_sse_connected" }.fields["response_run_id"],
                )
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `response run sse replays buffered events after last event id`() {
        val dataSource = postgresDataSource("Response run sse replay route")
        try {
            cleanMigrateAndSeed(dataSource)
            val services = createConversationServices(dataSource = dataSource, understanding = null)
            val conversationRepository = JdbcConversationRepository(dataSource)
            val realtimeHub = ResponseRunRealtimeHub(TaskFlowIds.FixedClock)
            val responseRunService = ResponseRunService(
                conversationRepository,
                services.repository,
                realtimeHub,
                TaskFlowIds.FixedClock,
            )
            val logger = RecordingStructuredLogger()

            testApplication {
                application {
                    configureHttpPlatform()
                    routing {
                        conversationRoutes(services.conversationService, HeaderActorResolver, responseRunService, logger)
                    }
                }
                val sseClient = createClient {
                    install(SSE)
                }
                val created = postJson<CreateConversationRequest, CreateConversationResponse>(
                    "/v1/conversations",
                    CreateConversationRequest("route-sse-replay-create", "你好", "Asia/Shanghai"),
                )
                val runId = created.data.conversation.responseRuns.single().id
                val run = assertNotNull(conversationRepository.findResponseRun(ResponseRunId(UUID.fromString(runId))))
                realtimeHub.delta(run, "first")
                realtimeHub.delta(run, "second")

                sseClient.sse("/v1/conversations/${created.data.conversation.id}/response-runs/$runId/events", {
                    headers.append("X-Orbit-Tenant", TaskFlowIds.TenantOne.toString())
                    headers.append("X-Orbit-User", TaskFlowIds.UserOne.toString())
                    headers.append("X-Orbit-Scopes", "orbit.tasks.read orbit.tasks.write")
                    headers.append("Last-Event-ID", "$runId:${run.attempt}:1")
                }) {
                    val events = incoming.take(2).toList()
                    val snapshot = JsonFormat.decodeFromString<ResponseRunEventEnvelope>(events[0].data!!)
                    val replay = JsonFormat.decodeFromString<ResponseRunEventEnvelope>(events[1].data!!)

                    assertEquals(ResponseRunEventPayload.Snapshot::class, snapshot.payload::class)
                    assertEquals(2, replay.seq)
                    assertEquals(ResponseRunEventPayload.Delta("second"), replay.payload)
                    assertEquals("$runId:${run.attempt}:2", events[1].id)
                }
                val liveSent = logger.entries.single { it.event == "response_run_sse_event_sent" }
                assertEquals(runId, liveSent.fields["response_run_id"])
                assertEquals("2", liveSent.fields["seq"])
                assertEquals("delta", liveSent.fields["payload_type"])
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `response run routes enforce owner without leaking foreign run existence`() {
        val dataSource = postgresDataSource("Response run route access")
        try {
            cleanMigrateAndSeed(dataSource)
            val services = createConversationServices(dataSource = dataSource, understanding = null)
            val conversationRepository = JdbcConversationRepository(dataSource)
            val responseRunService = ResponseRunService(
                conversationRepository,
                services.repository,
                ResponseRunRealtimeHub(TaskFlowIds.FixedClock),
                TaskFlowIds.FixedClock,
            )

            testApplication {
                application {
                    configureHttpPlatform()
                    routing {
                        conversationRoutes(services.conversationService, HeaderActorResolver, responseRunService)
                    }
                }

                val created = postJson<CreateConversationRequest, CreateConversationResponse>(
                    "/v1/conversations",
                    CreateConversationRequest("route-access-create", "你好", "Asia/Shanghai"),
                )
                val runId = created.data.conversation.responseRuns.single().id

                val foreign = client.get("/v1/conversations/${created.data.conversation.id}/response-runs/$runId") {
                    actor(tenantId = TaskFlowIds.TenantTwo.toString())
                }

                assertEquals(HttpStatusCode.NotFound, foreign.status)
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `cancel is idempotent and retry only queues retryable states`() {
        val dataSource = postgresDataSource("Response run cancel retry routes")
        try {
            cleanMigrateAndSeed(dataSource)
            val services = createConversationServices(dataSource = dataSource, understanding = null)
            val conversationRepository = JdbcConversationRepository(dataSource)
            val responseRunService = ResponseRunService(
                conversationRepository,
                services.repository,
                ResponseRunRealtimeHub(TaskFlowIds.FixedClock),
                TaskFlowIds.FixedClock,
            )

            testApplication {
                application {
                    configureHttpPlatform()
                    routing {
                        conversationRoutes(services.conversationService, HeaderActorResolver, responseRunService)
                    }
                }

                val cancelConversation = postJson<CreateConversationRequest, CreateConversationResponse>(
                    "/v1/conversations",
                    CreateConversationRequest("route-cancel-create", "取消这个", "Asia/Shanghai"),
                )
                val cancelRunId = cancelConversation.data.conversation.responseRuns.single().id
                val cancelled = postEmpty<ResponseRunResponse>(
                    "/v1/conversations/${cancelConversation.data.conversation.id}/response-runs/$cancelRunId/cancel",
                )
                val cancelledAgain = postEmpty<ResponseRunResponse>(
                    "/v1/conversations/${cancelConversation.data.conversation.id}/response-runs/$cancelRunId/cancel",
                )
                assertEquals(ResponseRunStatusResponse.Cancelled, cancelled.data.status)
                assertEquals(ResponseRunStatusResponse.Cancelled, cancelledAgain.data.status)

                val retryConversation = postJson<CreateConversationRequest, CreateConversationResponse>(
                    "/v1/conversations",
                    CreateConversationRequest("route-retry-create", "重试这个", "Asia/Shanghai"),
                )
                val retryRunId = ResponseRunId(UUID.fromString(retryConversation.data.conversation.responseRuns.single().id))
                val claim = assertNotNull(
                    conversationRepository.claimNextResponseRun(
                        ClaimNextResponseRunCommand(
                            workerId = "route-test-worker",
                            now = TaskFlowIds.Now,
                            leaseDuration = java.time.Duration.ofSeconds(30),
                            maxAttempts = 3,
                        ),
                    ),
                )
                assertEquals(retryRunId, claim.run.id)
                assertEquals(
                    true,
                    conversationRepository.markResponseRunRetryable(
                        MarkResponseRunRetryableCommand(
                            responseRunId = retryRunId,
                            attempt = claim.run.attempt,
                            now = TaskFlowIds.Now.plusSeconds(1),
                            retryAt = TaskFlowIds.Now.plusSeconds(30),
                            failureCategory = ResponseRunFailureCategory.ProviderTemporary,
                        ),
                    ),
                )

                val retried = postEmpty<ResponseRunResponse>(
                    "/v1/conversations/${retryConversation.data.conversation.id}/response-runs/${retryRunId.value}/retry",
                )
                val retryCancelled = client.post(
                    "/v1/conversations/${cancelConversation.data.conversation.id}/response-runs/$cancelRunId/retry",
                ) {
                    actor()
                }

                assertEquals(ResponseRunStatusResponse.Queued, retried.data.status)
                assertEquals(HttpStatusCode.UnprocessableEntity, retryCancelled.status)

                val retriedClaim = assertNotNull(
                    conversationRepository.claimNextResponseRun(
                        ClaimNextResponseRunCommand(
                            workerId = "route-test-worker-two",
                            now = TaskFlowIds.Now.plusSeconds(31),
                            leaseDuration = java.time.Duration.ofSeconds(30),
                            maxAttempts = 3,
                        ),
                    ),
                )
                assertEquals(retryRunId, retriedClaim.run.id)
                assertEquals(
                    true,
                    conversationRepository.completeResponseRunAttempt(
                        CompleteResponseRunAttemptCommand(
                            responseRunId = retryRunId,
                            attempt = retriedClaim.run.attempt,
                            now = TaskFlowIds.Now.plusSeconds(32),
                        ),
                    ),
                )
                val completedCancel = postEmpty<ResponseRunResponse>(
                    "/v1/conversations/${retryConversation.data.conversation.id}/response-runs/${retryRunId.value}/cancel",
                )
                assertEquals(ResponseRunStatusResponse.Completed, completedCancel.data.status)
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `primary durable commit failure returns http failure`() {
        val dataSource = postgresDataSource("Conversation routes primary failure")
        try {
            cleanMigrateAndSeed(dataSource)
            val dependencies = createConversationServices(
                dataSource = dataSource,
                understanding = null,
            )
            val failingService = ConversationService(
                conversationRepository = FailingConversationRepository,
                taskRepository = dependencies.repository,
                planningService = dependencies.planningService,
            )

            testApplication {
                application {
                    configureHttpPlatform()
                    routing { conversationRoutes(failingService, HeaderActorResolver) }
                }

                val response = client.post("/v1/conversations") {
                    actor()
                    contentType(ContentType.Application.Json)
                    setBody(JsonFormat.encodeToString(CreateConversationRequest("route-db-failure", "你好", "Asia/Shanghai")))
                }

                assertEquals(HttpStatusCode.InternalServerError, response.status)
            }
        } finally {
            dataSource.close()
        }
    }

    private suspend inline fun <reified B, reified R> ApplicationTestBuilder.postJson(
        path: String,
        body: B,
    ): DecodedResponse<R> {
        val response = client.post(path) {
            actor()
            contentType(ContentType.Application.Json)
            setBody(JsonFormat.encodeToString(body))
        }
        return response.decode()
    }

    private suspend inline fun <reified R> ApplicationTestBuilder.getJson(path: String): DecodedResponse<R> {
        val response = client.get(path) {
            actor()
        }
        return response.decode()
    }

    private suspend inline fun <reified R> ApplicationTestBuilder.postEmpty(path: String): DecodedResponse<R> {
        val response = client.post(path) {
            actor()
        }
        return response.decode()
    }

    private suspend inline fun <reified T> HttpResponse.decode(): DecodedResponse<T> {
        assertEquals(HttpStatusCode.OK, status, bodyAsText())
        val body = bodyAsText()
        return DecodedResponse(JsonFormat.decodeFromString<KResponse<T>>(body).data!!, body)
    }

    private fun HttpRequestBuilder.actor(
        scopes: String = "orbit.tasks.read orbit.tasks.write",
        tenantId: String = TaskFlowIds.TenantOne.toString(),
        userId: String = TaskFlowIds.UserOne.toString(),
    ) {
        header("X-Orbit-Tenant", tenantId)
        header("X-Orbit-User", userId)
        header("X-Orbit-Scopes", scopes)
    }

    private data class DecodedResponse<T>(
        val data: T,
        val rawBody: String,
    )

    private object HeaderActorResolver : ActorResolver {
        override fun resolve(call: ApplicationCall): ActorContext =
            ActorContext(
                tenantId = call.request.headers["X-Orbit-Tenant"] ?: throw UnauthenticatedException(),
                userId = call.request.headers["X-Orbit-User"] ?: throw UnauthenticatedException(),
                scopes = call.request.headers["X-Orbit-Scopes"]
                    ?.split(" ")
                    ?.filter(String::isNotBlank)
                    ?.toSet()
                    ?: emptySet(),
            )
    }

    private object FailingConversationRepository : ConversationRepository {
        override suspend fun createConversation(command: CreateConversationCommand): CreateConversationResult =
            throw SQLException("primary commit failed")

        override suspend fun findConversationDetail(
            owner: com.nexusflow.backend.feature.task.domain.TaskOwner,
            conversationId: ConversationId,
        ): ConversationDetail? = throw SQLException("primary read failed")

        override suspend fun findConversationDetailForResponseRun(responseRunId: ResponseRunId): ConversationDetail? =
            throw SQLException("primary read failed")

        override suspend fun appendUserMessage(command: AppendConversationUserMessageCommand): AppendConversationUserMessageResult =
            throw SQLException("primary commit failed")

        override suspend fun claimNextResponseRun(command: ClaimNextResponseRunCommand): ClaimedResponseRun? =
            throw SQLException("primary claim failed")

        override suspend fun heartbeatResponseRunLease(command: HeartbeatResponseRunLeaseCommand): Boolean =
            throw SQLException("primary heartbeat failed")

        override suspend fun completeResponseRunAttempt(command: CompleteResponseRunAttemptCommand): Boolean =
            throw SQLException("primary finalize failed")

        override suspend fun failResponseRunAttempt(command: FailResponseRunAttemptCommand): Boolean =
            throw SQLException("primary finalize failed")

        override suspend fun markResponseRunRetryable(command: MarkResponseRunRetryableCommand): Boolean =
            throw SQLException("primary retry failed")

        override suspend fun findResponseRun(responseRunId: ResponseRunId): ResponseRun? =
            throw SQLException("primary read failed")

        override suspend fun cancelResponseRun(command: CancelResponseRunCommand): CancelResponseRunResult =
            throw SQLException("primary cancel failed")

        override suspend fun retryResponseRun(command: RetryResponseRunCommand): RetryResponseRunResult =
            throw SQLException("primary retry failed")

        override suspend fun storeResponseRunResult(command: StoreResponseRunResultCommand): StoreResponseRunResult =
            throw SQLException("primary result failed")

        override suspend fun findResponseRunResult(
            responseRunId: ResponseRunId,
            attempt: Int,
        ): ResponseRunResult? = throw SQLException("primary result read failed")

        override suspend fun findConsumableResponseRunForResult(result: ResponseRunResult): ResponseRun? =
            throw SQLException("primary result read failed")

        override suspend fun consumeConversationAnswerResult(
            command: ConsumeConversationAnswerResultCommand,
        ): ConsumeResponseRunResult = throw SQLException("primary result consume failed")

        override suspend fun queuePlanningStageFromResult(
            command: com.nexusflow.backend.feature.conversation.domain.QueuePlanningStageFromResultCommand,
        ): ConsumeResponseRunResult = throw SQLException("primary result consume failed")

        override suspend fun completePlanningResult(
            command: com.nexusflow.backend.feature.conversation.domain.CompletePlanningResultCommand,
        ): ConsumeResponseRunResult = throw SQLException("primary result consume failed")
    }

    private class RecordingStructuredLogger : StructuredLogger {
        val entries = mutableListOf<Entry>()

        override fun log(
            level: LogLevel,
            component: String,
            event: String,
            fields: LogFields,
            cause: Throwable?,
        ) {
            entries += Entry(component, event, fields.values)
        }

        data class Entry(
            val component: String,
            val event: String,
            val fields: Map<String, String>,
        )
    }

    private companion object {
        val JsonFormat = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = false
        }
    }
}
