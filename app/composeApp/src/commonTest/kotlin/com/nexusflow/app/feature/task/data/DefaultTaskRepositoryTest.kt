package com.nexusflow.app.feature.task.data

import com.nexusflow.app.core.network.ApiCallExecutor
import com.nexusflow.app.core.observability.AppLogger
import com.nexusflow.app.core.observability.LogFields
import com.nexusflow.app.core.observability.LogLevel
import com.nexusflow.app.core.observability.LogTag
import com.nexusflow.app.feature.task.domain.CreateTaskCommand
import com.nexusflow.app.feature.task.domain.PlanId
import com.nexusflow.app.feature.task.domain.RequirementKind
import com.nexusflow.app.feature.task.domain.RequirementStrength
import com.nexusflow.app.feature.task.domain.RequirementValue
import com.nexusflow.app.feature.task.domain.SelectPlanCommand
import com.nexusflow.app.feature.task.domain.TaskId
import com.nexusflow.app.feature.task.domain.UpdateRequirementCommand
import com.nexusflow.contracts.api.CreateTaskRequest
import com.nexusflow.contracts.api.KResponse
import com.nexusflow.contracts.api.MessageRole
import com.nexusflow.contracts.api.PlanDirection
import com.nexusflow.contracts.api.PlanEstimatedCostResponse
import com.nexusflow.contracts.api.PlanResponse
import com.nexusflow.contracts.api.PlanSourceRefResponse
import com.nexusflow.contracts.api.PlanTimelineItemResponse
import com.nexusflow.contracts.api.PlanningStatus
import com.nexusflow.contracts.api.PlanningStatusResponse
import com.nexusflow.contracts.api.RequirementEvaluationResponse
import com.nexusflow.contracts.api.RequirementEvaluationResult
import com.nexusflow.contracts.api.RequirementResponse
import com.nexusflow.contracts.api.RequirementSource
import com.nexusflow.contracts.api.RequirementSummaryResponse
import com.nexusflow.contracts.api.RequirementValueResponse
import com.nexusflow.contracts.api.SendTaskMessageRequest
import com.nexusflow.contracts.api.TaskDetailResponse
import com.nexusflow.contracts.api.TaskMessageResponse
import com.nexusflow.contracts.api.TaskResponse
import com.nexusflow.contracts.api.TaskSummaryResponse
import com.nexusflow.contracts.api.UpdateRequirementRequest
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import com.nexusflow.contracts.api.RequirementKind as WireRequirementKind
import com.nexusflow.contracts.api.RequirementStrength as WireRequirementStrength

class DefaultTaskRepositoryTest {
    @Test
    fun `loads summaries and detail through requirement contracts`() =
        runTest {
            val api =
                RecordingTaskApi(
                    listResponses = listOf(KResponse(code = 200, data = listOf(summaryResponse()))),
                    detailResponses = listOf(KResponse(code = 200, data = detailResponse())),
                )
            val repository = repository(api)

            val summaries = repository.loadTaskSummaries().getOrThrow()
            val detail = repository.loadTaskDetail(TaskId("task-1")).getOrThrow()

            assertEquals("Plan a match night", summaries.single().intent)
            assertEquals(RequirementStrength.Must, summaries.single().requirements.single().strength)
            assertEquals(2, detail.revision)
            assertEquals("Liverpool", (detail.requirements.single().value as RequirementValue.Text).value)
            assertEquals(PlanId("plan-1"), detail.plans.single().id)
        }

    @Test
    fun `create task sends message based request`() =
        runTest {
            val api = RecordingTaskApi(createResponses = listOf(KResponse(code = 200, data = detailResponse())))
            val repository = repository(api)

            repository.createTask(
                CreateTaskCommand(
                    creationRequestId = "create-1",
                    requestText = "Plan Saturday",
                    timeZoneId = "Asia/Shanghai",
                ),
            ).getOrThrow()

            assertEquals(listOf("create:create-1"), api.calls)
            assertEquals(CreateTaskRequest("create-1", "Plan Saturday", "Asia/Shanghai"), api.createRequests.single())
        }

    @Test
    fun `updates requirement with typed wire value`() =
        runTest {
            val api = RecordingTaskApi(updateResponses = listOf(KResponse(code = 200, data = detailResponse())))
            val repository = repository(api)

            repository.updateRequirement(
                UpdateRequirementCommand(
                    taskId = TaskId("task-1"),
                    requirementId = com.nexusflow.app.feature.task.domain.RequirementId("requirement-1"),
                    kind = RequirementKind.Topic,
                    value = RequirementValue.Text("Liverpool"),
                    strength = RequirementStrength.Prefer,
                ),
            ).getOrThrow()

            val request = api.updateRequests.single()
            assertEquals(WireRequirementKind.Topic, request.kind)
            assertEquals(WireRequirementStrength.Prefer, request.strength)
            assertIs<RequirementValueResponse.Topic>(request.value)
        }

    @Test
    fun `select plan uses task and plan path parameters`() =
        runTest {
            val api = RecordingTaskApi(selectResponses = listOf(KResponse(code = 200, data = detailResponse(selectedPlanId = "plan-1"))))
            val repository = repository(api)

            val detail = repository.selectPlan(SelectPlanCommand(TaskId("task-1"), PlanId("plan-1"))).getOrThrow()

            assertEquals(listOf("select:task-1:plan-1"), api.calls)
            assertEquals(PlanId("plan-1"), detail.selectedPlanId)
        }
}

private fun repository(api: RecordingTaskApi): DefaultTaskRepository =
    DefaultTaskRepository(TaskRemoteDataSource(api, ApiCallExecutor(RecordingLogger())))

private class RecordingTaskApi(
    listResponses: List<KResponse<List<TaskSummaryResponse>>> = emptyList(),
    detailResponses: List<KResponse<TaskDetailResponse>> = emptyList(),
    createResponses: List<KResponse<TaskDetailResponse>> = emptyList(),
    sendResponses: List<KResponse<TaskDetailResponse>> = emptyList(),
    updateResponses: List<KResponse<TaskDetailResponse>> = emptyList(),
    removeResponses: List<KResponse<TaskDetailResponse>> = emptyList(),
    selectResponses: List<KResponse<TaskDetailResponse>> = emptyList(),
) : TaskApi {
    val calls = mutableListOf<String>()
    val createRequests = mutableListOf<CreateTaskRequest>()
    val updateRequests = mutableListOf<UpdateRequirementRequest>()
    private val listResponses = ArrayDeque(listResponses)
    private val detailResponses = ArrayDeque(detailResponses)
    private val createResponses = ArrayDeque(createResponses)
    private val sendResponses = ArrayDeque(sendResponses)
    private val updateResponses = ArrayDeque(updateResponses)
    private val removeResponses = ArrayDeque(removeResponses)
    private val selectResponses = ArrayDeque(selectResponses)

    override suspend fun createTask(request: CreateTaskRequest): KResponse<TaskDetailResponse> {
        calls += "create:${request.clientRequestId}"
        createRequests += request
        return createResponses.removeFirst()
    }

    override suspend fun listTasks(): KResponse<List<TaskSummaryResponse>> {
        calls += "list"
        return listResponses.removeFirst()
    }

    override suspend fun getTask(taskId: String): KResponse<TaskDetailResponse> {
        calls += "detail:$taskId"
        return detailResponses.removeFirst()
    }

    override suspend fun sendMessage(
        taskId: String,
        request: SendTaskMessageRequest,
    ): KResponse<TaskDetailResponse> {
        calls += "send:$taskId:${request.clientMessageId}"
        return sendResponses.removeFirst()
    }

    override suspend fun updateRequirement(
        taskId: String,
        requirementId: String,
        request: UpdateRequirementRequest,
    ): KResponse<TaskDetailResponse> {
        calls += "update:$taskId:$requirementId"
        updateRequests += request
        return updateResponses.removeFirst()
    }

    override suspend fun removeRequirement(
        taskId: String,
        requirementId: String,
    ): KResponse<TaskDetailResponse> {
        calls += "remove:$taskId:$requirementId"
        return removeResponses.removeFirst()
    }

    override suspend fun selectPlan(
        taskId: String,
        planId: String,
    ): KResponse<TaskDetailResponse> {
        calls += "select:$taskId:$planId"
        return selectResponses.removeFirst()
    }
}

private class RecordingLogger : AppLogger {
    override fun log(
        level: LogLevel,
        tag: LogTag,
        event: String,
        fields: LogFields,
        cause: Throwable?,
    ) = Unit
}

private fun summaryResponse(): TaskSummaryResponse =
    TaskSummaryResponse(
        id = "task-1",
        intent = "Plan a match night",
        requirements = listOf(RequirementSummaryResponse("requirement-1", "Liverpool", WireRequirementStrength.Must)),
        selectedPlanId = null,
        updatedAt = Now,
    )

private fun detailResponse(selectedPlanId: String? = null): TaskDetailResponse =
    TaskDetailResponse(
        task =
            TaskResponse(
                id = "task-1",
                intent = "Plan a match night",
                revision = 2,
                selectedPlanId = selectedPlanId,
                createdAt = Now,
                updatedAt = Now,
            ),
        requirements =
            listOf(
                RequirementResponse(
                    id = "requirement-1",
                    kind = WireRequirementKind.Topic,
                    value = RequirementValueResponse.Topic("Liverpool"),
                    strength = WireRequirementStrength.Must,
                    source = RequirementSource.UserExplicit,
                    evidenceMessageId = "message-1",
                    createdAt = Now,
                    updatedAt = Now,
                ),
            ),
        messages =
            listOf(
                TaskMessageResponse(
                    id = "message-1",
                    role = MessageRole.User,
                    content = "Watch Liverpool",
                    clientMessageId = "client-message-1",
                    createdAt = Now,
                ),
            ),
        plans = listOf(planResponse()),
        planning = PlanningStatusResponse(PlanningStatus.Idle),
    )

private fun planResponse(): PlanResponse =
    PlanResponse(
        id = "plan-1",
        taskId = "task-1",
        revision = 2,
        direction = PlanDirection.BestMatch,
        title = "Match night",
        summary = "Watch Liverpool nearby.",
        timeline =
            listOf(
                PlanTimelineItemResponse(
                    title = "Screening",
                    startAt = Now,
                    endAt = Now,
                    location = "Futian",
                ),
            ),
        estimatedCost = PlanEstimatedCostResponse(180, "CNY"),
        commuteMinutes = 18,
        requirementEvaluations =
            listOf(RequirementEvaluationResponse("requirement-1", RequirementEvaluationResult.Satisfied)),
        tradeoffs = emptyList(),
        reasons = listOf("Matches the topic"),
        sourceRefs = listOf(PlanSourceRefResponse("Controlled Sports Feed", "controlled://sports", Now)),
        opportunityRefs = listOf("opportunity-1"),
        validUntil = Now,
        createdAt = Now,
    )

private val Now = Instant.parse("2026-08-29T10:00:00Z")
