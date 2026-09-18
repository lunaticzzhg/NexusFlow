package com.nexusflow.backend.feature.task.application

import com.nexusflow.backend.core.readtool.ReadToolCatalog
import com.nexusflow.backend.feature.task.ControlledPlanningReadTool
import com.nexusflow.backend.feature.task.RecordingPlanningResearch
import com.nexusflow.backend.feature.task.ScriptedUnderstanding
import com.nexusflow.backend.feature.task.TaskFlowIds
import com.nexusflow.backend.feature.task.UuidSequence
import com.nexusflow.backend.feature.task.activityDomainChange
import com.nexusflow.backend.feature.task.cleanMigrateAndSeed
import com.nexusflow.backend.feature.task.createConversationServices
import com.nexusflow.backend.feature.task.createTaskServices
import com.nexusflow.backend.feature.task.drainResponseRuns
import com.nexusflow.backend.feature.task.locationChange
import com.nexusflow.backend.feature.task.planningUnavailable
import com.nexusflow.backend.feature.task.postgresDataSource
import com.nexusflow.backend.feature.task.taskActor
import com.nexusflow.backend.feature.task.understandingOutcome
import com.nexusflow.backend.feature.task.domain.RequirementKind
import com.nexusflow.backend.feature.task.domain.RequirementStrength
import com.nexusflow.backend.feature.task.domain.RequirementValue
import com.nexusflow.backend.feature.task.domain.TaskDetail
import com.nexusflow.backend.feature.task.domain.TaskOwner
import com.nexusflow.backend.feature.task.domain.TaskId
import com.nexusflow.backend.feature.task.domain.TenantId
import com.nexusflow.backend.feature.task.domain.UserId
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

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
    fun `updateRequirement mutates requirement clears selected plan advances revision and replans`() =
        runBlocking {
            val initial = createLinkedPlanningTask()
            val services = createTaskServicesForMutation()
            val selected = services.planningService.selectPlan(
                actor = taskActor(),
                taskId = initial.task.id.value.toString(),
                planId = initial.currentPlans().single().id.value.toString(),
            )
            assertNotNull(selected.task.selectedPlanId)
            val requirement = selected.requirements.single { it.kind == RequirementKind.Location }
            val startingRevision = selected.task.revision

            val result = services.taskService.updateRequirement(
                actor = taskActor(),
                taskId = selected.task.id.value.toString(),
                requirementId = requirement.id.value.toString(),
                kind = RequirementKind.Location,
                value = RequirementValue.Location("Nanshan"),
                strength = RequirementStrength.Must,
            )

            assertEquals(PlanningOutcome.Ready, result.planningOutcome)
            assertTrue(result.planningTriggered)
            assertEquals(startingRevision + 1, result.task.revision)
            assertNull(result.task.selectedPlanId)
            assertEquals(RequirementValue.Location("Nanshan"), result.requirements.single { it.id == requirement.id }.value)
            assertEquals(RequirementStrength.Must, result.requirements.single { it.id == requirement.id }.strength)
            assertEquals(1, services.planComposer.contexts.size)
            assertEquals(result.task.revision, services.planComposer.contexts.single().taskRevision)
            assertEquals(listOf(result.task.revision), result.currentPlans().map { it.revision })
        }

    @Test
    fun `deleteRequirement removes requirement advances revision and replans`() =
        runBlocking {
            val initial = createLinkedPlanningTask()
            val services = createTaskServicesForMutation()
            val requirement = initial.requirements.single { it.kind == RequirementKind.Location }
            val startingRevision = initial.task.revision

            val result = services.taskService.deleteRequirement(
                actor = taskActor(),
                taskId = initial.task.id.value.toString(),
                requirementId = requirement.id.value.toString(),
            )

            assertEquals(PlanningOutcome.Ready, result.planningOutcome)
            assertTrue(result.planningTriggered)
            assertEquals(startingRevision + 1, result.task.revision)
            assertFalse(result.requirements.any { it.id == requirement.id })
            assertEquals(1, services.planComposer.contexts.size)
            assertEquals(result.task.revision, services.planComposer.contexts.single().taskRevision)
            assertEquals(listOf(result.task.revision), result.currentPlans().map { it.revision })
        }

    @Test
    fun `updateRequirement requires write scope before mutating`() =
        runBlocking {
            val initial = createLinkedPlanningTask()
            val services = createTaskServicesForMutation()
            val requirement = initial.requirements.first()

            assertFailsWith<MissingTaskScopeException> {
                services.taskService.updateRequirement(
                    actor = taskActor(scopes = setOf("orbit.tasks.read")),
                    taskId = initial.task.id.value.toString(),
                    requirementId = requirement.id.value.toString(),
                    kind = requirement.kind,
                    value = requirement.value,
                    strength = requirement.strength,
                )
            }
            Unit
        }

    @Test
    fun `invalid task or requirement ids are rejected before repository mutation`() =
        runBlocking {
            val initial = createLinkedPlanningTask()
            val services = createTaskServicesForMutation()
            val requirement = initial.requirements.first()

            assertFailsWith<InvalidTaskRequestException> {
                services.taskService.updateRequirement(
                    actor = taskActor(),
                    taskId = "not-a-task-id",
                    requirementId = requirement.id.value.toString(),
                    kind = requirement.kind,
                    value = requirement.value,
                    strength = requirement.strength,
                )
            }
            assertFailsWith<InvalidTaskRequestException> {
                services.taskService.deleteRequirement(
                    actor = taskActor(),
                    taskId = initial.task.id.value.toString(),
                    requirementId = "not-a-requirement-id",
                )
            }
            Unit
        }

    @Test
    fun `missing task or requirement maps to not found`() =
        runBlocking {
            val initial = createLinkedPlanningTask()
            val services = createTaskServicesForMutation()
            val requirement = initial.requirements.first()

            assertFailsWith<TaskNotFoundException> {
                services.taskService.updateRequirement(
                    actor = taskActor(),
                    taskId = TaskFlowIds.UnknownTask.toString(),
                    requirementId = requirement.id.value.toString(),
                    kind = requirement.kind,
                    value = requirement.value,
                    strength = requirement.strength,
                )
            }
            assertFailsWith<TaskNotFoundException> {
                services.taskService.deleteRequirement(
                    actor = taskActor(),
                    taskId = initial.task.id.value.toString(),
                    requirementId = "00000000-0000-0000-0000-000000000099",
                )
            }
            Unit
        }

    @Test
    fun `replanning unavailable keeps requirement mutation and returns unavailable outcome`() =
        runBlocking {
            val initial = createLinkedPlanningTask()
            val planningResearch = RecordingPlanningResearch().apply {
                researchFailure = planningUnavailable()
            }
            val services = createTaskServicesForMutation(planningResearch = planningResearch)
            val requirement = initial.requirements.single { it.kind == RequirementKind.Location }
            val startingRevision = initial.task.revision

            val result = services.taskService.updateRequirement(
                actor = taskActor(),
                taskId = initial.task.id.value.toString(),
                requirementId = requirement.id.value.toString(),
                kind = RequirementKind.Location,
                value = RequirementValue.Location("Nanshan"),
                strength = RequirementStrength.Must,
            )
            val persisted = services.repository.findTaskDetail(owner(), TaskId(result.task.id.value))

            assertEquals(PlanningOutcome.Unavailable, result.planningOutcome)
            assertTrue(result.planningTriggered)
            assertEquals(startingRevision + 1, result.task.revision)
            assertEquals(RequirementValue.Location("Nanshan"), result.requirements.single { it.id == requirement.id }.value)
            assertEquals(RequirementValue.Location("Nanshan"), persisted!!.requirements.single { it.id == requirement.id }.value)
            assertEquals(emptyList(), result.currentPlans())
            assertEquals(1, planningResearch.requests.size)
            assertEquals(result.task.revision, planningResearch.requests.single().taskRevision)
        }

    private suspend fun createLinkedPlanningTask(): TaskDetail {
        val services = createConversationServices(
            dataSource = dataSource,
            readToolCatalog = ReadToolCatalog(listOf(ControlledPlanningReadTool())),
            understanding = ScriptedUnderstanding({
                understandingOutcome(
                    changes = listOf(
                        activityDomainChange("movie", "movie"),
                        locationChange("Futian", "Futian"),
                    ),
                )
            }),
        )
        val created = services.conversationService.createConversation(
            actor = taskActor(),
            clientRequestId = "task-service-create",
            message = "Find a movie near Futian",
            timeZoneId = "Asia/Shanghai",
        )
        services.drainResponseRuns()
        return services.conversationService.getConversation(
            actor = taskActor(),
            conversationId = created.detail.conversation.id.value.toString(),
        ).currentTask!!
    }

    private fun createTaskServicesForMutation(
        planningResearch: RecordingPlanningResearch = RecordingPlanningResearch(),
    ) = createTaskServices(
        dataSource = dataSource,
        understanding = ScriptedUnderstanding({ understandingOutcome(changes = emptyList()) }),
        planningResearch = planningResearch,
        readToolCatalog = ReadToolCatalog(listOf(ControlledPlanningReadTool())),
        planIds = UuidSequence(800),
    )

    private fun TaskDetail.currentPlans() = plans.filter { it.revision == task.revision }

    private fun TaskMutationResult.currentPlans() = plans.filter { it.revision == task.revision }

    private fun owner(): TaskOwner =
        TaskOwner(TenantId(TaskFlowIds.TenantOne), UserId(TaskFlowIds.UserOne))
}
