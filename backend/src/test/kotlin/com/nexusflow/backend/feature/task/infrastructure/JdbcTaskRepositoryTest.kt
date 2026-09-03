package com.nexusflow.backend.feature.task.infrastructure

import com.nexusflow.backend.feature.task.TaskFlowIds
import com.nexusflow.backend.feature.task.cleanMigrateAndSeed
import com.nexusflow.backend.feature.task.opportunity
import com.nexusflow.backend.feature.task.postgresDataSource
import com.nexusflow.backend.feature.task.domain.ApplyUnderstandingCommand
import com.nexusflow.backend.feature.task.domain.ApplyUnderstandingResult
import com.nexusflow.backend.feature.task.domain.AssistantMessageWrite
import com.nexusflow.backend.feature.task.domain.CreateTaskPersistenceCommand
import com.nexusflow.backend.feature.task.domain.CreateTaskPersistenceResult
import com.nexusflow.backend.feature.task.domain.MessageId
import com.nexusflow.backend.feature.task.domain.PersistPlansCommand
import com.nexusflow.backend.feature.task.domain.PersistPlansResult
import com.nexusflow.backend.feature.task.domain.Plan
import com.nexusflow.backend.feature.task.domain.PlanDirection
import com.nexusflow.backend.feature.task.domain.PlanEstimatedCost
import com.nexusflow.backend.feature.task.domain.PlanId
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
import com.nexusflow.backend.feature.task.domain.TaskId
import com.nexusflow.backend.feature.task.domain.TaskOwner
import com.nexusflow.backend.feature.task.domain.TenantId
import com.nexusflow.backend.feature.task.domain.UserId
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    @BeforeTest
    fun setUp() {
        dataSource = postgresDataSource("Task repository")
        cleanMigrateAndSeed(dataSource)
        repository = JdbcTaskRepository(dataSource)
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
            val messageId = MessageId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
            val requirementId = RequirementId(UUID.fromString("00000000-0000-0000-0000-000000000003"))
            val planId = PlanId(UUID.fromString("00000000-0000-0000-0000-000000000004"))
            val candidate = opportunity("00000000-0000-0000-0000-000000000005")

            val created = repository.createTask(
                CreateTaskPersistenceCommand(
                    owner = owner,
                    taskId = taskId,
                    firstMessageId = messageId,
                    creationRequestId = "create-jdbc",
                    message = "Find a movie",
                    aiRequestId = "ai-create",
                    now = TaskFlowIds.Now,
                ),
            )
            assertIs<CreateTaskPersistenceResult.Created>(created)

            val applied = repository.applyUnderstanding(
                ApplyUnderstandingCommand(
                    owner = owner,
                    taskId = taskId,
                    expectedTaskRevision = 1,
                    messageId = messageId,
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
                    assistantMessage = AssistantMessageWrite(
                        id = MessageId(UUID.fromString("00000000-0000-0000-0000-000000000006")),
                        text = "I found movie requirements.",
                    ),
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
            assertEquals(candidate.sources.single().label, loaded.plans.single().sourceRefs.single().label)
            assertEquals(requirementId, loaded.plans.single().requirementEvaluations.single().requirementId)
            assertEquals("Matches movie requirement.", loaded.plans.single().requirementEvaluations.single().explanation)
            assertEquals(candidate.facts.location?.displayName, loaded.plans.single().timeline.single().location)

            val snapshotJson = opportunitySnapshotJson(candidate.id.value)
            assertEquals(candidate.title, snapshotJson.getValue("title"))
            assertEquals("180", snapshotJson.getValue("price"))
            assertEquals(candidate.sources.single().label, snapshotJson.getValue("sourceLabel"))
            assertEquals(1, countRows("plan_opportunities"))
            assertEquals(1, countRows("plan_requirement_evaluations"))
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
