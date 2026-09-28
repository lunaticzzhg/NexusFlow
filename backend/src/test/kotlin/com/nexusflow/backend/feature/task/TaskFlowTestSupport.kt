package com.nexusflow.backend.feature.task

import com.nexusflow.contracts.backendai.planning.CreatePlansResult
import com.nexusflow.contracts.backendai.planning.PlanComposer
import com.nexusflow.contracts.backendai.planning.PlanDirection as AiPlanDirection
import com.nexusflow.contracts.backendai.planning.PlanProposal as AiPlanProposal
import com.nexusflow.contracts.backendai.planning.PlanExplainer
import com.nexusflow.contracts.backendai.planning.ExplainPlansResult
import com.nexusflow.contracts.backendai.planning.ExplainPlansRequest
import com.nexusflow.contracts.backendai.planning.PlanModelMetadata
import com.nexusflow.contracts.backendai.planning.PlanNarrative
import com.nexusflow.contracts.backendai.planning.PlanNarrativePoint
import com.nexusflow.contracts.backendai.planning.PlanningResearchCapability
import com.nexusflow.contracts.backendai.planning.PlanningResearchRequest
import com.nexusflow.contracts.backendai.planning.PlanningResearchResult
import com.nexusflow.contracts.backendai.planning.CreatePlansRequest
import com.nexusflow.contracts.backendai.answer.AnswerNeedCoveragePayload
import com.nexusflow.contracts.backendai.answer.AnswerNeedCoverageStatus
import com.nexusflow.contracts.backendai.answer.ComposeConversationAnswerRequest
import com.nexusflow.contracts.backendai.answer.ComposeConversationAnswerResult
import com.nexusflow.contracts.backendai.answer.ConversationAnsweringCapability
import com.nexusflow.contracts.backendai.answer.StreamingConversationAnsweringCapability
import com.nexusflow.contracts.backendai.conversation.ConversationDecisionCapability
import com.nexusflow.contracts.backendai.conversation.ConversationMessagePayload
import com.nexusflow.contracts.backendai.conversation.ConversationTurnCapability
import com.nexusflow.contracts.backendai.conversation.ConversationTurnMetadata
import com.nexusflow.contracts.backendai.conversation.ConversationTurnRequest
import com.nexusflow.contracts.backendai.conversation.ConversationTurnResult
import com.nexusflow.contracts.backendai.conversation.ConversationDecisionRequest
import com.nexusflow.contracts.backendai.conversation.ConversationDecisionResult
import com.nexusflow.contracts.backendai.conversation.InformationNeedMode
import com.nexusflow.contracts.backendai.conversation.InformationNeedProposal
import com.nexusflow.contracts.backendai.conversation.ReadOnlyToolCallProposal
import com.nexusflow.contracts.backendai.common.StructuredModelRequestDiagnostics
import com.nexusflow.contracts.backendai.common.CapabilityUnavailableException
import com.nexusflow.contracts.backendai.understanding.ClarificationProposal
import com.nexusflow.contracts.backendai.understanding.ClarificationReasonCategory
import com.nexusflow.contracts.backendai.understanding.ContextSelectionProposal
import com.nexusflow.contracts.backendai.understanding.ConstraintDeltaOperation
import com.nexusflow.contracts.backendai.understanding.ConstraintDeltaProposal
import com.nexusflow.contracts.backendai.understanding.RequirementKind as AiRequirementKind
import com.nexusflow.contracts.backendai.understanding.RequirementStrength as AiRequirementStrength
import com.nexusflow.contracts.backendai.understanding.RequirementValue as AiRequirementValue
import com.nexusflow.contracts.backendai.understanding.TurnIntent
import com.nexusflow.contracts.backendai.understanding.UnderstandMessageRequest
import com.nexusflow.contracts.backendai.understanding.UnderstandingMetadata
import com.nexusflow.contracts.backendai.understanding.UnderstandMessageResult
import com.nexusflow.contracts.backendai.understanding.UserMessageUnderstanding
import com.nexusflow.backend.feature.research.application.ReadToolCatalog
import com.nexusflow.backend.feature.research.application.ReadTool
import com.nexusflow.backend.feature.research.application.ReadToolActivityKind
import com.nexusflow.backend.feature.research.application.ReadToolCall
import com.nexusflow.backend.feature.research.application.ReadToolDefinition
import com.nexusflow.backend.feature.research.application.ReadToolEvidence
import com.nexusflow.backend.feature.research.application.ReadToolEvidencePayload
import com.nexusflow.backend.feature.research.application.ReadToolExecutionContext
import com.nexusflow.backend.feature.research.application.ReadToolFact
import com.nexusflow.backend.feature.research.application.ReadToolFactKind
import com.nexusflow.backend.feature.research.application.ReadToolFactValue
import com.nexusflow.backend.feature.research.application.ReadToolExecutor
import com.nexusflow.backend.feature.research.application.ReadToolKey
import com.nexusflow.backend.feature.research.application.ReadToolOutcome
import com.nexusflow.backend.core.identity.ActorContext
import com.nexusflow.backend.core.aicontext.ModelContextAssembler
import com.nexusflow.backend.core.aicontext.ModelContextCatalog
import com.nexusflow.backend.feature.conversation.application.ConversationService
import com.nexusflow.backend.feature.conversation.application.ConversationTurnProcessor
import com.nexusflow.backend.feature.conversation.application.ResponseRunResultConsumer
import com.nexusflow.backend.feature.conversation.application.ResponseRunWorker
import com.nexusflow.backend.feature.conversation.application.ResponseRunWorkerConfig
import com.nexusflow.backend.feature.conversation.infrastructure.JdbcConversationAnswerCommitter
import com.nexusflow.backend.feature.conversation.infrastructure.JdbcConversationRepository
import com.nexusflow.backend.feature.conversation.infrastructure.JdbcConversationTurnStartCommitter
import com.nexusflow.backend.feature.responserun.infrastructure.JdbcResponseRunRepository
import com.nexusflow.backend.feature.conversation.application.answer.ConversationAnswerService
import com.nexusflow.backend.feature.task.application.PlanningService
import com.nexusflow.backend.feature.task.application.TaskService
import com.nexusflow.backend.feature.research.application.readtool.MovieDiscoveryKey
import com.nexusflow.backend.feature.research.application.readtool.MovieShowtimesKey
import com.nexusflow.backend.feature.research.application.readtool.WebSearchKey
import com.nexusflow.backend.feature.task.domain.ActivityModeValue
import com.nexusflow.backend.feature.task.domain.AvailabilityFact
import com.nexusflow.backend.feature.task.domain.DurationFact
import com.nexusflow.backend.feature.task.domain.FactValue
import com.nexusflow.backend.feature.task.domain.LocationFact
import com.nexusflow.backend.feature.task.domain.MoneyFact
import com.nexusflow.backend.feature.task.domain.Opportunity
import com.nexusflow.backend.feature.task.domain.OpportunityFacts
import com.nexusflow.backend.feature.task.domain.OpportunityId
import com.nexusflow.backend.feature.task.domain.OpportunityKind
import com.nexusflow.backend.feature.task.domain.OpportunityProvider
import com.nexusflow.backend.feature.task.domain.OpportunityRequest
import com.nexusflow.backend.feature.task.domain.PlanValidator
import com.nexusflow.backend.feature.task.domain.SourceRef
import com.nexusflow.backend.feature.task.infrastructure.JdbcTaskRepository
import com.nexusflow.backend.test.PostgresTestGate
import com.nexusflow.observability.StructuredLogger
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.datetime.Instant as KotlinInstant
import kotlinx.serialization.json.JsonObject
import org.flywaydb.core.Flyway
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource

internal fun taskActor(
    tenantId: UUID = TaskFlowIds.TenantOne,
    userId: UUID = TaskFlowIds.UserOne,
    scopes: Set<String> = setOf("orbit.tasks.read", "orbit.tasks.write"),
): ActorContext =
    ActorContext(
        tenantId = tenantId.toString(),
        userId = userId.toString(),
        scopes = scopes,
    )

internal fun createTaskService(
    dataSource: DataSource,
    understanding: UserMessageUnderstanding,
    planningResearch: PlanningResearchCapability = RecordingPlanningResearch(),
    readToolCatalog: ReadToolCatalog = defaultPlanningReadToolCatalog(),
    readToolExecutor: ReadToolExecutor = ReadToolExecutor(readToolCatalog),
    planComposer: RecordingPlanComposer = RecordingPlanComposer(),
    planExplainer: RecordingPlanExplainer = RecordingPlanExplainer(),
    conversationAnswerService: ConversationAnswerService? = null,
    modelContextCatalog: ModelContextCatalog? = null,
    modelContextAssembler: ModelContextAssembler? = modelContextCatalog?.let(::ModelContextAssembler),
    taskIds: UuidSequence = UuidSequence(),
    planIds: UuidSequence = UuidSequence(500),
    clock: Clock = TaskFlowIds.FixedClock,
    logger: StructuredLogger? = null,
): TaskService =
    createTaskServices(
        dataSource = dataSource,
        understanding = understanding,
        planningResearch = planningResearch,
        readToolCatalog = readToolCatalog,
        readToolExecutor = readToolExecutor,
        planComposer = planComposer,
        planExplainer = planExplainer,
        conversationAnswerService = conversationAnswerService,
        modelContextCatalog = modelContextCatalog,
        modelContextAssembler = modelContextAssembler,
        taskIds = taskIds,
        planIds = planIds,
        clock = clock,
        logger = logger,
    ).taskService

internal fun createTaskServices(
    dataSource: DataSource,
    understanding: UserMessageUnderstanding,
    planningResearch: PlanningResearchCapability = RecordingPlanningResearch(),
    readToolCatalog: ReadToolCatalog = defaultPlanningReadToolCatalog(),
    readToolExecutor: ReadToolExecutor = ReadToolExecutor(readToolCatalog),
    planComposer: RecordingPlanComposer = RecordingPlanComposer(),
    planExplainer: RecordingPlanExplainer = RecordingPlanExplainer(),
    conversationAnswerService: ConversationAnswerService? = null,
    modelContextCatalog: ModelContextCatalog? = null,
    modelContextAssembler: ModelContextAssembler? = modelContextCatalog?.let(::ModelContextAssembler),
    taskIds: UuidSequence = UuidSequence(),
    planIds: UuidSequence = UuidSequence(500),
    clock: Clock = TaskFlowIds.FixedClock,
    logger: StructuredLogger? = null,
): TaskServices {
    val repository = JdbcTaskRepository(dataSource)
    val planningService = PlanningService(
        repository = repository,
        planValidator = PlanValidator(),
        planningResearch = planningResearch,
        readToolCatalog = readToolCatalog,
        readToolExecutor = readToolExecutor,
        planComposer = planComposer,
        planExplainer = planExplainer,
        clock = clock,
        uuidFactory = planIds::next,
        logger = logger,
    )
    val taskService = TaskService(
        repository = repository,
        planningService = planningService,
        clock = clock,
    )
    return TaskServices(taskService, planningService, repository, planComposer, planExplainer)
}

internal fun createConversationServices(
    dataSource: DataSource,
    understanding: UserMessageUnderstanding? = null,
    planningResearch: PlanningResearchCapability = RecordingPlanningResearch(),
    readToolCatalog: ReadToolCatalog = defaultPlanningReadToolCatalog(),
    readToolExecutor: ReadToolExecutor = ReadToolExecutor(readToolCatalog),
    planComposer: RecordingPlanComposer = RecordingPlanComposer(),
    planExplainer: RecordingPlanExplainer = RecordingPlanExplainer(),
    conversationAnswerService: ConversationAnswerService? = null,
    conversationTurn: ConversationTurnCapability? = understanding?.let(::UnderstandingBackedConversationTurn),
    taskIds: UuidSequence = UuidSequence(),
    planIds: UuidSequence = UuidSequence(500),
    clock: Clock = TaskFlowIds.FixedClock,
    logger: StructuredLogger? = null,
): ConversationServices {
    val repository = JdbcTaskRepository(dataSource)
    val conversationRepository = JdbcConversationRepository(dataSource)
    val conversationTurnStartCommitter = JdbcConversationTurnStartCommitter(dataSource)
    val responseRunRepository = JdbcResponseRunRepository(dataSource)
    val planningService = PlanningService(
        repository = repository,
        planValidator = PlanValidator(),
        planningResearch = planningResearch,
        readToolCatalog = readToolCatalog,
        readToolExecutor = readToolExecutor,
        planComposer = planComposer,
        planExplainer = planExplainer,
        clock = clock,
        uuidFactory = planIds::next,
        logger = logger,
    )
    val conversationService = ConversationService(
        conversationRepository = conversationRepository,
        conversationTurnStartCommitter = conversationTurnStartCommitter,
        taskRepository = repository,
        planningService = planningService,
        conversationAnswerService = conversationAnswerService,
        clock = clock,
        uuidFactory = taskIds::next,
        logger = logger,
    )
    val responseRunWorker = ResponseRunWorker(
        responseRunStore = responseRunRepository,
        resultStore = responseRunRepository,
        processor = ConversationTurnProcessor(
            conversationRepository = conversationRepository,
            taskRepository = repository,
            conversationAnswerService = conversationAnswerService,
            conversationTurn = conversationTurn,
            planningService = planningService,
            clock = clock,
            uuidFactory = taskIds::next,
        ),
        resultConsumer = ResponseRunResultConsumer(JdbcConversationAnswerCommitter(dataSource), repository, clock),
        config = ResponseRunWorkerConfig(
            enabled = true,
            pollInterval = Duration.ofMillis(10),
            leaseDuration = Duration.ofSeconds(30),
            heartbeatInterval = Duration.ofSeconds(10),
            retryBackoff = Duration.ZERO,
            maxAttempts = 3,
        ),
        clock = clock,
        workerId = "test-response-run-worker",
    )
    return ConversationServices(conversationService, planningService, repository, planComposer, planExplainer, responseRunWorker)
}

internal data class TaskServices(
    val taskService: TaskService,
    val planningService: PlanningService,
    val repository: JdbcTaskRepository,
    val planComposer: RecordingPlanComposer,
    val planExplainer: RecordingPlanExplainer,
)

internal data class ConversationServices(
    val conversationService: ConversationService,
    val planningService: PlanningService,
    val repository: JdbcTaskRepository,
    val planComposer: RecordingPlanComposer,
    val planExplainer: RecordingPlanExplainer,
    val responseRunWorker: ResponseRunWorker,
)

internal suspend fun ConversationServices.drainResponseRuns(maxRuns: Int = 8) {
    repeat(maxRuns) {
        if (!responseRunWorker.runOnce()) return
    }
}

internal class ScriptedUnderstanding(
    private vararg val steps: suspend (UnderstandMessageRequest) -> UnderstandMessageResult,
) : UserMessageUnderstanding {
    val calls = mutableListOf<UnderstandMessageRequest>()

    override suspend fun understand(context: UnderstandMessageRequest): UnderstandMessageResult {
        calls += context
        val index = calls.lastIndex.coerceAtMost(steps.lastIndex)
        return steps[index](context)
    }
}



internal class ScriptedConversationTurn(
    private vararg val steps: suspend (ConversationTurnRequest, suspend (String) -> Unit) -> ConversationTurnResult,
) : ConversationTurnCapability {
    val requests = mutableListOf<ConversationTurnRequest>()

    override suspend fun execute(
        request: ConversationTurnRequest,
        onAnswerDelta: suspend (String) -> Unit,
    ): ConversationTurnResult {
        requests += request
        val index = requests.lastIndex.coerceAtMost(steps.lastIndex)
        return steps[index](request, onAnswerDelta)
    }
}

internal class UnderstandingBackedConversationTurn(
    private val understanding: UserMessageUnderstanding,
) : ConversationTurnCapability {
    override suspend fun execute(
        request: ConversationTurnRequest,
        onAnswerDelta: suspend (String) -> Unit,
    ): ConversationTurnResult {
        val result = understanding.understand(
            UnderstandMessageRequest(
                aiRequestId = request.aiRequestId,
                currentMessage = request.currentMessage,
                referenceTime = request.referenceTime,
                timeZoneId = request.timeZoneId,
                activePlanning = request.activePlanning,
                optionalContext = request.optionalContext,
                diagnostics = request.diagnostics,
            ),
        )
        return when (result.turnIntent) {
            TurnIntent.Planning -> ConversationTurnResult.Planning(
                planningGoalPatch = result.planningGoalPatch,
                constraintDeltas = result.constraintDeltas,
                clarification = result.clarification,
                contextSelection = result.contextSelection,
                metadata = ConversationTurnMetadata(
                    provider = result.metadata.provider,
                    model = result.metadata.model,
                    promptVersion = result.metadata.promptVersion,
                    providerRequestId = result.metadata.providerRequestId,
                    attemptCount = result.metadata.attemptCount,
                    usage = result.metadata.usage,
                    diagnostics = result.metadata.diagnostics,
                ),
            )
            TurnIntent.Conversation -> ConversationTurnResult.Answer(
                answer = "Test conversation answer",
                metadata = ConversationTurnMetadata(
                    provider = result.metadata.provider,
                    model = result.metadata.model,
                    promptVersion = result.metadata.promptVersion,
                    providerRequestId = result.metadata.providerRequestId,
                    attemptCount = result.metadata.attemptCount,
                    usage = result.metadata.usage,
                    diagnostics = result.metadata.diagnostics,
                ),
            )
        }
    }
}

internal fun answerTurn(answer: String): ConversationTurnCapability =
    ScriptedConversationTurn({ _: ConversationTurnRequest, onAnswerDelta: suspend (String) -> Unit ->
        onAnswerDelta(answer)
        ConversationTurnResult.Answer(answer)
    })

internal fun researchTurn(vararg needs: InformationNeedProposal): ConversationTurnCapability =
    ScriptedConversationTurn({ _: ConversationTurnRequest, _: suspend (String) -> Unit ->
        ConversationTurnResult.Research(needs.toList())
    })

internal fun planningTurn(result: UnderstandMessageResult): ConversationTurnCapability =
    ScriptedConversationTurn({ _: ConversationTurnRequest, _: suspend (String) -> Unit ->
        ConversationTurnResult.Planning(
            planningGoalPatch = result.planningGoalPatch,
            constraintDeltas = result.constraintDeltas,
            clarification = result.clarification,
            contextSelection = result.contextSelection,
            metadata = ConversationTurnMetadata(
                provider = result.metadata.provider,
                model = result.metadata.model,
                promptVersion = result.metadata.promptVersion,
                providerRequestId = result.metadata.providerRequestId,
                attemptCount = result.metadata.attemptCount,
                usage = result.metadata.usage,
                diagnostics = result.metadata.diagnostics,
            ),
        )
    })

internal class RecordingConversationDecision(
    private vararg val steps: suspend (ConversationDecisionRequest) -> ConversationDecisionResult,
) : ConversationDecisionCapability {
    val requests = mutableListOf<ConversationDecisionRequest>()

    override suspend fun decide(request: ConversationDecisionRequest): ConversationDecisionResult {
        requests += request
        val index = requests.lastIndex.coerceAtMost(steps.lastIndex)
        return steps[index](request)
    }
}

internal fun directConversationDecision(answer: String): ConversationDecisionResult =
    ConversationDecisionResult(
        informationNeeds = listOf(
            InformationNeedProposal(
                id = "need-model-only",
                question = answer,
                mode = InformationNeedMode.MODEL_ONLY,
            ),
        ),
    )

internal fun researchConversationDecision(vararg toolCalls: ReadOnlyToolCallProposal): ConversationDecisionResult =
    ConversationDecisionResult(
        informationNeeds = listOf(
            InformationNeedProposal(
                id = "need-research",
                question = "Research current facts",
                mode = InformationNeedMode.TOOL_REQUIRED,
                toolCalls = toolCalls.toList(),
            ),
        ),
    )

internal fun noSuitableToolConversationDecision(requestedCapabilityHint: String? = null): ConversationDecisionResult =
    ConversationDecisionResult(
        informationNeeds = listOf(
            InformationNeedProposal(
                id = "need-no-tool",
                question = "Research current facts",
                mode = InformationNeedMode.TOOL_REQUIRED,
                requestedCapabilityHint = requestedCapabilityHint ?: "external facts",
            ),
        ),
    )

internal fun mixedWeatherAndActivityDecision(vararg toolCalls: ReadOnlyToolCallProposal): ConversationDecisionResult =
    ConversationDecisionResult(
        informationNeeds = listOf(
            InformationNeedProposal(
                id = "need-weather",
                question = "确认当前天气",
                mode = InformationNeedMode.TOOL_REQUIRED,
                toolCalls = toolCalls.toList(),
            ),
            InformationNeedProposal(
                id = "need-activity",
                question = "给出下雨时的活动建议",
                mode = InformationNeedMode.MODEL_ONLY,
            ),
        ),
    )

internal fun toolProposal(
    key: String,
    arguments: JsonObject,
): ReadOnlyToolCallProposal =
    ReadOnlyToolCallProposal(toolKey = key, arguments = arguments)

internal fun conversationAnswerService(
    decision: ConversationDecisionCapability,
    answering: ConversationAnsweringCapability? = RecordingQuestionAnswering(),
    streamingAnswering: StreamingConversationAnsweringCapability? = answering as? StreamingConversationAnsweringCapability,
    catalog: ReadToolCatalog = ReadToolCatalog(emptyList()),
    executor: ReadToolExecutor = ReadToolExecutor(catalog),
    logger: StructuredLogger? = null,
): ConversationAnswerService =
    ConversationAnswerService(
        conversationAnswering = answering,
        streamingConversationAnswering = streamingAnswering,
        readToolCatalog = catalog,
        readToolExecutor = executor,
        logger = logger,
    )

internal fun defaultPlanningReadToolCatalog(): ReadToolCatalog =
    ReadToolCatalog(listOf(ControlledPlanningReadTool()))

internal class RecordingPlanningResearch(
    private vararg val steps: suspend (PlanningResearchRequest) -> PlanningResearchResult,
) : PlanningResearchCapability {
    val requests = mutableListOf<PlanningResearchRequest>()
    var researchFailure: Throwable? = null
    var proposalFactory: (PlanningResearchRequest) -> List<ReadOnlyToolCallProposal> = {
        val firstToolKey = it.availableReadTools.firstOrNull()?.toolKey ?: WebSearchKey.value
        listOf(toolProposal(firstToolKey, JsonObject(emptyMap())))
    }

    override suspend fun research(request: PlanningResearchRequest): PlanningResearchResult {
        requests += request
        researchFailure?.let { throw it }
        val calls =
            if (steps.isEmpty()) {
                proposalFactory(request)
            } else {
                val index = requests.lastIndex.coerceAtMost(steps.lastIndex)
                steps[index](request).toolCalls
            }
        return PlanningResearchResult(
            toolCalls = calls,
            metadata = PlanModelMetadata(provider = "test", model = "research", promptVersion = "test", providerRequestId = "research"),
        )
    }
}

internal class ControlledPlanningReadTool(
    key: ReadToolKey = MovieShowtimesKey,
    private val activityKind: ReadToolActivityKind = ReadToolActivityKind.OtherResearch,
    private val outcomeFactory: (JsonObject, ReadToolExecutionContext) -> ReadToolOutcome = { _, _ ->
        ReadToolOutcome.Success(
            ReadToolEvidencePayload(
                listOf(
                    readToolEvidence(
                        sourceId = "controlled-planning-evidence",
                        sourceUrl = "controlled://planning-evidence",
                        sourceKey = key.value,
                        title = "Late movie screening",
                        summary = "Controlled opportunity snapshot.",
                        startAt = TaskFlowIds.Now.plusSeconds(3_600),
                        endAt = TaskFlowIds.Now.plusSeconds(7_200),
                        location = "Nanshan",
                        availability = AvailabilityFact.Available.name,
                    ),
                ),
            ),
        )
    },
) : ReadTool {
    val requests = mutableListOf<Pair<ReadToolCall, ReadToolExecutionContext>>()

    override val definition: ReadToolDefinition = ReadToolDefinition(
        key = key,
        description = "${key.value} planning test tool",
        argumentHint = "test arguments",
        activityKind = activityKind,
    )

    override suspend fun execute(
        proposedArguments: JsonObject,
        context: ReadToolExecutionContext,
    ): ReadToolOutcome {
        requests += ReadToolCall(definition.key, proposedArguments) to context
        return outcomeFactory(proposedArguments, context)
    }
}

internal fun readToolEvidence(
    sourceId: String,
    sourceUrl: String?,
    sourceKey: String,
    title: String,
    summary: String? = null,
    startAt: Instant? = null,
    endAt: Instant? = null,
    location: String? = null,
    availability: String? = null,
): ReadToolEvidence =
    ReadToolEvidence(
        sourceId = sourceId,
        sourceUrl = sourceUrl,
        sourceKey = sourceKey,
        facts = listOfNotNull(
            ReadToolFact(ReadToolFactKind.TITLE, ReadToolFactValue.Text(title)),
            summary?.let { ReadToolFact(ReadToolFactKind.SUMMARY, ReadToolFactValue.Text(it)) },
            startAt?.let { ReadToolFact(ReadToolFactKind.START_TIME, ReadToolFactValue.Timestamp(it)) },
            endAt?.let { ReadToolFact(ReadToolFactKind.END_TIME, ReadToolFactValue.Timestamp(it)) },
            location?.let { ReadToolFact(ReadToolFactKind.LOCATION_NAME, ReadToolFactValue.Text(it)) },
            availability?.let { ReadToolFact(ReadToolFactKind.AVAILABILITY, ReadToolFactValue.Text(it)) },
        ),
    )

internal class RecordingPlanComposer : PlanComposer {
    val contexts = mutableListOf<CreatePlansRequest>()
    var composeFailure: Throwable? = null
    var draftFactory: (CreatePlansRequest) -> List<AiPlanProposal> = { context ->
        listOf(
            AiPlanProposal(
                direction = AiPlanDirection.BestMatch,
                opportunityRefs = listOf(context.opportunities.first().id),
            ),
        )
    }

    override suspend fun compose(context: CreatePlansRequest): CreatePlansResult {
        contexts += context
        composeFailure?.let { throw it }
        return CreatePlansResult(
            drafts = draftFactory(context),
            metadata = PlanModelMetadata(provider = "test", model = "planner", promptVersion = "test", providerRequestId = "plan"),
        )
    }
}

internal class RecordingPlanExplainer : PlanExplainer {
    val contexts = mutableListOf<ExplainPlansRequest>()
    var explainFailure: Throwable? = null
    var narrativeFactory: (ExplainPlansRequest) -> List<PlanNarrative> = { context ->
        context.plans.map { plan ->
            val firstFact = plan.facts.first().id
            PlanNarrative(
                planId = plan.planId,
                title = "Grounded ${plan.direction.name}",
                summary = "Explained from opportunity snapshots.",
                reasons = listOf(PlanNarrativePoint("Uses a supplied opportunity snapshot.", listOf(firstFact))),
                tradeoffs = listOf(PlanNarrativePoint("No extra facts were introduced.", listOf(firstFact))),
            )
        }
    }

    override suspend fun explain(context: ExplainPlansRequest): ExplainPlansResult {
        contexts += context
        explainFailure?.let { throw it }
        return ExplainPlansResult(
            narratives = narrativeFactory(context),
            metadata = PlanModelMetadata(provider = "test", model = "explainer", promptVersion = "test", providerRequestId = "explain"),
        )
    }
}

internal class RecordingQuestionAnswering : StreamingConversationAnsweringCapability {
    val requests = mutableListOf<ComposeConversationAnswerRequest>()
    var answerFailure: Throwable? = null
    var answerText: String? = null
    var deltas: List<String>? = null
    val emittedDeltas = mutableListOf<String>()

    override suspend fun answer(
        request: ComposeConversationAnswerRequest,
        onDelta: suspend (String) -> Unit,
    ): ComposeConversationAnswerResult {
        requests += request
        val text = answerText
            ?: request.informationNeeds.firstOrNull { it.mode == InformationNeedMode.MODEL_ONLY }?.question
            ?: "Grounded answer from evidence."
        (deltas ?: listOf(text)).forEach { delta ->
            emittedDeltas += delta
            onDelta(delta)
        }
        answerFailure?.let { throw it }
        return ComposeConversationAnswerResult(
            answer = deltas?.joinToString(separator = "") ?: text,
            coverage = request.informationNeeds.map { need ->
                val used = need.evidenceSourceIds.takeIf { ids ->
                    need.mode == InformationNeedMode.TOOL_REQUIRED && ids.isNotEmpty()
                }.orEmpty()
                AnswerNeedCoveragePayload(
                    needId = need.id,
                    status = if (need.mode == InformationNeedMode.TOOL_REQUIRED && need.evidenceSourceIds.isEmpty()) {
                        AnswerNeedCoverageStatus.UNRESOLVED
                    } else {
                        AnswerNeedCoverageStatus.ANSWERED
                    },
                    usedEvidenceSourceIds = used,
                )
            },
        )
    }
}

internal class RecordingOpportunityProvider : OpportunityProvider {
    val requests = mutableListOf<OpportunityRequest>()
    var discoverFailure: Throwable? = null
    var opportunityFactory: (OpportunityRequest) -> List<Opportunity> = { request ->
        val sportsRequired = request.requirements.any { requirement ->
            requirement.value == com.nexusflow.backend.feature.task.domain.RequirementValue.ActivityDomain("sports")
        }
        listOf(
            opportunity(
                id = "00000000-0000-0000-0000-000000000101",
                kind = if (sportsRequired) OpportunityKind.Sports else OpportunityKind.Movies,
                title = if (sportsRequired) "Liverpool screening" else "Late movie screening",
                activityMode = ActivityModeValue.OutOfHome,
                location = "Futian",
                topics = if (sportsRequired) "sports,liverpool" else "movie,cinema",
                validUntil = TaskFlowIds.Now.plusSeconds(86_400),
            ),
        )
    }

    override suspend fun discover(request: OpportunityRequest): List<Opportunity> {
        requests += request
        discoverFailure?.let { throw it }
        return opportunityFactory(request)
    }
}

internal fun planningUnavailable(): Throwable = CapabilityUnavailableException()

internal fun understandingOutcome(
    turnIntent: TurnIntent = TurnIntent.Planning,
    intentPatch: String? = null,
    changes: List<ConstraintDeltaProposal>,
    clarificationNeeded: Boolean = false,
    questionDraft: String? = null,
    selectedContextKeys: List<String> = emptyList(),
): UnderstandMessageResult =
    UnderstandMessageResult(
        turnIntent = turnIntent,
        planningGoalPatch = intentPatch,
        constraintDeltas = changes,
        clarification = ClarificationProposal(
            needed = clarificationNeeded,
            missingInformation = if (clarificationNeeded) listOf("details") else emptyList(),
            reasonCategory = if (clarificationNeeded) ClarificationReasonCategory.MissingRequiredInformation else ClarificationReasonCategory.None,
            questionDraft = questionDraft,
        ),
        contextSelection = ContextSelectionProposal(selectedKeys = selectedContextKeys),
        metadata = UnderstandingMetadata(
            provider = "test",
            model = "understanding",
            promptVersion = "test",
            providerRequestId = "understand",
            attemptCount = 1,
            diagnostics = StructuredModelRequestDiagnostics(fullUserPayloadSerializedChars = 10),
        ),
    )

internal fun activityDomainChange(
    value: String,
    evidenceText: String,
    strength: AiRequirementStrength = AiRequirementStrength.Must,
): ConstraintDeltaProposal =
    ConstraintDeltaProposal(
        operation = ConstraintDeltaOperation.Upsert,
        kind = AiRequirementKind.ActivityDomain,
        value = AiRequirementValue.ActivityDomain(value),
        strength = strength,
        evidenceText = evidenceText,
    )

internal fun locationChange(
    text: String,
    evidenceText: String,
    strength: AiRequirementStrength = AiRequirementStrength.Prefer,
): ConstraintDeltaProposal =
    ConstraintDeltaProposal(
        operation = ConstraintDeltaOperation.Upsert,
        kind = AiRequirementKind.Location,
        value = AiRequirementValue.Location(text),
        strength = strength,
        evidenceText = evidenceText,
    )

internal fun budgetChange(
    wholeUnits: Long,
    evidenceText: String,
    currencyCode: String? = "CNY",
    strength: AiRequirementStrength = AiRequirementStrength.Must,
): ConstraintDeltaProposal =
    ConstraintDeltaProposal(
        operation = ConstraintDeltaOperation.Upsert,
        kind = AiRequirementKind.BudgetLimit,
        value = AiRequirementValue.BudgetLimit(wholeUnits = wholeUnits, currencyCode = currencyCode),
        strength = strength,
        evidenceText = evidenceText,
    )

internal fun commuteLimitChange(
    maxMinutes: Int,
    evidenceText: String,
    strength: AiRequirementStrength = AiRequirementStrength.Must,
): ConstraintDeltaProposal =
    ConstraintDeltaProposal(
        operation = ConstraintDeltaOperation.Upsert,
        kind = AiRequirementKind.CommuteLimit,
        value = AiRequirementValue.CommuteLimit(maxMinutes = maxMinutes),
        strength = strength,
        evidenceText = evidenceText,
    )

internal fun removeBudget(evidenceText: String = ""): ConstraintDeltaProposal =
    ConstraintDeltaProposal(
        operation = ConstraintDeltaOperation.Remove,
        kind = AiRequirementKind.BudgetLimit,
        value = null,
        strength = null,
        evidenceText = evidenceText,
    )

internal fun postgresDataSource(testFamily: String): HikariDataSource =
    HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = TaskPostgres.postgres(testFamily).getJdbcUrl()
            username = TaskPostgres.postgres(testFamily).getUsername()
            password = TaskPostgres.postgres(testFamily).getPassword()
            maximumPoolSize = 2
        },
    )

internal fun cleanMigrateAndSeed(dataSource: HikariDataSource) {
    Flyway.configure()
        .dataSource(dataSource)
        .cleanDisabled(false)
        .load()
        .clean()
    Flyway.configure()
        .dataSource(dataSource)
        .load()
        .migrate()
    seedIdentityFixtures(dataSource)
}

internal fun seedIdentityFixtures(dataSource: HikariDataSource) {
    dataSource.connection.use { connection ->
        connection.prepareStatement("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, ?)").use { statement ->
            listOf(TaskFlowIds.TenantOne to "Tenant One", TaskFlowIds.TenantTwo to "Tenant Two").forEach { (id, name) ->
                statement.setObject(1, id)
                statement.setString(2, name)
                statement.setTimestamp(3, Timestamp.from(TaskFlowIds.Now))
                statement.addBatch()
            }
            statement.executeBatch()
        }
        connection.prepareStatement("INSERT INTO users (id, created_at) VALUES (?, ?)").use { statement ->
            listOf(TaskFlowIds.UserOne, TaskFlowIds.UserTwo).forEach { id ->
                statement.setObject(1, id)
                statement.setTimestamp(2, Timestamp.from(TaskFlowIds.Now))
                statement.addBatch()
            }
            statement.executeBatch()
        }
        connection.prepareStatement("INSERT INTO tenant_memberships (tenant_id, user_id, created_at) VALUES (?, ?, ?)").use { statement ->
            listOf(
                TaskFlowIds.TenantOne to TaskFlowIds.UserOne,
                TaskFlowIds.TenantOne to TaskFlowIds.UserTwo,
                TaskFlowIds.TenantTwo to TaskFlowIds.UserTwo,
            ).forEach { (tenantId, userId) ->
                statement.setObject(1, tenantId)
                statement.setObject(2, userId)
                statement.setTimestamp(3, Timestamp.from(TaskFlowIds.Now))
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }
}

internal fun opportunity(
    id: String,
    kind: OpportunityKind = OpportunityKind.Movies,
    title: String = "Late movie screening",
    activityMode: ActivityModeValue = ActivityModeValue.OutOfHome,
    location: String = "Futian",
    topics: String = "movie,cinema",
    price: MoneyFact? = MoneyFact(180, "CNY"),
    commute: DurationFact? = DurationFact(18),
    validUntil: Instant = TaskFlowIds.Now.plusSeconds(86_400),
): Opportunity =
    Opportunity(
        id = OpportunityId(UUID.fromString(id)),
        provider = "Controlled Test Feed",
        externalKey = "controlled://$id",
        kind = kind,
        title = title,
        facts = OpportunityFacts(
            summary = "Controlled opportunity snapshot.",
            startTime = TaskFlowIds.Now.plusSeconds(3_600),
            endTime = TaskFlowIds.Now.plusSeconds(7_200),
            location = LocationFact(location, location.lowercase()),
            activityMode = activityMode,
            price = price,
            commute = commute,
            availability = AvailabilityFact.Available,
            attributes = mapOf("topics" to FactValue.Text(topics), "locations" to FactValue.Text(location)),
        ),
        sources = listOf(SourceRef("Controlled Test Feed", "controlled://source", TaskFlowIds.Now.minusSeconds(600))),
        observedAt = TaskFlowIds.Now,
        validUntil = validUntil,
    )

internal class UuidSequence(start: Int = 1) {
    private var nextValue = start

    fun next(): UUID =
        UUID.fromString("00000000-0000-0000-0000-${nextValue++.toString().padStart(12, '0')}")
}

internal object TaskFlowIds {
    val Now: Instant = Instant.parse("2026-08-28T10:35:00Z")
    val FixedClock: Clock = Clock.fixed(Now, ZoneOffset.UTC)
    val TenantOne: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")
    val TenantTwo: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002")
    val UserOne: UUID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001")
    val UserTwo: UUID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002")
    val UnknownTask: UUID = UUID.fromString("cccccccc-0000-0000-0000-000000000001")

    fun kotlinNow(): KotlinInstant =
        KotlinInstant.fromEpochSeconds(Now.epochSecond, Now.nano.toLong())
}

private object TaskPostgres {
    private var postgresContainer: PostgreSQLContainer? = null

    fun postgres(testFamily: String): PostgreSQLContainer =
        postgresContainer ?: try {
            PostgreSQLContainer("postgres:16-alpine").apply { start() }
                .also { postgresContainer = it }
        } catch (error: IllegalStateException) {
            PostgresTestGate.unavailable(testFamily, error)
        }
}
