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
import com.nexusflow.contracts.backendai.planning.CreatePlansRequest
import com.nexusflow.contracts.backendai.common.StructuredModelRequestDiagnostics
import com.nexusflow.contracts.backendai.common.CapabilityUnavailableException
import com.nexusflow.contracts.backendai.understanding.ClarificationProposal
import com.nexusflow.contracts.backendai.understanding.ClarificationReasonCategory
import com.nexusflow.contracts.backendai.understanding.ContextSelectionProposal
import com.nexusflow.contracts.backendai.understanding.RequirementChangeProposal
import com.nexusflow.contracts.backendai.understanding.RequirementKind as AiRequirementKind
import com.nexusflow.contracts.backendai.understanding.RequirementStrength as AiRequirementStrength
import com.nexusflow.contracts.backendai.understanding.RequirementValue as AiRequirementValue
import com.nexusflow.contracts.backendai.understanding.UnderstandMessageRequest
import com.nexusflow.contracts.backendai.understanding.UnderstandingMetadata
import com.nexusflow.contracts.backendai.understanding.UnderstandMessageResult
import com.nexusflow.contracts.backendai.understanding.UserIntent
import com.nexusflow.contracts.backendai.understanding.UserMessageUnderstanding
import com.nexusflow.backend.core.identity.ActorContext
import com.nexusflow.backend.feature.task.application.PlanningService
import com.nexusflow.backend.feature.task.application.TaskService
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
import org.flywaydb.core.Flyway
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Timestamp
import java.time.Clock
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
    opportunityProvider: OpportunityProvider = RecordingOpportunityProvider(),
    planComposer: RecordingPlanComposer = RecordingPlanComposer(),
    planExplainer: RecordingPlanExplainer = RecordingPlanExplainer(),
    taskIds: UuidSequence = UuidSequence(),
    planIds: UuidSequence = UuidSequence(500),
    clock: Clock = TaskFlowIds.FixedClock,
    logger: StructuredLogger? = null,
): TaskService =
    createTaskServices(
        dataSource = dataSource,
        understanding = understanding,
        opportunityProvider = opportunityProvider,
        planComposer = planComposer,
        planExplainer = planExplainer,
        taskIds = taskIds,
        planIds = planIds,
        clock = clock,
        logger = logger,
    ).taskService

internal fun createTaskServices(
    dataSource: DataSource,
    understanding: UserMessageUnderstanding,
    opportunityProvider: OpportunityProvider = RecordingOpportunityProvider(),
    planComposer: RecordingPlanComposer = RecordingPlanComposer(),
    planExplainer: RecordingPlanExplainer = RecordingPlanExplainer(),
    taskIds: UuidSequence = UuidSequence(),
    planIds: UuidSequence = UuidSequence(500),
    clock: Clock = TaskFlowIds.FixedClock,
    logger: StructuredLogger? = null,
): TaskServices {
    val repository = JdbcTaskRepository(dataSource)
    val planningService = PlanningService(
        repository = repository,
        opportunityProvider = opportunityProvider,
        planValidator = PlanValidator(),
        planComposer = planComposer,
        planExplainer = planExplainer,
        clock = clock,
        uuidFactory = planIds::next,
        timeZoneId = "Asia/Shanghai",
        logger = logger,
    )
    val taskService = TaskService(
        repository = repository,
        planningService = planningService,
        understanding = understanding,
        clock = clock,
        uuidFactory = taskIds::next,
        logger = logger,
    )
    return TaskServices(taskService, planningService, repository, planComposer, planExplainer)
}

internal data class TaskServices(
    val taskService: TaskService,
    val planningService: PlanningService,
    val repository: JdbcTaskRepository,
    val planComposer: RecordingPlanComposer,
    val planExplainer: RecordingPlanExplainer,
)

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

    override fun discover(request: OpportunityRequest): List<Opportunity> {
        requests += request
        discoverFailure?.let { throw it }
        return opportunityFactory(request)
    }
}

internal fun planningUnavailable(): Throwable = CapabilityUnavailableException()

internal fun understandingOutcome(
    intentPatch: String? = null,
    changes: List<RequirementChangeProposal>,
    clarificationNeeded: Boolean = false,
    questionDraft: String? = null,
): UnderstandMessageResult =
    UnderstandMessageResult(
        userIntent = UserIntent.PlanRequest,
        intentPatch = intentPatch,
        requirementChanges = changes,
        clarification = ClarificationProposal(
            needed = clarificationNeeded,
            missingInformation = if (clarificationNeeded) listOf("details") else emptyList(),
            reasonCategory = if (clarificationNeeded) ClarificationReasonCategory.MissingRequiredInformation else ClarificationReasonCategory.None,
            questionDraft = questionDraft,
        ),
        contextSelection = ContextSelectionProposal(),
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
): RequirementChangeProposal =
    RequirementChangeProposal(
        kind = AiRequirementKind.ActivityDomain,
        value = AiRequirementValue.ActivityDomain(value),
        strength = strength,
        evidenceText = evidenceText,
    )

internal fun locationChange(
    text: String,
    evidenceText: String,
    strength: AiRequirementStrength = AiRequirementStrength.Prefer,
): RequirementChangeProposal =
    RequirementChangeProposal(
        kind = AiRequirementKind.Location,
        value = AiRequirementValue.Location(text),
        strength = strength,
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
            price = MoneyFact(180, "CNY"),
            commute = DurationFact(18),
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
