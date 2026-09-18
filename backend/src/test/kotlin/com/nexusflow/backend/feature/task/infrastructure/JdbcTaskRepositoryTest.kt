package com.nexusflow.backend.feature.task.infrastructure

import com.nexusflow.backend.feature.task.TaskFlowIds
import com.nexusflow.backend.feature.task.cleanMigrateAndSeed
import com.nexusflow.backend.feature.task.opportunity
import com.nexusflow.backend.feature.task.postgresDataSource
import com.nexusflow.backend.feature.conversation.domain.ConversationId
import com.nexusflow.backend.feature.conversation.domain.CreateConversationCommand
import com.nexusflow.backend.feature.conversation.domain.CreateConversationResult
import com.nexusflow.backend.feature.conversation.infrastructure.JdbcConversationRepository
import com.nexusflow.backend.feature.task.domain.ApplyConversationUnderstandingCommand
import com.nexusflow.backend.feature.task.domain.ApplyUnderstandingResult
import com.nexusflow.backend.feature.task.domain.AssistantMessageWrite
import com.nexusflow.backend.feature.task.domain.CreateLinkedTaskPersistenceCommand
import com.nexusflow.backend.feature.task.domain.CreateLinkedTaskPersistenceResult
import com.nexusflow.backend.feature.task.domain.MessageId
import com.nexusflow.backend.feature.task.domain.OpportunityFactKey
import com.nexusflow.backend.feature.task.domain.PersistPlansCommand
import com.nexusflow.backend.feature.task.domain.PersistPlansResult
import com.nexusflow.backend.feature.task.domain.Plan
import com.nexusflow.backend.feature.task.domain.PlanDirection
import com.nexusflow.backend.feature.task.domain.PlanEstimatedCost
import com.nexusflow.backend.feature.task.domain.PlanId
import com.nexusflow.backend.feature.task.domain.PlanSourceRef
import com.nexusflow.backend.feature.task.domain.PlanTimelineItem
import com.nexusflow.backend.feature.task.domain.RequirementEvaluation
import com.nexusflow.backend.feature.task.domain.RequirementEvaluationResult
import com.nexusflow.backend.feature.task.domain.RequirementId
import com.nexusflow.backend.feature.task.domain.RequirementKind
import com.nexusflow.backend.feature.task.domain.RequirementSource
import com.nexusflow.backend.feature.task.domain.RequirementStrength
import com.nexusflow.backend.feature.task.domain.RequirementValue
import com.nexusflow.backend.feature.task.domain.RequirementWrite
import com.nexusflow.backend.feature.task.domain.SelectPlanCommand
import com.nexusflow.backend.feature.task.domain.SelectPlanResult
import com.nexusflow.backend.feature.task.domain.SourceAuthority
import com.nexusflow.backend.feature.task.domain.SourceRef
import com.nexusflow.backend.feature.task.domain.TaskId
import com.nexusflow.backend.feature.task.domain.TaskOwner
import com.nexusflow.backend.feature.task.domain.TenantId
import com.nexusflow.backend.feature.task.domain.UpdateRequirementCommand
import com.nexusflow.backend.feature.task.domain.UserId
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class JdbcTaskRepositoryTest {
    private lateinit var dataSource: HikariDataSource
    private lateinit var repository: JdbcTaskRepository
    private lateinit var conversationRepository: JdbcConversationRepository

    @BeforeTest
    fun setUp() {
        dataSource = postgresDataSource("Task repository")
        cleanMigrateAndSeed(dataSource)
        repository = JdbcTaskRepository(dataSource)
        conversationRepository = JdbcConversationRepository(dataSource)
    }

    @AfterTest
    fun tearDown() {
        if (::dataSource.isInitialized) {
            dataSource.close()
        }
    }

    @Test
    fun `repository persists opportunity snapshots plan refs evaluations and reconstructs task detail`() =
        runBlocking {
            val owner = TaskOwner(TenantId(TaskFlowIds.TenantOne), UserId(TaskFlowIds.UserOne))
            val taskId = TaskId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
            val conversationId = ConversationId(UUID.fromString("00000000-0000-0000-0000-000000000011"))
            val messageId = MessageId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
            val requirementId = RequirementId(UUID.fromString("00000000-0000-0000-0000-000000000003"))
            val planId = PlanId(UUID.fromString("00000000-0000-0000-0000-000000000004"))
            val candidateSource = SourceRef(
                label = "Controlled Test Feed",
                uri = "controlled://source",
                sourceUpdatedAt = TaskFlowIds.Now.minusSeconds(600),
                sourceId = "controlled-test-feed",
                authority = SourceAuthority.StructuredSecondary,
                factKeys = setOf(OpportunityFactKey.Title, OpportunityFactKey.StartTime, OpportunityFactKey.Location),
            )
            val candidate = opportunity("00000000-0000-0000-0000-000000000005")
                .copy(sources = listOf(candidateSource))

            val conversation = conversationRepository.createConversation(
                CreateConversationCommand(
                    owner = owner,
                    conversationId = conversationId,
                    firstMessageId = messageId,
                    creationRequestId = "conversation-jdbc",
                    clientMessageId = "conversation-jdbc",
                    text = "Find a movie",
                    aiRequestId = "ai-create",
                    now = TaskFlowIds.Now,
                ),
            )
            assertIs<CreateConversationResult.Created>(conversation)

            val created = repository.createLinkedTask(
                CreateLinkedTaskPersistenceCommand(
                    owner = owner,
                    conversationId = conversationId,
                    taskId = taskId,
                    creationRequestId = "create-jdbc",
                    intent = "Find a movie",
                    now = TaskFlowIds.Now,
                ),
            )
            assertIs<CreateLinkedTaskPersistenceResult.Created>(created)

            val applied = repository.applyConversationUnderstanding(
                ApplyConversationUnderstandingCommand(
                    owner = owner,
                    taskId = taskId,
                    expectedTaskRevision = 1,
                    conversationMessageId = messageId,
                    aiRequestId = "ai-create",
                    intentPatch = null,
                    requirements = listOf(
                        RequirementWrite(
                            id = requirementId,
                            kind = RequirementKind.ActivityDomain,
                            value = RequirementValue.ActivityDomain("movie"),
                            strength = RequirementStrength.Must,
                        ),
                    ),
                    selectedTaskContextKeys = emptyList(),
                    now = TaskFlowIds.Now,
                ),
            )
            val detailAfterUnderstanding = assertIs<ApplyUnderstandingResult.Applied>(applied).detail

            val plan = Plan(
                id = planId,
                taskId = taskId,
                revision = detailAfterUnderstanding.task.revision,
                direction = PlanDirection.BestMatch,
                title = "Snapshot-backed movie plan",
                summary = "Uses persisted opportunity data.",
                timeline = listOf(
                    PlanTimelineItem(
                        title = candidate.title,
                        startAt = candidate.facts.startTime,
                        endAt = candidate.facts.endTime,
                        location = candidate.facts.location?.displayName,
                    ),
                ),
                estimatedCost = PlanEstimatedCost(180, "CNY"),
                commuteMinutes = 18,
                requirementEvaluations = listOf(
                    RequirementEvaluation(requirementId, RequirementEvaluationResult.Satisfied, "Matches movie requirement."),
                ),
                tradeoffs = listOf("Controlled feed only."),
                reasons = listOf("Opportunity snapshot is available."),
                sourceRefs = emptyList(),
                opportunityRefs = listOf(candidate.id),
                validUntil = candidate.validUntil,
                createdAt = TaskFlowIds.Now,
            )
            val persisted = repository.persistPlans(
                PersistPlansCommand(
                    owner = owner,
                    taskId = taskId,
                    expectedTaskRevision = detailAfterUnderstanding.task.revision,
                    opportunities = listOf(candidate),
                    plans = listOf(plan),
                    now = TaskFlowIds.Now,
                ),
            )
            assertIs<PersistPlansResult.Persisted>(persisted)

            val selected = repository.selectCurrentPlan(SelectPlanCommand(owner, taskId, planId, TaskFlowIds.Now))
            val loaded = assertIs<SelectPlanResult.Selected>(selected).detail

            assertEquals(planId, loaded.task.selectedPlanId)
            assertEquals(RequirementSource.UserExplicit, loaded.requirements.single().source)
            assertEquals(messageId, loaded.requirements.single().evidence?.let { (it as com.nexusflow.backend.feature.task.domain.RequirementEvidence.UserMessage).messageId })
            assertEquals(listOf(candidate.id), loaded.plans.single().opportunityRefs)
            assertEquals(
                PlanSourceRef(candidateSource.label, candidateSource.uri, candidateSource.sourceUpdatedAt),
                loaded.plans.single().sourceRefs.single(),
            )
            assertEquals(requirementId, loaded.plans.single().requirementEvaluations.single().requirementId)
            assertEquals("Matches movie requirement.", loaded.plans.single().requirementEvaluations.single().explanation)
            assertEquals(candidate.facts.location?.displayName, loaded.plans.single().timeline.single().location)

            val snapshotJson = opportunitySnapshotJson(candidate.id.value)
            assertEquals(candidate.title, snapshotJson.getValue("title"))
            assertEquals("180", snapshotJson.getValue("price"))
            assertEquals(candidateSource.label, snapshotJson.getValue("sourceLabel"))
            assertEquals(candidateSource.sourceId, snapshotJson.getValue("sourceId"))
            assertEquals(candidateSource.authority.name, snapshotJson.getValue("sourceAuthority"))
            assertEquals("Location,StartTime,Title", snapshotJson.getValue("sourceFactKeys"))
            assertEquals(1, countRows("plan_opportunities"))
            assertEquals(1, countRows("plan_requirement_evaluations"))
        }

    @Test
    fun `migrated opportunity snapshot kind constraint accepts expanded M2 kinds`() {
        dataSource.connection.use { connection ->
            insertOpportunitySnapshotKind(connection, "00000000-0000-0000-0000-000000000105", "LiveEvents")
            insertOpportunitySnapshotKind(connection, "00000000-0000-0000-0000-000000000106", "Outdoor")
        }
    }

    @Test
    fun `stale planning revision is rejected without persisting plans`() =
        runBlocking {
            val owner = TaskOwner(TenantId(TaskFlowIds.TenantOne), UserId(TaskFlowIds.UserOne))
            val taskId = TaskId(UUID.fromString("00000000-0000-0000-0000-000000000201"))
            val conversationId = ConversationId(UUID.fromString("00000000-0000-0000-0000-000000000211"))
            val messageId = MessageId(UUID.fromString("00000000-0000-0000-0000-000000000202"))
            val requirementId = RequirementId(UUID.fromString("00000000-0000-0000-0000-000000000203"))
            val planId = PlanId(UUID.fromString("00000000-0000-0000-0000-000000000204"))
            val candidate = opportunity("00000000-0000-0000-0000-000000000205")

            assertIs<CreateConversationResult.Created>(
                conversationRepository.createConversation(
                    CreateConversationCommand(
                        owner = owner,
                        conversationId = conversationId,
                        firstMessageId = messageId,
                        creationRequestId = "conversation-stale-planning",
                        clientMessageId = "conversation-stale-planning",
                        text = "Find a movie",
                        aiRequestId = "ai-stale-planning",
                        now = TaskFlowIds.Now,
                    ),
                ),
            )
            assertIs<CreateLinkedTaskPersistenceResult.Created>(
                repository.createLinkedTask(
                    CreateLinkedTaskPersistenceCommand(
                        owner = owner,
                        conversationId = conversationId,
                        taskId = taskId,
                        creationRequestId = "create-stale-planning",
                        intent = "Find a movie",
                        now = TaskFlowIds.Now,
                    ),
                ),
            )
            val applied = assertIs<ApplyUnderstandingResult.Applied>(
                repository.applyConversationUnderstanding(
                    ApplyConversationUnderstandingCommand(
                        owner = owner,
                        taskId = taskId,
                        expectedTaskRevision = 1,
                        conversationMessageId = messageId,
                        aiRequestId = "ai-stale-planning",
                        intentPatch = null,
                        requirements = listOf(
                            RequirementWrite(
                                id = requirementId,
                                kind = RequirementKind.Location,
                                value = RequirementValue.Location("Futian"),
                                strength = RequirementStrength.Must,
                            ),
                        ),
                        selectedTaskContextKeys = emptyList(),
                        now = TaskFlowIds.Now.plusSeconds(1),
                    ),
                ),
            )
            val staleRevision = applied.detail.task.revision
            val stalePlan = Plan(
                id = planId,
                taskId = taskId,
                revision = staleRevision,
                direction = PlanDirection.BestMatch,
                title = "Stale movie plan",
                summary = "This plan belongs to the previous task revision.",
                timeline = listOf(
                    PlanTimelineItem(
                        title = candidate.title,
                        startAt = candidate.facts.startTime,
                        endAt = candidate.facts.endTime,
                        location = candidate.facts.location?.displayName,
                    ),
                ),
                estimatedCost = PlanEstimatedCost(180, "CNY"),
                commuteMinutes = 18,
                requirementEvaluations = listOf(
                    RequirementEvaluation(requirementId, RequirementEvaluationResult.Satisfied, "Matched stale input."),
                ),
                tradeoffs = emptyList(),
                reasons = listOf("Generated before the task changed."),
                sourceRefs = emptyList(),
                opportunityRefs = listOf(candidate.id),
                validUntil = candidate.validUntil,
                createdAt = TaskFlowIds.Now.plusSeconds(2),
            )

            val mutation = repository.updateRequirement(
                UpdateRequirementCommand(
                    owner = owner,
                    taskId = taskId,
                    requirementId = requirementId,
                    kind = RequirementKind.Location,
                    value = RequirementValue.Location("Nanshan"),
                    strength = RequirementStrength.Must,
                    now = TaskFlowIds.Now.plusSeconds(3),
                ),
            )
            assertIs<com.nexusflow.backend.feature.task.domain.RequirementMutationResult.Mutated>(mutation)

            val persisted = repository.persistPlans(
                PersistPlansCommand(
                    owner = owner,
                    taskId = taskId,
                    expectedTaskRevision = staleRevision,
                    opportunities = listOf(candidate),
                    plans = listOf(stalePlan),
                    now = TaskFlowIds.Now.plusSeconds(4),
                ),
            )

            assertEquals(PersistPlansResult.StaleTaskRevision, persisted)
            assertEquals(staleRevision + 1, repository.findTaskDetail(owner, taskId)!!.task.revision)
            assertEquals(0, countRows("plans"))
            assertEquals(0, countRows("opportunity_snapshots"))
        }

    private fun insertOpportunitySnapshotKind(
        connection: java.sql.Connection,
        id: String,
        kind: String,
    ) {
        connection.prepareStatement(
            """
            INSERT INTO opportunity_snapshots (
                id,
                provider,
                external_key,
                kind,
                title,
                facts_json,
                sources_json,
                observed_at,
                valid_until
            ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, UUID.fromString(id))
            statement.setString(2, kind.lowercase())
            statement.setString(3, "$kind-1001")
            statement.setString(4, kind)
            statement.setString(5, "$kind Candidate")
            statement.setString(6, """{"summary":null}""")
            statement.setString(7, """[]""")
            statement.setTimestamp(8, Timestamp.from(TaskFlowIds.Now))
            statement.setTimestamp(9, Timestamp.from(TaskFlowIds.Now.plusSeconds(3_600)))
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun opportunitySnapshotJson(opportunityId: UUID): Map<String, String> =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT title, facts_json::text, sources_json::text
                FROM opportunity_snapshots
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, opportunityId)
                statement.executeQuery().use { result ->
                    result.next()
                    val facts = JsonFormat.parseToJsonElement(result.getString("facts_json")).jsonObject
                    val sources = JsonFormat.parseToJsonElement(result.getString("sources_json")).jsonArray
                    mapOf(
                        "title" to result.getString("title"),
                        "price" to facts.getValue("price").jsonObject.getValue("wholeUnits").jsonPrimitive.content,
                        "sourceLabel" to sources.first().jsonObject.getValue("label").jsonPrimitive.content,
                        "sourceId" to sources.first().jsonObject.getValue("sourceId").jsonPrimitive.content,
                        "sourceAuthority" to sources.first().jsonObject.getValue("sourceAuthority").jsonPrimitive.content,
                        "sourceFactKeys" to sources.first()
                            .jsonObject
                            .getValue("factKeys")
                            .jsonArray
                            .joinToString(",") { it.jsonPrimitive.content },
                    )
                }
            }
        }

    private fun countRows(table: String): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $table").use { result ->
                    result.next()
                    result.getInt(1)
                }
            }
        }

    private companion object {
        val JsonFormat = Json { ignoreUnknownKeys = true }
    }
}
