package com.nexusflow.backend.feature.conversation.infrastructure

import com.nexusflow.backend.core.config.RuntimeEnvironment
import com.nexusflow.backend.core.observability.BackendTraceContext
import com.nexusflow.backend.feature.conversation.application.ConversationTurnProcessor
import com.nexusflow.backend.feature.conversation.application.ResponseRunActivityKind
import com.nexusflow.backend.feature.conversation.application.ResponseRunRealtimeHub
import com.nexusflow.backend.feature.conversation.application.ResponseRunEventPayload
import com.nexusflow.backend.feature.conversation.application.ConversationTurnTerminalKind
import com.nexusflow.backend.feature.conversation.application.ResponseRunResultConsumer
import com.nexusflow.backend.feature.conversation.application.ResponseRunWorker
import com.nexusflow.backend.feature.conversation.application.ResponseRunWorkerConfig
import com.nexusflow.backend.feature.conversation.application.answer.ConversationAnswerService
import com.nexusflow.backend.feature.task.application.PlanningService
import com.nexusflow.backend.feature.responserun.domain.ClaimNextResponseRunCommand
import com.nexusflow.backend.feature.responserun.domain.ClaimedResponseRun
import com.nexusflow.backend.feature.responserun.domain.ConsumeResponseRunIgnoreReason
import com.nexusflow.backend.feature.responserun.domain.ConsumeResponseRunResult
import com.nexusflow.backend.feature.conversation.domain.ConversationId
import com.nexusflow.backend.feature.conversation.domain.CreateConversationCommand
import com.nexusflow.backend.feature.conversation.domain.CreateConversationResult
import com.nexusflow.backend.feature.responserun.domain.FailResponseRunAttemptCommand
import com.nexusflow.backend.feature.responserun.domain.MarkResponseRunRetryableCommand
import com.nexusflow.backend.feature.responserun.domain.ResponseRunFailureCategory
import com.nexusflow.backend.feature.responserun.domain.ResponseRunId
import com.nexusflow.backend.feature.responserun.domain.ResponseRunResult
import com.nexusflow.backend.feature.responserun.domain.ResponseRunResultPayload
import com.nexusflow.backend.feature.responserun.domain.ResponseRunStage
import com.nexusflow.backend.feature.responserun.domain.ResponseRunStatus
import com.nexusflow.backend.feature.responserun.domain.StoreResponseRunResult
import com.nexusflow.backend.feature.responserun.domain.StoreResponseRunResultCommand
import com.nexusflow.backend.feature.responserun.infrastructure.JdbcResponseRunRepository
import com.nexusflow.backend.feature.responserun.domain.RequirementValuePayload
import com.nexusflow.backend.feature.responserun.domain.RequirementWritePayload
import com.nexusflow.backend.feature.research.application.ReadToolCatalog
import com.nexusflow.backend.feature.research.application.ReadToolActivityKind
import com.nexusflow.backend.feature.research.application.ReadToolExecutor
import com.nexusflow.backend.feature.research.application.ReadToolOutcome
import com.nexusflow.backend.feature.task.ControlledPlanningReadTool
import com.nexusflow.backend.feature.task.RecordingConversationDecision
import com.nexusflow.backend.feature.task.RecordingQuestionAnswering
import com.nexusflow.backend.feature.task.RecordingPlanningResearch
import com.nexusflow.backend.feature.task.ScriptedUnderstanding
import com.nexusflow.backend.feature.task.TaskFlowIds
import com.nexusflow.backend.feature.research.application.readtool.MovieShowtimesKey
import com.nexusflow.backend.feature.research.application.readtool.SportsFixturesKey
import com.nexusflow.backend.feature.task.activityDomainChange
import com.nexusflow.backend.feature.task.budgetChange
import com.nexusflow.backend.feature.task.cleanMigrateAndSeed
import com.nexusflow.backend.feature.task.conversationAnswerService
import com.nexusflow.backend.feature.task.createConversationServices
import com.nexusflow.backend.feature.task.directConversationDecision
import com.nexusflow.backend.feature.task.mixedWeatherAndActivityDecision
import com.nexusflow.backend.feature.conversation.domain.MessageId
import com.nexusflow.backend.feature.conversation.domain.MessageRole
import com.nexusflow.backend.feature.task.domain.ApplyConversationUnderstandingCommand
import com.nexusflow.backend.feature.task.domain.ApplyUnderstandingResult
import com.nexusflow.backend.feature.task.domain.CreateLinkedTaskPersistenceCommand
import com.nexusflow.backend.feature.task.domain.CreateLinkedTaskPersistenceResult
import com.nexusflow.backend.feature.task.domain.RequirementKind
import com.nexusflow.backend.feature.task.domain.RequirementMutationResult
import com.nexusflow.backend.feature.task.domain.RequirementId
import com.nexusflow.backend.feature.task.domain.RequirementStrength
import com.nexusflow.backend.feature.task.domain.RequirementValue
import com.nexusflow.backend.feature.task.domain.RequirementWrite
import com.nexusflow.backend.feature.task.domain.TaskId
import com.nexusflow.backend.feature.task.domain.TaskOwner
import com.nexusflow.backend.feature.task.domain.TenantId
import com.nexusflow.backend.feature.task.domain.UpdateRequirementCommand
import com.nexusflow.backend.feature.task.domain.UserId
import com.nexusflow.backend.feature.task.infrastructure.JdbcTaskRepository
import com.nexusflow.backend.feature.task.postgresDataSource
import com.nexusflow.backend.feature.task.taskActor
import com.nexusflow.backend.feature.task.toolProposal
import com.nexusflow.backend.feature.task.understandingOutcome
import com.nexusflow.contracts.backendai.understanding.TurnIntent
import com.nexusflow.contracts.backendai.conversation.ConversationDecisionResult
import com.nexusflow.contracts.backendai.conversation.InformationNeedMode
import com.nexusflow.contracts.backendai.conversation.InformationNeedProposal
import com.nexusflow.contracts.backendai.answer.ResearchIssueType
import com.nexusflow.contracts.backendai.answer.StreamingConversationAnsweringCapability
import com.nexusflow.contracts.backendai.common.CapabilityUnavailableException
import com.nexusflow.observability.DefaultStructuredLogger
import com.nexusflow.observability.JsonLogFormatter
import com.nexusflow.observability.LogLevel
import com.nexusflow.observability.LogSink
import com.nexusflow.observability.StructuredLogger
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdbcResponseRunResultBusTest {
    private lateinit var dataSource: HikariDataSource
    private lateinit var repository: JdbcConversationRepository
    private lateinit var turnStartCommitter: JdbcConversationTurnStartCommitter
    private lateinit var responseRunRepository: JdbcResponseRunRepository
    private lateinit var taskRepository: JdbcTaskRepository
    private lateinit var consumer: ResponseRunResultConsumer

    @BeforeTest
    fun setUp() {
        dataSource = postgresDataSource("Response run result bus")
        cleanMigrateAndSeed(dataSource)
        repository = JdbcConversationRepository(dataSource)
        turnStartCommitter = JdbcConversationTurnStartCommitter(dataSource)
        responseRunRepository = JdbcResponseRunRepository(dataSource)
        taskRepository = JdbcTaskRepository(dataSource)
        consumer = ResponseRunResultConsumer(JdbcConversationAnswerCommitter(dataSource), taskRepository, TaskFlowIds.FixedClock)
    }

    @AfterTest
    fun tearDown() {
        if (::dataSource.isInitialized) {
            dataSource.close()
        }
    }

    @Test
    fun `duplicate result only inserts one assistant`() =
        runBlocking {
            val created = createConversation("00000000-0000-0000-0000-000000004001")
            val claim = assertNotNull(claim())
            val result = storeAnswerResult(created, claim)

            assertIs<ConsumeResponseRunResult.Consumed>(consumer.consume(result))
            assertIs<StoreResponseRunResult.Existing>(
                responseRunRepository.storeResponseRunResult(
                    StoreResponseRunResultCommand(
                        responseRunId = claim.run.id,
                        attempt = claim.run.attempt,
                        payload = answerPayload(created, "Duplicate ignored", UUID.randomUUID()),
                        now = TaskFlowIds.Now.plusSeconds(2),
                    ),
                ),
            )
            assertIs<ConsumeResponseRunResult.AlreadyConsumed>(consumer.consume(result))

            val detail = repository.findConversationDetail(owner(), created.conversationId)
            assertEquals(listOf(MessageRole.User, MessageRole.Assistant), detail?.messages?.map { it.role })
            assertEquals(1, detail?.messages?.count { it.role == MessageRole.Assistant })
            assertEquals(ResponseRunStatus.Completed, responseRunRepository.findResponseRun(created.runId)?.status)
        }

    @Test
    fun `stale attempt result is ignored after lease reclaim`() =
        runBlocking {
            val created = createConversation("00000000-0000-0000-0000-000000004011")
            val firstClaim = assertNotNull(claim(leaseDuration = Duration.ofSeconds(5)))
            val staleResult = storeAnswerResult(created, firstClaim)
            val reclaimed = assertNotNull(
                claim(
                    workerId = "worker-two",
                    now = TaskFlowIds.Now.plusSeconds(6),
                    leaseDuration = Duration.ofSeconds(5),
                ),
            )

            val ignored = assertIs<ConsumeResponseRunResult.Ignored>(consumer.consume(staleResult))
            assertEquals(ConsumeResponseRunIgnoreReason.AttemptMismatch, ignored.reason)

            val detail = repository.findConversationDetail(owner(), created.conversationId)
            assertEquals(listOf(MessageRole.User), detail?.messages?.map { it.role })
            assertEquals(reclaimed.run.attempt, responseRunRepository.findResponseRun(created.runId)?.attempt)
            assertEquals(ResponseRunStatus.Processing, responseRunRepository.findResponseRun(created.runId)?.status)
        }

    @Test
    fun `terminal run result is ignored`() =
        runBlocking {
            val created = createConversation("00000000-0000-0000-0000-000000004021")
            val claim = assertNotNull(claim())
            val result = storeAnswerResult(created, claim)
            assertEquals(
                true,
                responseRunRepository.failResponseRunAttempt(
                    FailResponseRunAttemptCommand(
                        responseRunId = claim.run.id,
                        attempt = claim.run.attempt,
                        now = TaskFlowIds.Now.plusSeconds(2),
                        failureCategory = ResponseRunFailureCategory.InternalInvariant,
                    ),
                ),
            )

            val ignored = assertIs<ConsumeResponseRunResult.Ignored>(consumer.consume(result))
            assertEquals(ConsumeResponseRunIgnoreReason.RunNotConsumable, ignored.reason)

            val detail = repository.findConversationDetail(owner(), created.conversationId)
            assertEquals(listOf(MessageRole.User), detail?.messages?.map { it.role })
            assertEquals(ResponseRunStatus.Failed, responseRunRepository.findResponseRun(created.runId)?.status)
        }

    @Test
    fun `failed retryable run result is ignored without partial writes`() =
        runBlocking {
            val created = createConversation("00000000-0000-0000-0000-000000004026")
            val claim = assertNotNull(claim())
            val result = storeAnswerResult(created, claim)
            assertEquals(
                true,
                responseRunRepository.markResponseRunRetryable(
                    MarkResponseRunRetryableCommand(
                        responseRunId = claim.run.id,
                        attempt = claim.run.attempt,
                        now = TaskFlowIds.Now.plusSeconds(2),
                        retryAt = TaskFlowIds.Now.plusSeconds(10),
                        failureCategory = ResponseRunFailureCategory.ProviderTemporary,
                    ),
                ),
            )

            val ignored = assertIs<ConsumeResponseRunResult.Ignored>(consumer.consume(result))
            assertEquals(ConsumeResponseRunIgnoreReason.RunNotConsumable, ignored.reason)

            assertNoConversationAnswerSideEffects(created, expectedStatus = ResponseRunStatus.FailedRetryable)
        }

    @Test
    fun `queued run result is ignored without partial writes`() =
        runBlocking {
            val created = createConversation("00000000-0000-0000-0000-000000004027")
            val claim = assertNotNull(claim())
            val result = storeAnswerResult(created, claim)
            forceRunQueued(created.runId)

            val ignored = assertIs<ConsumeResponseRunResult.Ignored>(consumer.consume(result))
            assertEquals(ConsumeResponseRunIgnoreReason.RunNotConsumable, ignored.reason)

            assertNoConversationAnswerSideEffects(created, expectedStatus = ResponseRunStatus.Queued)
        }

    @Test
    fun `consumer can retry after result store without redoing AI`() =
        runBlocking {
            val created = createConversation("00000000-0000-0000-0000-000000004031")
            val claim = assertNotNull(claim())
            val result = storeAnswerResult(created, claim)
            assertEquals(listOf(MessageRole.User), repository.findConversationDetail(owner(), created.conversationId)?.messages?.map { it.role })

            assertIs<ConsumeResponseRunResult.Consumed>(consumer.consume(result))

            val detail = repository.findConversationDetail(owner(), created.conversationId)
            assertEquals(listOf(MessageRole.User, MessageRole.Assistant), detail?.messages?.map { it.role })
            assertEquals("Durable answer", detail?.messages?.single { it.role == MessageRole.Assistant }?.content)
        }

    @Test
    fun `existing assistant message reconciles processing run and consumes result`() =
        runBlocking {
            val created = createConversation("00000000-0000-0000-0000-000000004032")
            val claim = assertNotNull(claim())
            val existingAssistantId = MessageId(UUID.fromString("00000000-0000-0000-0000-000000004132"))
            insertAssistantMessage(created, existingAssistantId, "Existing durable answer")
            val result = storeAnswerResult(created, claim)

            assertIs<ConsumeResponseRunResult.Consumed>(consumer.consume(result))

            val detail = repository.findConversationDetail(owner(), created.conversationId)
            assertEquals(listOf(MessageRole.User, MessageRole.Assistant), detail?.messages?.map { it.role })
            assertEquals(existingAssistantId, detail?.messages?.single { it.role == MessageRole.Assistant }?.id)
            assertEquals(ResponseRunStatus.Completed, responseRunRepository.findResponseRun(created.runId)?.status)
            assertEquals(existingAssistantId, responseRunRepository.findResponseRun(created.runId)?.assistantMessageId)
            assertNotNull(responseRunRepository.findResponseRunResult(created.runId, claim.run.attempt)?.consumedAt)
            Unit
        }

    @Test
    fun `ai request mismatch returns diagnostic ignored reason`() =
        runBlocking {
            val created = createConversation("00000000-0000-0000-0000-000000004033")
            val claim = assertNotNull(claim())
            val result = assertIs<StoreResponseRunResult.Stored>(
                responseRunRepository.storeResponseRunResult(
                    StoreResponseRunResultCommand(
                        responseRunId = claim.run.id,
                        attempt = claim.run.attempt,
                        payload = answerPayload(
                            created,
                            "Mismatched answer",
                            UUID.fromString("00000000-0000-0000-0000-000000004133"),
                        ).copy(aiRequestId = "different-ai-request"),
                        now = TaskFlowIds.Now.plusSeconds(1),
                    ),
                ),
            ).result

            val ignored = assertIs<ConsumeResponseRunResult.Ignored>(consumer.consume(result))
            assertEquals(ConsumeResponseRunIgnoreReason.AiRequestMismatch, ignored.reason)
            assertNoConversationAnswerSideEffects(created, expectedStatus = ResponseRunStatus.Processing)
        }

    @Test
    fun `characterization planning understanding consumption creates task and queues planning stage`() =
        runBlocking {
            val created = createConversation(
                idSeed = "00000000-0000-0000-0000-000000004036",
                text = "Plan a movie night",
            )
            val claim = assertNotNull(claim())
            val result = assertIs<StoreResponseRunResult.Stored>(
                responseRunRepository.storeResponseRunResult(
                    StoreResponseRunResultCommand(
                        responseRunId = claim.run.id,
                        attempt = claim.run.attempt,
                        payload = ResponseRunResultPayload.PlanningUnderstanding(
                            conversationId = created.conversationId.value.toString(),
                            userMessageId = created.userMessageId.value.toString(),
                            aiRequestId = created.aiRequestId,
                            taskId = null,
                            taskCreationRequestId = "characterization-planning-understanding",
                            intent = "Plan a characterized movie night",
                            expectedTaskRevision = null,
                            intentPatch = "Plan a characterized movie night",
                            requirements = listOf(
                                RequirementWritePayload(
                                    id = "00000000-0000-0000-0000-000000004136",
                                    kind = RequirementKind.ActivityDomain.name,
                                    value = RequirementValuePayload.ActivityDomain("movie"),
                                    strength = RequirementStrength.Must.name,
                                ),
                            ),
                            clarificationText = null,
                            planningRequested = true,
                        ),
                        now = TaskFlowIds.Now.plusSeconds(1),
                    ),
                ),
            ).result

            assertIs<ConsumeResponseRunResult.Consumed>(consumer.consume(result))

            val run = assertNotNull(responseRunRepository.findResponseRun(created.runId))
            assertEquals(ResponseRunStatus.Queued, run.status)
            assertEquals(ResponseRunStage.Planning, run.stage)
            assertNotNull(run.expectedTaskId)
            assertEquals(2, run.expectedTaskRevision)
            assertNotNull(responseRunRepository.findResponseRunResult(created.runId, claim.run.attempt)?.consumedAt)
            val detail = assertNotNull(repository.findConversationDetail(owner(), created.conversationId))
            assertNotNull(detail.messages.single { it.id == created.userMessageId }.understoodAt)
            val task = assertNotNull(taskRepository.findCurrentTaskForConversation(owner(), created.conversationId))
            assertEquals(run.expectedTaskId, task.task.id)
            assertEquals("Plan a characterized movie night", task.task.intent)
            assertEquals(2, task.task.revision)
            assertEquals(RequirementKind.ActivityDomain, task.requirements.single().kind)
            assertEquals(RequirementValue.ActivityDomain("movie"), task.requirements.single().value)
        }

    @Test
    fun `characterization planning understanding consumption updates existing linked task and queues planning stage`() =
        runBlocking {
            val created = createConversation(
                idSeed = "00000000-0000-0000-0000-000000004037",
                text = "Update the movie night plan",
            )
            val taskId = TaskId(UUID.fromString("00000000-0000-0000-0000-000000004237"))
            assertIs<CreateLinkedTaskPersistenceResult.Created>(
                taskRepository.createLinkedTask(
                    CreateLinkedTaskPersistenceCommand(
                        owner = owner(),
                        conversationId = created.conversationId,
                        taskId = taskId,
                        creationRequestId = "existing-linked-task-understanding",
                        intent = "Plan the original movie night",
                        now = TaskFlowIds.Now,
                    ),
                ),
            )
            val taskCountBefore = taskRepository.listTaskSummaries(owner()).size
            val claim = assertNotNull(claim())
            val result = assertIs<StoreResponseRunResult.Stored>(
                responseRunRepository.storeResponseRunResult(
                    StoreResponseRunResultCommand(
                        responseRunId = claim.run.id,
                        attempt = claim.run.attempt,
                        payload = ResponseRunResultPayload.PlanningUnderstanding(
                            conversationId = created.conversationId.value.toString(),
                            userMessageId = created.userMessageId.value.toString(),
                            aiRequestId = created.aiRequestId,
                            taskId = taskId.value.toString(),
                            taskCreationRequestId = "existing-linked-task-understanding-result",
                            intent = "Plan the updated movie night",
                            expectedTaskRevision = 1,
                            intentPatch = "Plan the updated movie night",
                            requirements = listOf(
                                RequirementWritePayload(
                                    id = "00000000-0000-0000-0000-000000004238",
                                    kind = RequirementKind.Location.name,
                                    value = RequirementValuePayload.Location("Nanshan"),
                                    strength = RequirementStrength.Must.name,
                                ),
                            ),
                            clarificationText = null,
                            planningRequested = true,
                        ),
                        now = TaskFlowIds.Now.plusSeconds(1),
                    ),
                ),
            ).result

            assertIs<ConsumeResponseRunResult.Consumed>(consumer.consume(result))

            val run = assertNotNull(responseRunRepository.findResponseRun(created.runId))
            assertEquals(ResponseRunStatus.Queued, run.status)
            assertEquals(ResponseRunStage.Planning, run.stage)
            assertEquals(taskId, run.expectedTaskId)
            assertEquals(2, run.expectedTaskRevision)
            assertNotNull(responseRunRepository.findResponseRunResult(created.runId, claim.run.attempt)?.consumedAt)
            val detail = assertNotNull(repository.findConversationDetail(owner(), created.conversationId))
            assertNotNull(detail.messages.single { it.id == created.userMessageId }.understoodAt)
            assertEquals(taskCountBefore, taskRepository.listTaskSummaries(owner()).size)
            val task = assertNotNull(taskRepository.findCurrentTaskForConversation(owner(), created.conversationId))
            assertEquals(taskId, task.task.id)
            assertEquals("Plan the updated movie night", task.task.intent)
            assertEquals(2, task.task.revision)
            assertEquals(RequirementKind.Location, task.requirements.single().kind)
            assertEquals(RequirementValue.Location("Nanshan"), task.requirements.single().value)
        }

    @Test
    fun `worker retries when existing result fails consumer invariant`() =
        runBlocking {
            val created = createConversation("00000000-0000-0000-0000-000000004034")
            forceRunState(created.runId, attempt = 1, status = ResponseRunStatus.Queued)
            assertIs<StoreResponseRunResult.Stored>(
                responseRunRepository.storeResponseRunResult(
                    StoreResponseRunResultCommand(
                        responseRunId = created.runId,
                        attempt = 1,
                        payload = answerPayload(
                            created,
                            "Mismatched stored answer",
                            UUID.fromString("00000000-0000-0000-0000-000000004134"),
                        ).copy(aiRequestId = "different-ai-request"),
                        now = TaskFlowIds.Now.plusSeconds(1),
                    ),
                ),
            )
            forceRunState(created.runId, attempt = 0, status = ResponseRunStatus.Queued)
            val understanding = ScriptedUnderstanding({
                understandingOutcome(turnIntent = TurnIntent.Conversation, changes = emptyList())
            })
            val decision = RecordingConversationDecision({ directConversationDecision("Worker answer") })
            val processor = ConversationTurnProcessor(
                conversationRepository = repository,
                taskRepository = taskRepository,
                understanding = understanding,
                conversationAnswerService = conversationAnswerService(decision),
                clock = TaskFlowIds.FixedClock,
                uuidFactory = { UUID.fromString("00000000-0000-0000-0000-000000004234") },
                timeZoneId = "Asia/Shanghai",
            )

            assertEquals(true, worker(processor).runOnce())

            val run = responseRunRepository.findResponseRun(created.runId)
            assertEquals(ResponseRunStatus.FailedRetryable, run?.status)
            assertEquals(ResponseRunFailureCategory.InternalInvariant, run?.failureCategory)
            assertNull(responseRunRepository.findResponseRunResult(created.runId, 1)?.consumedAt)
            assertEquals(listOf(MessageRole.User), repository.findConversationDetail(owner(), created.conversationId)?.messages?.map { it.role })
        }

    @Test
    fun `stale attempt store does not overwrite current attempt result`() =
        runBlocking {
            val created = createConversation("00000000-0000-0000-0000-000000004035")
            val firstClaim = assertNotNull(claim(leaseDuration = Duration.ofSeconds(5)))
            val reclaimed = assertNotNull(
                claim(
                    workerId = "worker-two",
                    now = TaskFlowIds.Now.plusSeconds(6),
                    leaseDuration = Duration.ofSeconds(5),
                ),
            )

            assertEquals(
                StoreResponseRunResult.StaleAttempt,
                responseRunRepository.storeResponseRunResult(
                    StoreResponseRunResultCommand(
                        responseRunId = created.runId,
                        attempt = firstClaim.run.attempt,
                        payload = answerPayload(
                            created,
                            "Stale answer",
                            UUID.fromString("00000000-0000-0000-0000-000000004135"),
                        ),
                        now = TaskFlowIds.Now.plusSeconds(7),
                    ),
                ),
            )
            val current = assertIs<StoreResponseRunResult.Stored>(
                responseRunRepository.storeResponseRunResult(
                    StoreResponseRunResultCommand(
                        responseRunId = created.runId,
                        attempt = reclaimed.run.attempt,
                        payload = answerPayload(
                            created,
                            "Current answer",
                            UUID.fromString("00000000-0000-0000-0000-000000004136"),
                        ),
                        now = TaskFlowIds.Now.plusSeconds(8),
                    ),
                ),
            ).result

            assertNull(responseRunRepository.findResponseRunResult(created.runId, firstClaim.run.attempt))
            assertEquals("Current answer", (current.payload as ResponseRunResultPayload.ConversationAnswer).text)
        }

    @Test
    fun `completed run is terminal irreversible`() =
        runBlocking {
            val created = createConversation("00000000-0000-0000-0000-000000004041")
            val claim = assertNotNull(claim())
            val result = storeAnswerResult(created, claim)
            assertIs<ConsumeResponseRunResult.Consumed>(consumer.consume(result))

            assertEquals(
                false,
                responseRunRepository.failResponseRunAttempt(
                    FailResponseRunAttemptCommand(
                        responseRunId = claim.run.id,
                        attempt = claim.run.attempt,
                        now = TaskFlowIds.Now.plusSeconds(3),
                        failureCategory = ResponseRunFailureCategory.InternalInvariant,
                    ),
                ),
            )
            assertEquals(ResponseRunStatus.Completed, responseRunRepository.findResponseRun(created.runId)?.status)
            assertEquals(1, repository.findConversationDetail(owner(), created.conversationId)?.messages?.count { it.role == MessageRole.Assistant })
        }

    @Test
    fun `fast post does not call AI and worker stores result and completes run`() =
        runBlocking {
            val understanding = ScriptedUnderstanding({
                understandingOutcome(turnIntent = TurnIntent.Conversation, changes = emptyList())
            })
            val decision = RecordingConversationDecision({ directConversationDecision("Worker answer") })
            val answerService = conversationAnswerService(decision)
            val conversationService = createConversationServices(
                dataSource = dataSource,
                understanding = understanding,
                conversationAnswerService = answerService,
            ).conversationService
            val accepted = conversationService.createConversation(
                taskActor(),
                "worker-result-bus",
                "Hello",
                "Asia/Shanghai",
            )
            val created = CreatedRun(
                conversationId = accepted.detail.conversation.id,
                userMessageId = accepted.detail.messages.single().id,
                runId = accepted.detail.responseRuns.single().id,
                aiRequestId = accepted.detail.messages.single().aiRequestId!!,
            )
            assertEquals(emptyList(), understanding.calls)
            assertEquals(emptyList(), decision.requests)
            val processor = ConversationTurnProcessor(
                conversationRepository = repository,
                taskRepository = taskRepository,
                understanding = understanding,
                conversationAnswerService = answerService,
                clock = TaskFlowIds.FixedClock,
                uuidFactory = { UUID.fromString("00000000-0000-0000-0000-000000004999") },
                timeZoneId = "Asia/Shanghai",
            )
            val worker = ResponseRunWorker(
                responseRunStore = responseRunRepository,
                resultStore = responseRunRepository,
                processor = processor,
                resultConsumer = consumer,
                config = ResponseRunWorkerConfig(
                    enabled = true,
                    pollInterval = Duration.ofMillis(10),
                    leaseDuration = Duration.ofSeconds(30),
                    heartbeatInterval = Duration.ofSeconds(10),
                    retryBackoff = Duration.ZERO,
                    maxAttempts = 3,
                ),
                clock = TaskFlowIds.FixedClock,
                workerId = "worker-result-bus",
            )

            assertEquals(true, worker.runOnce())

            val detail = repository.findConversationDetail(owner(), created.conversationId)
            assertEquals(listOf(MessageRole.User, MessageRole.Assistant), detail?.messages?.map { it.role })
            assertEquals("Worker answer", detail?.messages?.single { it.role == MessageRole.Assistant }?.content)
            assertEquals(1, understanding.calls.size)
            assertEquals(1, decision.requests.size)
            assertEquals(ResponseRunStatus.Completed, responseRunRepository.findResponseRun(created.runId)?.status)
            assertNotNull(responseRunRepository.findResponseRunResult(created.runId, 1)?.consumedAt)
            Unit
        }

    @Test
    fun `chat answer response run emits operation timeline`() =
        runBlocking {
            val logger = jsonRecordingLogger()
            val understanding = ScriptedUnderstanding({
                understandingOutcome(turnIntent = TurnIntent.Conversation, changes = emptyList())
            })
            val decision = RecordingConversationDecision({ directConversationDecision("Worker answer") })
            val answerService = conversationAnswerService(decision, logger = logger.logger)
            val accepted = createConversationServices(
                dataSource = dataSource,
                understanding = understanding,
                conversationAnswerService = answerService,
            ).conversationService.createConversation(
                taskActor(),
                "chat-answer-timeline",
                "Hello",
                "Asia/Shanghai",
            )
            val runId = accepted.detail.responseRuns.single().id.value.toString()
            val processor = ConversationTurnProcessor(
                conversationRepository = repository,
                taskRepository = taskRepository,
                understanding = understanding,
                conversationAnswerService = answerService,
                logger = logger.logger,
                clock = TaskFlowIds.FixedClock,
                uuidFactory = { UUID.fromString("00000000-0000-0000-0000-000000004998") },
                timeZoneId = "Asia/Shanghai",
            )

            assertEquals(true, worker(processor, logger = logger.logger).runOnce())

            val records = logger.records()
            assertEquals(
                "conversation_turn",
                records.single { it.field("event") == "response_run_processing_started" }.field("operation_type"),
            )
            assertEquals(
                runId,
                records.single { it.field("event") == "response_run_processing_started" }.field("operation_id"),
            )
            assertStepsInOrder(
                records.filter { it.field("operation_id") == runId },
                listOf(
                    "started",
                    "understanding_started",
                    "understanding_finished",
                    "answer_started",
                    "decision_finished",
                    "answer_generated",
                    "answer_finished",
                    "finished",
                ),
            )
            val chatBranch = records.filter { it.field("operation_id") == runId && it.field("branch") == "chat_answer" }
            listOf("understanding_finished", "decision_finished", "answer_generated", "answer_finished", "finished")
                .forEach { step -> assertTrue(chatBranch.any { it.field("step") == step }, "missing $step") }
            assertEquals(
                "conversation",
                chatBranch.single { it.field("step") == "understanding_finished" }.field("outcome"),
            )
            assertTrue(records.none { it.field("event") == "response_run_result_stored" && it.field("level") == "INFO" })
        }

    @Test
    fun `worker logs and processing use response run origin trace`() =
        runBlocking {
            val originTraceId = "fedcba9876543210fedcba9876543210"
            val understanding = ScriptedUnderstanding({
                understandingOutcome(turnIntent = TurnIntent.Conversation, changes = emptyList())
            })
            val decision = RecordingConversationDecision({ directConversationDecision("Traced worker answer") })
            val answerService = conversationAnswerService(decision)
            val created = assertIs<CreateConversationResult.Created>(
                turnStartCommitter.createConversation(
                    CreateConversationCommand(
                        owner = owner(),
                        conversationId = ConversationId(UUID.fromString("00000000-0000-0000-0000-0000000040a1")),
                        firstMessageId = MessageId(UUID.fromString("00000000-0000-0000-0000-0000000040a2")),
                        creationRequestId = "worker-origin-trace",
                        clientMessageId = "worker-origin-trace",
                        text = "Hello with trace",
                        aiRequestId = "ai-worker-origin-trace",
                        now = TaskFlowIds.Now,
                        responseRunId = ResponseRunId(UUID.fromString("00000000-0000-0000-0000-0000000040a3")),
                        originTraceId = originTraceId,
                    ),
                ),
            )
            val processor = ConversationTurnProcessor(
                conversationRepository = repository,
                taskRepository = taskRepository,
                understanding = understanding,
                conversationAnswerService = answerService,
                clock = TaskFlowIds.FixedClock,
                uuidFactory = { UUID.fromString("00000000-0000-0000-0000-0000000040a4") },
                timeZoneId = "Asia/Shanghai",
            )
            val sink = RecordingLogSink()
            val logger = DefaultStructuredLogger(
                traceContext = BackendTraceContext,
                formatter = JsonLogFormatter,
                sink = sink,
                serviceName = "nexusflow-backend",
                environment = RuntimeEnvironment.Local.value,
                minimumLevel = LogLevel.DEBUG,
            )
            val worker = ResponseRunWorker(
                responseRunStore = responseRunRepository,
                resultStore = responseRunRepository,
                processor = processor,
                resultConsumer = consumer,
                config = ResponseRunWorkerConfig(
                    enabled = true,
                    pollInterval = Duration.ofMillis(10),
                    leaseDuration = Duration.ofSeconds(30),
                    heartbeatInterval = Duration.ofSeconds(10),
                    retryBackoff = Duration.ZERO,
                    maxAttempts = 3,
                ),
                clock = TaskFlowIds.FixedClock,
                workerId = "worker-origin-trace",
                logger = logger,
            )

            assertEquals(true, worker.runOnce())

            val records = sink.lines.map { Json.parseToJsonElement(it).jsonObject }
            val claimed = records.single { it.getValue("event").jsonPrimitive.content == "response_run_claimed" }
            assertEquals(originTraceId, claimed.getValue("trace_id").jsonPrimitive.content)
            assertEquals("DEBUG", claimed.getValue("level").jsonPrimitive.content)
            assertEquals(originTraceId, claimed.getValue("origin_trace_id").jsonPrimitive.content)
            assertEquals(
                created.detail.responseRuns.single().id.value.toString(),
                claimed.getValue("response_run_id").jsonPrimitive.content,
            )
            val stored = records.single { it.getValue("event").jsonPrimitive.content == "response_run_result_stored" }
            assertEquals("DEBUG", stored.getValue("level").jsonPrimitive.content)
            assertEquals(
                created.detail.messages.single { it.role == MessageRole.User }.aiRequestId,
                stored.getValue("ai_request_id").jsonPrimitive.content,
            )
            assertEquals(
                created.detail.responseRuns.single().id.value.toString(),
                stored.getValue("response_run_id").jsonPrimitive.content,
            )
            assertTrue(records.all { it.getValue("trace_id").jsonPrimitive.content == originTraceId })
        }

    @Test
    fun `worker stops current attempt and schedules retry when heartbeat loses lease`() =
        runBlocking {
            val created = createConversation("00000000-0000-0000-0000-0000000040b1")
            val understanding = ScriptedUnderstanding({
                understandingOutcome(turnIntent = TurnIntent.Conversation, changes = emptyList())
            })
            val decision = RecordingConversationDecision({ directConversationDecision("lease lost answer") })
            val stalledAnswering = StreamingConversationAnsweringCapability { _, _ ->
                awaitCancellation()
            }
            val processor = ConversationTurnProcessor(
                conversationRepository = repository,
                taskRepository = taskRepository,
                understanding = understanding,
                conversationAnswerService = conversationAnswerService(
                    decision = decision,
                    answering = null,
                    streamingAnswering = stalledAnswering,
                ),
                clock = TaskFlowIds.FixedClock,
                uuidFactory = { UUID.fromString("00000000-0000-0000-0000-0000000040b4") },
                timeZoneId = "Asia/Shanghai",
            )
            val worker = ResponseRunWorker(
                responseRunStore = responseRunRepository,
                resultStore = responseRunRepository,
                processor = processor,
                resultConsumer = consumer,
                config = ResponseRunWorkerConfig(
                    enabled = true,
                    pollInterval = Duration.ofMillis(10),
                    leaseDuration = Duration.ofSeconds(30),
                    heartbeatInterval = Duration.ofMillis(10),
                    retryBackoff = Duration.ZERO,
                    maxAttempts = 3,
                ),
                clock = TaskFlowIds.FixedClock,
                workerId = "worker-lease-lost",
            )

            val workerJob = launch { worker.runOnce() }
            withTimeout(1_000) {
                while (responseRunRepository.findResponseRun(created.runId)?.leaseOwner != "worker-lease-lost") {
                    delay(5)
                }
            }
            stealResponseRunLease(created.runId, "other-worker")

            withTimeout(1_000) {
                workerJob.join()
            }

            val run = assertNotNull(responseRunRepository.findResponseRun(created.runId))
            assertEquals(ResponseRunStatus.FailedRetryable, run.status)
            assertEquals(ResponseRunFailureCategory.WorkerLost, run.failureCategory)
            assertNull(responseRunRepository.findResponseRunResult(created.runId, 1))
        }

    @Test
    fun `conversation tool success emits one activity while deduped evidence fans out`() =
        runBlocking {
            val toolCall = toolProposal(MovieShowtimesKey.value, JsonObject(emptyMap()))
            val decision = RecordingConversationDecision({
                ConversationDecisionResult(
                    informationNeeds = listOf(
                        InformationNeedProposal(
                            id = "need-one",
                            question = "Research first current fact",
                            mode = InformationNeedMode.TOOL_REQUIRED,
                            toolCalls = listOf(toolCall),
                        ),
                        InformationNeedProposal(
                            id = "need-two",
                            question = "Research second current fact",
                            mode = InformationNeedMode.TOOL_REQUIRED,
                            toolCalls = listOf(toolCall),
                        ),
                    ),
                )
            })
            val answering = RecordingQuestionAnswering()
            val tool = ControlledPlanningReadTool(
                key = MovieShowtimesKey,
                activityKind = ReadToolActivityKind.Movie,
            )
            val catalog = ReadToolCatalog(listOf(tool))
            val answerService = conversationAnswerService(
                decision = decision,
                answering = answering,
                catalog = catalog,
                executor = ReadToolExecutor(catalog),
            )
            val understanding = ScriptedUnderstanding({
                understandingOutcome(turnIntent = TurnIntent.Conversation, changes = emptyList())
            })
            val services = createConversationServices(
                dataSource = dataSource,
                understanding = understanding,
                conversationAnswerService = answerService,
            )
            val accepted = services.conversationService.createConversation(
                taskActor(),
                "tool-activity-success",
                "Find current movie options",
                "Asia/Shanghai",
            )
            val realtimeHub = ResponseRunRealtimeHub(TaskFlowIds.FixedClock)
            val processor = conversationAnswerProcessor(
                understanding = understanding,
                answerService = answerService,
                realtimeHub = realtimeHub,
            )
            val worker = worker(processor)

            assertEquals(true, worker.runOnce())

            val run = assertNotNull(responseRunRepository.findResponseRun(accepted.detail.responseRuns.single().id))
            assertEquals(ResponseRunStatus.Completed, run.status)
            assertEquals(1, tool.requests.size)
            val answerRequest = answering.requests.single()
            assertEquals(
                listOf(
                    listOf("controlled-planning-evidence"),
                    listOf("controlled-planning-evidence"),
                ),
                answerRequest.informationNeeds.map { it.evidenceSourceIds },
            )
            val snapshot = assertNotNull(realtimeHub.snapshot(run.copy(status = ResponseRunStatus.Processing)))
            assertEquals(1, snapshot.activities.size)
            val activity = snapshot.activities.single()
            assertEquals(ResponseRunActivityKind.Movie, activity.kind)
            assertEquals(false, activity.failed)
            assertNull(activity.message)
            assertNotNull(activity.completedAt)
            Unit
        }

    @Test
    fun `true streamed answer emits deltas before terminal and durable answer matches stream`() =
        runBlocking {
            val decision = RecordingConversationDecision({ directConversationDecision("Answer conversationally") })
            val answering = RecordingQuestionAnswering().apply {
                deltas = listOf("Streaming ", "durable ", "answer")
            }
            val answerService = conversationAnswerService(decision = decision, answering = answering)
            val understanding = ScriptedUnderstanding({
                understandingOutcome(turnIntent = TurnIntent.Conversation, changes = emptyList())
            })
            val services = createConversationServices(
                dataSource = dataSource,
                understanding = understanding,
                conversationAnswerService = answerService,
            )
            val accepted = services.conversationService.createConversation(
                taskActor(),
                "streaming-answer",
                "Answer conversationally",
                "Asia/Shanghai",
            )
            val realtimeHub = ResponseRunRealtimeHub(TaskFlowIds.FixedClock)
            val events = mutableListOf<ResponseRunEventPayload>()
            val eventJob = launch {
                realtimeHub.events(accepted.detail.responseRuns.single().id).collect { event ->
                    events += event.payload
                }
            }
            val processor = conversationAnswerProcessor(
                understanding = understanding,
                answerService = answerService,
                realtimeHub = realtimeHub,
            )

            assertEquals(true, worker(processor, realtimeHub).runOnce())
            delay(25)
            eventJob.cancelAndJoin()

            val run = assertNotNull(responseRunRepository.findResponseRun(accepted.detail.responseRuns.single().id))
            assertEquals(ResponseRunStatus.Completed, run.status)
            val detail = assertNotNull(repository.findConversationDetail(owner(), accepted.detail.conversation.id))
            val durableAnswer = detail.messages.single { it.role == MessageRole.Assistant }.content
            assertEquals(answering.emittedDeltas.joinToString(separator = ""), durableAnswer)
            val snapshot = assertNotNull(realtimeHub.snapshot(run.copy(status = ResponseRunStatus.Processing)))
            assertEquals(durableAnswer, snapshot.partialText)
            val deltaIndex = events.indexOfFirst { it is ResponseRunEventPayload.Delta }
            val terminalIndex = events.indexOfFirst { it is ResponseRunEventPayload.Completed }
            assertTrue(deltaIndex >= 0)
            assertTrue(terminalIndex > deltaIndex)
        }

    @Test
    fun `stream provider failure marks run retryable without durable assistant`() =
        runBlocking {
            val decision = RecordingConversationDecision({ directConversationDecision("Answer conversationally") })
            val answering = RecordingQuestionAnswering().apply {
                deltas = listOf("partial")
                answerFailure = CapabilityUnavailableException()
            }
            val answerService = conversationAnswerService(decision = decision, answering = answering)
            val understanding = ScriptedUnderstanding({
                understandingOutcome(turnIntent = TurnIntent.Conversation, changes = emptyList())
            })
            val services = createConversationServices(
                dataSource = dataSource,
                understanding = understanding,
                conversationAnswerService = answerService,
            )
            val accepted = services.conversationService.createConversation(
                taskActor(),
                "streaming-answer-failure",
                "Answer conversationally",
                "Asia/Shanghai",
            )
            val realtimeHub = ResponseRunRealtimeHub(TaskFlowIds.FixedClock)
            val processor = conversationAnswerProcessor(
                understanding = understanding,
                answerService = answerService,
                realtimeHub = realtimeHub,
            )

            assertEquals(true, worker(processor, realtimeHub).runOnce())

            val run = assertNotNull(responseRunRepository.findResponseRun(accepted.detail.responseRuns.single().id))
            assertEquals(ResponseRunStatus.FailedRetryable, run.status)
            assertEquals(ResponseRunFailureCategory.ProviderTemporary, run.failureCategory)
            val detail = assertNotNull(repository.findConversationDetail(owner(), accepted.detail.conversation.id))
            assertEquals(listOf(MessageRole.User), detail.messages.map { it.role })
            assertEquals("partial", assertNotNull(realtimeHub.snapshot(run.copy(status = ResponseRunStatus.Processing))).partialText)
        }

    @Test
    fun `unavailable tool emits failed activity but fallback answer can complete the run`() =
        runBlocking {
            val toolCall = toolProposal(MovieShowtimesKey.value, JsonObject(emptyMap()))
            val decision = RecordingConversationDecision({
                mixedWeatherAndActivityDecision(toolCall)
            })
            val answering = RecordingQuestionAnswering().apply {
                answerText = "Fallback answer from model-only context."
            }
            val tool = ControlledPlanningReadTool(
                key = MovieShowtimesKey,
                activityKind = ReadToolActivityKind.Movie,
                outcomeFactory = { _, _ -> ReadToolOutcome.Unavailable("movie source unavailable") },
            )
            val catalog = ReadToolCatalog(listOf(tool))
            val answerService = conversationAnswerService(
                decision = decision,
                answering = answering,
                catalog = catalog,
                executor = ReadToolExecutor(catalog),
            )
            val understanding = ScriptedUnderstanding({
                understandingOutcome(turnIntent = TurnIntent.Conversation, changes = emptyList())
            })
            val services = createConversationServices(
                dataSource = dataSource,
                understanding = understanding,
                conversationAnswerService = answerService,
            )
            val accepted = services.conversationService.createConversation(
                taskActor(),
                "tool-activity-unavailable",
                "Can we do something if the source is down?",
                "Asia/Shanghai",
            )
            val realtimeHub = ResponseRunRealtimeHub(TaskFlowIds.FixedClock)
            val worker = worker(
                conversationAnswerProcessor(
                    understanding = understanding,
                    answerService = answerService,
                    realtimeHub = realtimeHub,
                ),
            )

            assertEquals(true, worker.runOnce())

            val run = assertNotNull(responseRunRepository.findResponseRun(accepted.detail.responseRuns.single().id))
            assertEquals(ResponseRunStatus.Completed, run.status)
            assertNull(run.failureCategory)
            val answerRequest = answering.requests.single()
            assertEquals(
                ResearchIssueType.SOURCE_UNAVAILABLE,
                answerRequest.informationNeeds.single { it.id == "need-weather" }.issues.single().type,
            )
            val snapshot = assertNotNull(realtimeHub.snapshot(run.copy(status = ResponseRunStatus.Processing)))
            val activity = snapshot.activities.single()
            assertEquals(true, activity.failed)
            assertNull(activity.message)
            Unit
        }

    @Test
    fun `planning turn first worker run creates linked task and queues planning stage`() =
        runBlocking {
            val understanding = ScriptedUnderstanding({
                understandingOutcome(
                    turnIntent = TurnIntent.Planning,
                    intentPatch = "Plan a movie night",
                    changes = listOf(activityDomainChange("movie", "movie night")),
                )
            })
            val services = createConversationServices(dataSource = dataSource, understanding = understanding)
            val accepted = services.conversationService.createConversation(
                taskActor(),
                "planning-stage-one",
                "Plan a movie night",
                "Asia/Shanghai",
            )
            val runId = accepted.detail.responseRuns.single().id
            val processor = planningProcessor(understanding, services.planningService)
            val sink = RecordingLogSink()
            val logger = DefaultStructuredLogger(
                traceContext = BackendTraceContext,
                formatter = JsonLogFormatter,
                sink = sink,
                serviceName = "nexusflow-backend",
                environment = RuntimeEnvironment.Local.value,
                minimumLevel = LogLevel.INFO,
            )
            val worker = worker(processor, logger = logger)

            assertEquals(true, worker.runOnce())

            val run = assertNotNull(responseRunRepository.findResponseRun(runId))
            assertEquals(ResponseRunStatus.Queued, run.status)
            assertEquals(ResponseRunStage.Planning, run.stage)
            assertNotNull(run.expectedTaskId)
            assertEquals(2, run.expectedTaskRevision)
            assertNotNull(responseRunRepository.findResponseRunResult(runId, 1)?.consumedAt)
            val task = assertNotNull(taskRepository.findCurrentTaskForConversation(owner(), accepted.detail.conversation.id))
            assertEquals("Plan a movie night", task.task.intent)
            assertEquals(RequirementKind.ActivityDomain, task.requirements.single().kind)
            assertEquals(emptyList(), services.planComposer.contexts)
            assertEquals(1, understanding.calls.size)
            val records = sink.lines.map { Json.parseToJsonElement(it).jsonObject }
            val planningQueued = records.single { it.getValue("event").jsonPrimitive.content == "response_run_planning_queued" }
            assertEquals("INFO", planningQueued.getValue("level").jsonPrimitive.content)
            assertEquals(runId.value.toString(), planningQueued.getValue("response_run_id").jsonPrimitive.content)
            assertEquals(runId.value.toString(), planningQueued.getValue("source_response_run_id").jsonPrimitive.content)
            assertEquals(run.expectedTaskId?.value.toString(), planningQueued.getValue("task_id").jsonPrimitive.content)
            assertEquals("planning", planningQueued.getValue("stage").jsonPrimitive.content)
            assertEquals("queued", planningQueued.getValue("status").jsonPrimitive.content)
            Unit
        }

    @Test
    fun `planning stage worker computes and consumer persists plans asynchronously`() =
        runBlocking {
            val understanding = ScriptedUnderstanding({
                understandingOutcome(
                    turnIntent = TurnIntent.Planning,
                    intentPatch = "Plan a movie night",
                    changes = listOf(activityDomainChange("movie", "movie night")),
                )
            })
            val services = createConversationServices(dataSource = dataSource, understanding = understanding)
            val accepted = services.conversationService.createConversation(
                taskActor(),
                "planning-stage-two",
                "Plan a movie night",
                "Asia/Shanghai",
            )
            val processor = planningProcessor(understanding, services.planningService)
            val worker = worker(processor)

            assertEquals(true, worker.runOnce())
            assertEquals(ResponseRunStatus.Queued, responseRunRepository.findResponseRun(accepted.detail.responseRuns.single().id)?.status)
            assertEquals(ResponseRunStage.Planning, responseRunRepository.findResponseRun(accepted.detail.responseRuns.single().id)?.stage)
            assertEquals(1, responseRunRepository.findResponseRun(accepted.detail.responseRuns.single().id)?.attempt)
            assertEquals(true, worker.runOnce())

            val run = assertNotNull(responseRunRepository.findResponseRun(accepted.detail.responseRuns.single().id))
            assertEquals(ResponseRunStatus.Completed, run.status)
            assertEquals(ResponseRunStage.Planning, run.stage)
            val task = assertNotNull(taskRepository.findCurrentTaskForConversation(owner(), accepted.detail.conversation.id))
            assertEquals(1, task.plans.size)
            assertEquals(1, services.planComposer.contexts.size)
            assertNotNull(responseRunRepository.findResponseRunResult(run.id, 1)?.consumedAt)
            Unit
        }

    @Test
    fun `planning response run emits operation timeline across queued and planning stage`() =
        runBlocking {
            val logger = jsonRecordingLogger()
            val understanding = ScriptedUnderstanding({
                understandingOutcome(
                    turnIntent = TurnIntent.Planning,
                    intentPatch = "Plan a movie night",
                    changes = listOf(activityDomainChange("movie", "movie night")),
                )
            })
            val services = createConversationServices(
                dataSource = dataSource,
                understanding = understanding,
                logger = logger.logger,
            )
            val accepted = services.conversationService.createConversation(
                taskActor(),
                "planning-timeline",
                "Plan a movie night",
                "Asia/Shanghai",
            )
            val runId = accepted.detail.responseRuns.single().id.value.toString()
            val processor = planningProcessor(understanding, services.planningService, logger.logger)
            val worker = worker(processor, logger = logger.logger)

            assertEquals(true, worker.runOnce())
            assertEquals(true, worker.runOnce())

            val records = logger.records().filter { it.field("operation_id") == runId }
            assertStepsInOrder(
                records,
                listOf(
                    "started",
                    "understanding_started",
                    "understanding_finished",
                    "planning_queued",
                    "started",
                    "readiness_checked",
                    "planning_started",
                    "planning_finished",
                    "finished",
                ),
            )
            val planningBranch = records.filter { it.field("branch") == "planning" }
            listOf(
                "understanding_finished",
                "planning_queued",
                "readiness_checked",
                "planning_started",
                "planning_finished",
                "finished",
            ).forEach { step -> assertTrue(planningBranch.any { it.field("step") == step }, "missing $step") }
            assertEquals(
                "planning",
                planningBranch.single { it.field("step") == "understanding_finished" }.field("outcome"),
            )
            val planningFinished = planningBranch.single { it.field("event") == "planning_finished" }
            assertEquals("ready", planningFinished.field("outcome"))
            assertEquals("plan", planningFinished.field("planning_decision"))
            assertNotNull(planningFinished.field("task_id"))
            assertEquals("2", planningFinished.field("task_revision"))
        }

    @Test
    fun `planning understanding apply stale revision fails run without queueing planning stage`() =
        runBlocking {
            val created = createConversation("00000000-0000-0000-0000-000000004301", text = "Add a movie plan")
            val taskId = TaskId(UUID.fromString("00000000-0000-0000-0000-000000004302"))
            assertIs<CreateLinkedTaskPersistenceResult.Created>(
                taskRepository.createLinkedTask(
                    CreateLinkedTaskPersistenceCommand(
                        owner = owner(),
                        conversationId = created.conversationId,
                        taskId = taskId,
                        creationRequestId = "linked-task-stale-understanding",
                        intent = "Plan something",
                        now = TaskFlowIds.Now,
                    ),
                ),
            )
            val understanding = ScriptedUnderstanding({
                understandingOutcome(
                    turnIntent = TurnIntent.Planning,
                    intentPatch = "Plan a movie night",
                    changes = listOf(activityDomainChange("movie", "movie night")),
                )
            })
            val services = createConversationServices(dataSource = dataSource, understanding = understanding)
            val processor = planningProcessor(understanding, services.planningService)
            val claim = assertNotNull(claim())
            val payload = processor.process(claim)
            assertIs<ApplyUnderstandingResult.Applied>(
                taskRepository.applyConversationUnderstanding(
                    ApplyConversationUnderstandingCommand(
                        owner = owner(),
                        taskId = taskId,
                        expectedTaskRevision = 1,
                        conversationMessageId = created.userMessageId,
                        aiRequestId = created.aiRequestId,
                        intentPatch = null,
                        requirements = listOf(
                            RequirementWrite(
                                id = RequirementId(UUID.fromString("00000000-0000-0000-0000-000000004303")),
                                kind = RequirementKind.Location,
                                value = RequirementValue.Location("Nanshan"),
                                strength = RequirementStrength.Must,
                            ),
                        ),
                        now = TaskFlowIds.Now.plusSeconds(1),
                    ),
                ),
            )
            val result = assertIs<StoreResponseRunResult.Stored>(
                responseRunRepository.storeResponseRunResult(
                    StoreResponseRunResultCommand(
                        responseRunId = claim.run.id,
                        attempt = claim.run.attempt,
                        payload = payload,
                        now = TaskFlowIds.Now.plusSeconds(2),
                    ),
                ),
            ).result

            assertIs<ConsumeResponseRunResult.Consumed>(consumer.consume(result))

            val run = assertNotNull(responseRunRepository.findResponseRun(created.runId))
            assertEquals(ResponseRunStatus.Failed, run.status)
            assertEquals(ResponseRunStage.Turn, run.stage)
            assertEquals(ResponseRunFailureCategory.AiInvalidResult, run.failureCategory)
            assertNotNull(responseRunRepository.findResponseRunResult(created.runId, claim.run.attempt)?.consumedAt)
            Unit
        }

    @Test
    fun `stale planning result is completed without persisting stale plans`() =
        runBlocking {
            val understanding = ScriptedUnderstanding({
                understandingOutcome(
                    turnIntent = TurnIntent.Planning,
                    intentPatch = "Plan a movie night",
                    changes = listOf(activityDomainChange("movie", "movie night")),
                )
            })
            val services = createConversationServices(dataSource = dataSource, understanding = understanding)
            val accepted = services.conversationService.createConversation(
                taskActor(),
                "planning-stale-result",
                "Plan a movie night",
                "Asia/Shanghai",
            )
            val processor = planningProcessor(understanding, services.planningService)
            assertEquals(true, worker(processor).runOnce())
            assertEquals(ResponseRunStatus.Queued, responseRunRepository.findResponseRun(accepted.detail.responseRuns.single().id)?.status)
            val planningClaim = assertNotNull(claim())
            val planningPayload = processor.process(planningClaim)
            val task = assertNotNull(taskRepository.findCurrentTaskForConversation(owner(), accepted.detail.conversation.id))
            val requirement = task.requirements.single()
            assertIs<RequirementMutationResult.Mutated>(
                taskRepository.updateRequirement(
                    UpdateRequirementCommand(
                        owner = owner(),
                        taskId = task.task.id,
                        requirementId = requirement.id,
                        kind = RequirementKind.ActivityDomain,
                        value = RequirementValue.ActivityDomain("sports"),
                        strength = RequirementStrength.Must,
                        now = TaskFlowIds.Now.plusSeconds(20),
                    ),
                ),
            )
            val stored = responseRunRepository.storeResponseRunResult(
                StoreResponseRunResultCommand(
                    responseRunId = planningClaim.run.id,
                    attempt = planningClaim.run.attempt,
                    payload = planningPayload,
                    now = TaskFlowIds.Now.plusSeconds(21),
                ),
            )
            val result = when (stored) {
                is StoreResponseRunResult.Existing -> stored.result
                is StoreResponseRunResult.Stored -> stored.result
                StoreResponseRunResult.StaleAttempt -> error("planning result should be current attempt")
            }

            assertIs<ConsumeResponseRunResult.Consumed>(consumer.consume(result))

            val latest = assertNotNull(taskRepository.findTaskDetail(owner(), task.task.id))
            assertEquals(emptyList(), latest.plans)
            assertEquals(ResponseRunStatus.Completed, responseRunRepository.findResponseRun(planningClaim.run.id)?.status)
            Unit
        }

    @Test
    fun `no planning candidates completes normally without dependency failure`() =
        runBlocking {
            val emptyTool = ControlledPlanningReadTool(outcomeFactory = { _, _ -> ReadToolOutcome.Empty })
            val catalog = ReadToolCatalog(listOf(emptyTool))
            val understanding = ScriptedUnderstanding({
                understandingOutcome(
                    turnIntent = TurnIntent.Planning,
                    intentPatch = "Plan a movie night",
                    changes = listOf(activityDomainChange("movie", "movie night")),
                )
            })
            val services = createConversationServices(
                dataSource = dataSource,
                understanding = understanding,
                readToolCatalog = catalog,
                readToolExecutor = ReadToolExecutor(catalog),
            )
            val accepted = services.conversationService.createConversation(
                taskActor(),
                "planning-no-candidates",
                "Plan a movie night",
                "Asia/Shanghai",
            )
            val worker = worker(planningProcessor(understanding, services.planningService))

            assertEquals(true, worker.runOnce())
            assertEquals(ResponseRunStatus.Queued, responseRunRepository.findResponseRun(accepted.detail.responseRuns.single().id)?.status)
            assertEquals(true, worker.runOnce())

            val run = assertNotNull(responseRunRepository.findResponseRun(accepted.detail.responseRuns.single().id))
            assertEquals(ResponseRunStatus.Completed, run.status)
            assertNull(run.failureCategory)
            val task = assertNotNull(taskRepository.findCurrentTaskForConversation(owner(), accepted.detail.conversation.id))
            assertTrue(task.plans.isEmpty())
            Unit
        }

    @Test
    fun `no feasible planning result completes normally without dependency failure`() =
        runBlocking {
            val understanding = ScriptedUnderstanding({
                understandingOutcome(
                    turnIntent = TurnIntent.Planning,
                    intentPatch = "Plan a movie night",
                    changes = listOf(
                        activityDomainChange("movie", "movie night"),
                        budgetChange(wholeUnits = 1, evidenceText = "cheap"),
                    ),
                )
            })
            val services = createConversationServices(dataSource = dataSource, understanding = understanding)
            val accepted = services.conversationService.createConversation(
                taskActor(),
                "planning-no-feasible",
                "Plan a movie night",
                "Asia/Shanghai",
            )
            val worker = worker(planningProcessor(understanding, services.planningService))

            assertEquals(true, worker.runOnce())
            assertEquals(true, worker.runOnce())

            val run = assertNotNull(responseRunRepository.findResponseRun(accepted.detail.responseRuns.single().id))
            assertEquals(ResponseRunStatus.Completed, run.status)
            assertNull(run.failureCategory)
            val task = assertNotNull(taskRepository.findCurrentTaskForConversation(owner(), accepted.detail.conversation.id))
            assertTrue(task.plans.isEmpty())
            Unit
        }

    @Test
    fun `planning read tool partial failures preserve successful evidence semantics`() =
        runBlocking {
            val successTool = ControlledPlanningReadTool(key = MovieShowtimesKey)
            val unavailableTool = ControlledPlanningReadTool(
                key = SportsFixturesKey,
                outcomeFactory = { _, _ -> ReadToolOutcome.Unavailable("sports feed unavailable") },
            )
            val catalog = ReadToolCatalog(listOf(successTool, unavailableTool))
            val research = RecordingPlanningResearch().apply {
                proposalFactory = { request ->
                    request.availableReadTools.map { definition ->
                        toolProposal(definition.toolKey, JsonObject(emptyMap()))
                    }
                }
            }
            val understanding = ScriptedUnderstanding({
                understandingOutcome(
                    turnIntent = TurnIntent.Planning,
                    intentPatch = "Plan a movie night",
                    changes = listOf(activityDomainChange("movie", "movie night")),
                )
            })
            val services = createConversationServices(
                dataSource = dataSource,
                understanding = understanding,
                planningResearch = research,
                readToolCatalog = catalog,
                readToolExecutor = ReadToolExecutor(catalog),
            )
            val accepted = services.conversationService.createConversation(
                taskActor(),
                "planning-partial-tools",
                "Plan a movie night",
                "Asia/Shanghai",
            )
            val worker = worker(planningProcessor(understanding, services.planningService))

            assertEquals(true, worker.runOnce())
            assertEquals(true, worker.runOnce())

            val run = assertNotNull(responseRunRepository.findResponseRun(accepted.detail.responseRuns.single().id))
            assertEquals(ResponseRunStatus.Completed, run.status)
            assertNull(run.failureCategory)
            val task = assertNotNull(taskRepository.findCurrentTaskForConversation(owner(), accepted.detail.conversation.id))
            assertEquals(1, task.plans.size)
            assertEquals(1, successTool.requests.size)
            assertEquals(1, unavailableTool.requests.size)
            Unit
        }

    private suspend fun claim(
        workerId: String = "worker-one",
        now: Instant = TaskFlowIds.Now,
        leaseDuration: Duration = Duration.ofSeconds(30),
        maxAttempts: Int = 3,
    ) = responseRunRepository.claimNextResponseRun(
        ClaimNextResponseRunCommand(
            workerId = workerId,
            now = now,
            leaseDuration = leaseDuration,
            maxAttempts = maxAttempts,
        ),
    )

    private fun planningProcessor(
        understanding: ScriptedUnderstanding,
        planningService: PlanningService,
        logger: StructuredLogger? = null,
    ): ConversationTurnProcessor =
        ConversationTurnProcessor(
            conversationRepository = repository,
            taskRepository = taskRepository,
            understanding = understanding,
            conversationAnswerService = null,
            planningService = planningService,
            logger = logger,
            clock = TaskFlowIds.FixedClock,
            uuidFactory = { UUID.randomUUID() },
            timeZoneId = "Asia/Shanghai",
        )

    private fun conversationAnswerProcessor(
        understanding: ScriptedUnderstanding,
        answerService: ConversationAnswerService,
        realtimeHub: ResponseRunRealtimeHub,
    ): ConversationTurnProcessor =
        ConversationTurnProcessor(
            conversationRepository = repository,
            taskRepository = taskRepository,
            understanding = understanding,
            conversationAnswerService = answerService,
            planningService = null,
            realtimeHub = realtimeHub,
            clock = TaskFlowIds.FixedClock,
            uuidFactory = { UUID.randomUUID() },
            timeZoneId = "Asia/Shanghai",
        )

    private fun worker(
        processor: ConversationTurnProcessor,
        realtimeHub: ResponseRunRealtimeHub? = null,
        logger: StructuredLogger? = null,
    ): ResponseRunWorker =
        ResponseRunWorker(
            responseRunStore = responseRunRepository,
            resultStore = responseRunRepository,
            processor = processor,
            resultConsumer = consumer,
            config = ResponseRunWorkerConfig(
                enabled = true,
                pollInterval = Duration.ofMillis(10),
                leaseDuration = Duration.ofSeconds(30),
                heartbeatInterval = Duration.ofSeconds(10),
                retryBackoff = Duration.ZERO,
                maxAttempts = 3,
            ),
            clock = TaskFlowIds.FixedClock,
            workerId = "worker-result-bus",
            realtimeHub = realtimeHub,
            logger = logger,
        )

    private fun stealResponseRunLease(
        responseRunId: ResponseRunId,
        workerId: String,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE response_runs
                SET lease_owner = ?,
                    updated_at = ?
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, workerId)
                statement.setTimestamp(2, Timestamp.from(TaskFlowIds.Now.plusSeconds(1)))
                statement.setObject(3, responseRunId.value)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private suspend fun storeAnswerResult(
        created: CreatedRun,
        claim: ClaimedResponseRun,
    ): ResponseRunResult {
        val stored = responseRunRepository.storeResponseRunResult(
            StoreResponseRunResultCommand(
                responseRunId = claim.run.id,
                attempt = claim.run.attempt,
                payload = answerPayload(created, "Durable answer", UUID.fromString("00000000-0000-0000-0000-000000004101")),
                now = TaskFlowIds.Now.plusSeconds(1),
            ),
        )
        return assertIs<StoreResponseRunResult.Stored>(stored).result
    }

    private suspend fun assertNoConversationAnswerSideEffects(
        created: CreatedRun,
        expectedStatus: ResponseRunStatus,
    ) {
        val detail = repository.findConversationDetail(owner(), created.conversationId)
        assertEquals(listOf(MessageRole.User), detail?.messages?.map { it.role })
        assertNull(detail?.messages?.single()?.understoodAt)
        assertEquals(expectedStatus, responseRunRepository.findResponseRun(created.runId)?.status)
        assertNull(responseRunRepository.findResponseRunResult(created.runId, 1)?.consumedAt)
    }

    private fun forceRunQueued(runId: ResponseRunId) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE response_runs
                SET status = 'QUEUED',
                    lease_owner = NULL,
                    lease_expires_at = NULL
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, runId.value)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun forceRunState(
        runId: ResponseRunId,
        attempt: Int,
        status: ResponseRunStatus,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                UPDATE response_runs
                SET attempt = ?,
                    status = ?,
                    lease_owner = NULL,
                    lease_expires_at = NULL
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, attempt)
                statement.setString(2, status.name.uppercase())
                statement.setObject(3, runId.value)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun insertAssistantMessage(
        created: CreatedRun,
        assistantMessageId: MessageId,
        text: String,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO conversation_messages (
                    id, conversation_id, role, content, client_message_id, ai_request_id, turn_index, understood_at, created_at
                ) VALUES (?, ?, ?, ?, NULL, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, assistantMessageId.value)
                statement.setObject(2, created.conversationId.value)
                statement.setString(3, MessageRole.Assistant.name)
                statement.setString(4, text)
                statement.setString(5, created.aiRequestId)
                statement.setLong(6, 1L)
                statement.setTimestamp(7, Timestamp.from(TaskFlowIds.Now))
                statement.setTimestamp(8, Timestamp.from(TaskFlowIds.Now))
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun answerPayload(
        created: CreatedRun,
        text: String,
        assistantMessageId: UUID,
    ): ResponseRunResultPayload.ConversationAnswer =
        ResponseRunResultPayload.ConversationAnswer(
            conversationId = created.conversationId.value.toString(),
            userMessageId = created.userMessageId.value.toString(),
            aiRequestId = created.aiRequestId,
            assistantMessageId = assistantMessageId.toString(),
            text = text,
            terminalKind = ConversationTurnTerminalKind.AssistantMessage.name,
        )

    private suspend fun createConversation(
        idSeed: String,
        text: String = "Hello",
    ): CreatedRun {
        val conversationId = ConversationId(uuid(idSeed))
        val userMessageId = MessageId(UUID.randomUUID())
        val runId = ResponseRunId(UUID.randomUUID())
        val aiRequestId = "ai-$idSeed"
        val created = turnStartCommitter.createConversation(
            CreateConversationCommand(
                owner = owner(),
                conversationId = conversationId,
                firstMessageId = userMessageId,
                creationRequestId = "create-$idSeed",
                clientMessageId = "client-$idSeed",
                text = text,
                aiRequestId = aiRequestId,
                responseRunId = runId,
                now = TaskFlowIds.Now,
            ),
        )
        assertIs<CreateConversationResult.Created>(created)
        return CreatedRun(conversationId, userMessageId, runId, aiRequestId)
    }

    private fun owner(): TaskOwner = TaskOwner(TenantId(TaskFlowIds.TenantOne), UserId(TaskFlowIds.UserOne))

    private fun uuid(value: String): UUID = UUID.fromString(value)

    private class RecordingLogSink : LogSink {
        val lines = mutableListOf<String>()

        override fun write(
            level: LogLevel,
            component: String,
            formatted: String,
        ) {
            lines += formatted
        }
    }

    private data class JsonRecordingLogger(
        val logger: StructuredLogger,
        val sink: RecordingLogSink,
    ) {
        fun records(): List<JsonObject> =
            sink.lines.map { Json.parseToJsonElement(it).jsonObject }
    }

    private fun jsonRecordingLogger(): JsonRecordingLogger {
        val sink = RecordingLogSink()
        return JsonRecordingLogger(
            logger = DefaultStructuredLogger(
                traceContext = BackendTraceContext,
                formatter = JsonLogFormatter,
                sink = sink,
                serviceName = "nexusflow-backend",
                environment = RuntimeEnvironment.Local.value,
                minimumLevel = LogLevel.INFO,
            ),
            sink = sink,
        )
    }

    private fun JsonObject.field(key: String): String? =
        this[key]?.jsonPrimitive?.content

    private fun assertStepsInOrder(
        records: List<JsonObject>,
        steps: List<String>,
    ) {
        var cursor = -1
        steps.forEach { step ->
            val next = records.indexOfFirstAfter(cursor) { it.field("step") == step }
            assertTrue(next >= 0, "missing timeline step $step after index $cursor")
            cursor = next
        }
    }

    private fun List<JsonObject>.indexOfFirstAfter(
        cursor: Int,
        predicate: (JsonObject) -> Boolean,
    ): Int {
        for (index in cursor + 1 until size) {
            if (predicate(this[index])) return index
        }
        return -1
    }

    private data class CreatedRun(
        val conversationId: ConversationId,
        val userMessageId: MessageId,
        val runId: ResponseRunId,
        val aiRequestId: String,
    )
}
