package com.nexusflow.backend.feature.task.application

import com.nexusflow.ai.understanding.InvalidStructuredOutputException
import com.nexusflow.backend.feature.task.ScriptedUnderstanding
import com.nexusflow.backend.feature.task.TaskFlowIds
import com.nexusflow.backend.feature.task.activityDomainChange
import com.nexusflow.backend.feature.task.cleanMigrateAndSeed
import com.nexusflow.backend.feature.task.createTaskServices
import com.nexusflow.backend.feature.task.locationChange
import com.nexusflow.backend.feature.task.opportunity
import com.nexusflow.backend.feature.task.postgresDataSource
import com.nexusflow.backend.feature.task.taskActor
import com.nexusflow.backend.feature.task.understandingOutcome
import com.nexusflow.backend.feature.task.domain.MessageRole
import com.nexusflow.backend.feature.task.domain.PersistPlansCommand
import com.nexusflow.backend.feature.task.domain.Plan
import com.nexusflow.backend.feature.task.domain.PlanDirection
import com.nexusflow.backend.feature.task.domain.PlanId
import com.nexusflow.backend.feature.task.domain.PlanTimelineItem
import com.nexusflow.backend.feature.task.domain.RequirementEvaluation
import com.nexusflow.backend.feature.task.domain.RequirementEvaluationResult
import com.nexusflow.backend.feature.task.domain.RequirementEvidence
import com.nexusflow.backend.feature.task.domain.RequirementKind
import com.nexusflow.backend.feature.task.domain.RequirementStrength
import com.nexusflow.backend.feature.task.domain.RequirementValue
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TaskServiceTest {
    private lateinit var dataSource: HikariDataSource

    @BeforeTest
    fun setUp() {
        dataSource = postgresDataSource("Task service")
        cleanMigrateAndSeed(dataSource)
    }

    @AfterTest
    fun tearDown() {
        if (::dataSource.isInitialized) {
            dataSource.close()
        }
    }

    @Test
    fun `first message creates task applies understanding and automatically plans when ready`() =
        runBlocking {
            val services = createTaskServices(
                dataSource = dataSource,
                understanding = ScriptedUnderstanding({
                    understandingOutcome(
                        intentPatch = "Find a movie near Futian",
                        changes = listOf(
                            activityDomainChange("movie", "movie"),
                            locationChange("Futian", "Futian"),
                        ),
                    )
                }),
            )

            val detail = services.taskService.createTask(
                actor = taskActor(),
                clientRequestId = "create-1",
                message = "Find a movie near Futian",
                timeZoneId = "Asia/Shanghai",
            )

            assertEquals("Find a movie near Futian", detail.task.intent)
            assertEquals(2, detail.task.revision)
            assertEquals(1, detail.messages.count { it.role == MessageRole.User })
            assertNotNull(detail.messages.single { it.role == MessageRole.User }.understoodAt)
            assertEquals(2, detail.requirements.size)
            assertTrue(detail.requirements.all { it.evidence is RequirementEvidence.UserMessage })
            assertEquals(1, detail.plans.count { it.revision == detail.task.revision })
            assertEquals(detail.task.revision, services.planComposer.contexts.single().taskRevision)
            assertEquals(detail.plans.single().opportunityRefs, services.repository.findTaskDetail(detail.task.owner, detail.task.id)!!.plans.single().opportunityRefs)
        }

    @Test
    fun `follow up requirement changes create current revision plans while old plans stay as history`() =
        runBlocking {
            val services = createTaskServices(
                dataSource = dataSource,
                understanding = ScriptedUnderstanding(
                    {
                        understandingOutcome(
                            changes = listOf(
                                activityDomainChange("movie", "movie"),
                                locationChange("Futian", "Futian"),
                            ),
                        )
                    },
                    {
                        understandingOutcome(
                            changes = listOf(activityDomainChange("sports", "sports")),
                        )
                    },
                ),
            )
            val created = services.taskService.createTask(taskActor(), "create-2", "Find a movie near Futian", "Asia/Shanghai")
            val firstPlan = created.plans.single()

            val changed = services.taskService.sendMessage(
                actor = taskActor(),
                taskId = created.task.id.value.toString(),
                clientMessageId = "message-1",
                text = "Actually make it sports",
                timeZoneId = "Asia/Shanghai",
            )

            assertEquals(3, changed.task.revision)
            assertEquals(2, changed.plans.size)
            assertEquals(firstPlan.id, changed.plans.single { it.revision == 2L }.id)
            assertNotEquals(firstPlan.id, changed.plans.single { it.revision == 3L }.id)
            assertEquals(null, changed.task.selectedPlanId)
        }

    @Test
    fun `requirement update and delete increment revision clear selected plan and replan when ready`() =
        runBlocking {
            val services = createTaskServices(
                dataSource = dataSource,
                understanding = ScriptedUnderstanding({
                    understandingOutcome(
                        changes = listOf(
                            activityDomainChange("movie", "movie"),
                            locationChange("Futian", "Futian"),
                        ),
                    )
                }),
            )
            val created = services.taskService.createTask(taskActor(), "create-3", "Find a movie near Futian", "Asia/Shanghai")
            val location = created.requirements.single { it.kind == RequirementKind.Location }
            val selected = services.planningService.selectPlan(
                actor = taskActor(),
                taskId = created.task.id.value.toString(),
                planId = created.plans.single().id.value.toString(),
            )
            assertEquals(created.plans.single().id, selected.task.selectedPlanId)

            val updated = services.taskService.updateRequirement(
                actor = taskActor(),
                taskId = created.task.id.value.toString(),
                requirementId = location.id.value.toString(),
                kind = RequirementKind.Location,
                value = RequirementValue.Location("Nanshan"),
                strength = RequirementStrength.Prefer,
            )
            assertEquals(3, updated.task.revision)
            assertEquals(null, updated.task.selectedPlanId)
            assertEquals(1, updated.plans.count { it.revision == 3L })

            val selectedAgain = services.planningService.selectPlan(
                actor = taskActor(),
                taskId = updated.task.id.value.toString(),
                planId = updated.plans.single { it.revision == 3L }.id.value.toString(),
            )
            val deleted = services.taskService.deleteRequirement(
                actor = taskActor(),
                taskId = selectedAgain.task.id.value.toString(),
                requirementId = location.id.value.toString(),
            )

            assertEquals(4, deleted.task.revision)
            assertEquals(null, deleted.task.selectedPlanId)
            assertEquals(1, deleted.requirements.size)
            assertEquals(1, deleted.plans.count { it.revision == 4L })
        }

    @Test
    fun `plan selection accepts only current non expired plans`() =
        runBlocking {
            val services = createTaskServices(
                dataSource = dataSource,
                understanding = ScriptedUnderstanding({
                    understandingOutcome(changes = listOf(activityDomainChange("movie", "movie")))
                }),
            )
            val created = services.taskService.createTask(taskActor(), "create-4", "Find a movie", "Asia/Shanghai")
            val selected = services.planningService.selectPlan(
                actor = taskActor(),
                taskId = created.task.id.value.toString(),
                planId = created.plans.single().id.value.toString(),
            )
            val stalePlan = created.plans.single()
            val changed = services.taskService.sendMessage(
                actor = taskActor(),
                taskId = created.task.id.value.toString(),
                clientMessageId = "message-2",
                text = "movie again",
                timeZoneId = "Asia/Shanghai",
            )

            assertEquals(created.plans.single().id, selected.task.selectedPlanId)
            assertFailsWith<TaskConflictException> {
                services.planningService.selectPlan(
                    actor = taskActor(),
                    taskId = changed.task.id.value.toString(),
                    planId = stalePlan.id.value.toString(),
                )
            }

            val expiredOpportunity = opportunity(
                id = "00000000-0000-0000-0000-000000000901",
                validUntil = TaskFlowIds.Now.minusSeconds(60),
            )
            val expiredPlan = Plan(
                id = PlanId(java.util.UUID.fromString("00000000-0000-0000-0000-000000000902")),
                taskId = changed.task.id,
                revision = changed.task.revision,
                direction = PlanDirection.BestMatch,
                title = "Expired plan",
                summary = "Expired current revision plan.",
                timeline = listOf(PlanTimelineItem("Expired", TaskFlowIds.Now, TaskFlowIds.Now.plusSeconds(3_600), "Futian")),
                estimatedCost = null,
                commuteMinutes = null,
                requirementEvaluations = changed.requirements.map {
                    RequirementEvaluation(it.id, RequirementEvaluationResult.Satisfied)
                },
                tradeoffs = emptyList(),
                reasons = emptyList(),
                sourceRefs = emptyList(),
                opportunityRefs = listOf(expiredOpportunity.id),
                validUntil = TaskFlowIds.Now.minusSeconds(60),
                createdAt = TaskFlowIds.Now,
            )
            services.repository.persistPlans(
                PersistPlansCommand(
                    owner = changed.task.owner,
                    taskId = changed.task.id,
                    expectedTaskRevision = changed.task.revision,
                    opportunities = listOf(expiredOpportunity),
                    plans = listOf(expiredPlan),
                    now = TaskFlowIds.Now,
                ),
            )
            assertFailsWith<TaskConflictException> {
                services.planningService.selectPlan(
                    actor = taskActor(),
                    taskId = changed.task.id.value.toString(),
                    planId = expiredPlan.id.value.toString(),
                )
            }
            Unit
        }

    @Test
    fun `duplicate create and message retries continue pending understanding and planning`() =
        runBlocking {
            val understanding = ScriptedUnderstanding(
                { throw InvalidStructuredOutputException("temporary bad output") },
                { understandingOutcome(changes = listOf(activityDomainChange("movie", "movie"))) },
                { throw InvalidStructuredOutputException("temporary bad output") },
                { understandingOutcome(changes = listOf(locationChange("Futian", "Futian"))) },
            )
            val services = createTaskServices(dataSource = dataSource, understanding = understanding)

            assertFailsWith<TaskDependencyUnavailableException> {
                services.taskService.createTask(taskActor(), "create-retry", "Find a movie", "Asia/Shanghai")
            }
            val createdByRetry = services.taskService.createTask(taskActor(), "create-retry", "Find a movie", "Asia/Shanghai")

            assertEquals(1, createdByRetry.messages.count { it.role == MessageRole.User })
            assertEquals(2, createdByRetry.task.revision)
            assertEquals(1, createdByRetry.plans.count { it.revision == createdByRetry.task.revision })

            assertFailsWith<TaskDependencyUnavailableException> {
                services.taskService.sendMessage(
                    taskActor(),
                    createdByRetry.task.id.value.toString(),
                    "message-retry",
                    "Near Futian",
                    "Asia/Shanghai",
                )
            }
            val sentByRetry = services.taskService.sendMessage(
                taskActor(),
                createdByRetry.task.id.value.toString(),
                "message-retry",
                "Near Futian",
                "Asia/Shanghai",
            )

            assertEquals(2, sentByRetry.messages.count { it.role == MessageRole.User })
            assertEquals(3, sentByRetry.task.revision)
            assertEquals(1, sentByRetry.plans.count { it.revision == sentByRetry.task.revision })
            assertEquals(4, understanding.calls.size)
        }
}
