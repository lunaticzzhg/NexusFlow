package com.nexusflow.backend.feature.task

import com.nexusflow.ai.planner.PlanComposition
import com.nexusflow.ai.planner.PlanComposer
import com.nexusflow.ai.planner.PlanDirection as AiPlanDirection
import com.nexusflow.ai.planner.PlanDraft as AiPlanDraft
import com.nexusflow.ai.planner.PlanExplainer
import com.nexusflow.ai.planner.PlanExplanation
import com.nexusflow.ai.planner.PlanExplanationContext
import com.nexusflow.ai.planner.PlanModelMetadata
import com.nexusflow.ai.planner.PlanNarrative
import com.nexusflow.ai.planner.PlanNarrativePoint
import com.nexusflow.ai.planner.PlanningContext
import com.nexusflow.ai.provider.StructuredModelRequestDiagnostics
import com.nexusflow.ai.understanding.ClarificationProposal
import com.nexusflow.ai.understanding.ClarificationReasonCategory
import com.nexusflow.ai.understanding.ContextSelectionProposal
import com.nexusflow.ai.understanding.ProposedRequirementChange
import com.nexusflow.ai.understanding.RequirementKind as AiRequirementKind
import com.nexusflow.ai.understanding.RequirementStrength as AiRequirementStrength
import com.nexusflow.ai.understanding.RequirementValue as AiRequirementValue
import com.nexusflow.ai.understanding.UnderstandingContext
import com.nexusflow.ai.understanding.UnderstandingMetadata
import com.nexusflow.ai.understanding.UnderstandingOutcome
import com.nexusflow.ai.understanding.UserIntent
import com.nexusflow.ai.understanding.UserMessageUnderstanding
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
    )
    val taskService = TaskService(
        repository = repository,
        planningService = planningService,
        understanding = understanding,
        clock = clock,
        uuidFactory = taskIds::next,
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
    private vararg val steps: suspend (UnderstandingContext) -> UnderstandingOutcome,
) : UserMessageUnderstanding {
    val calls = mutableListOf<UnderstandingContext>()

    override suspend fun understand(context: UnderstandingContext): UnderstandingOutcome {
        calls += context
        val index = calls.lastIndex.coerceAtMost(steps.lastIndex)
        return steps[index](context)
    }
}

internal class RecordingPlanComposer : PlanComposer {
    val contexts = mutableListOf<PlanningContext>()

    override suspend fun compose(context: PlanningContext): PlanComposition {
        contexts += context
        return PlanComposition(
            drafts = listOf(
                AiPlanDraft(
                    direction = AiPlanDirection.BestMatch,
                    opportunityRefs = listOf(context.opportunities.first().id),
                ),
            ),
            metadata = PlanModelMetadata(provider = "test", model = "planner", promptVersion = "test", providerRequestId = "plan"),
        )
    }
}

internal class RecordingPlanExplainer : PlanExplainer {
    val contexts = mutableListOf<PlanExplanationContext>()

    override suspend fun explain(context: PlanExplanationContext): PlanExplanation {
        contexts += context
        return PlanExplanation(
            narratives = context.plans.map { plan ->
                val firstFact = plan.facts.first().id
                PlanNarrative(
                    planId = plan.planId,
                    title = "Grounded ${plan.direction.name}",
                    summary = "Explained from opportunity snapshots.",
                    reasons = listOf(PlanNarrativePoint("Uses a supplied opportunity snapshot.", listOf(firstFact))),
                    tradeoffs = listOf(PlanNarrativePoint("No extra facts were introduced.", listOf(firstFact))),
                )
            },
            metadata = PlanModelMetadata(provider = "test", model = "explainer", promptVersion = "test", providerRequestId = "explain"),
        )
    }
}

internal class RecordingOpportunityProvider : OpportunityProvider {
    val requests = mutableListOf<OpportunityRequest>()

    override fun discover(request: OpportunityRequest): List<Opportunity> {
        requests += request
        val sportsRequired = request.requirements.any { requirement ->
            requirement.value == com.nexusflow.backend.feature.task.domain.RequirementValue.ActivityDomain("sports")
        }
        return listOf(
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
}

internal fun understandingOutcome(
    intentPatch: String? = null,
    changes: List<ProposedRequirementChange>,
): UnderstandingOutcome =
    UnderstandingOutcome(
        userIntent = UserIntent.PlanRequest,
        intentPatch = intentPatch,
        requirementChanges = changes,
        clarification = ClarificationProposal(
            needed = false,
            missingInformation = emptyList(),
            reasonCategory = ClarificationReasonCategory.None,
            questionDraft = null,
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
): ProposedRequirementChange =
    ProposedRequirementChange(
        kind = AiRequirementKind.ActivityDomain,
        value = AiRequirementValue.ActivityDomain(value),
        strength = strength,
        evidenceText = evidenceText,
    )

internal fun locationChange(
    text: String,
    evidenceText: String,
    strength: AiRequirementStrength = AiRequirementStrength.Prefer,
): ProposedRequirementChange =
    ProposedRequirementChange(
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
