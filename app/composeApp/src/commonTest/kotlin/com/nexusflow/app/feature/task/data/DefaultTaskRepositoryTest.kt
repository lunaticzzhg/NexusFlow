package com.nexusflow.app.feature.task.data

import com.nexusflow.app.core.error.AppException
import com.nexusflow.app.core.network.ApiCallExecutor
import com.nexusflow.app.core.observability.AppLogger
import com.nexusflow.app.core.observability.LogFields
import com.nexusflow.app.core.observability.LogLevel
import com.nexusflow.app.core.observability.LogTag
import com.nexusflow.app.feature.task.domain.ConstraintValue
import com.nexusflow.app.feature.task.domain.CreateTaskCommand
import com.nexusflow.app.feature.task.domain.GeneratePlansCommand
import com.nexusflow.app.feature.task.domain.PlanId
import com.nexusflow.app.feature.task.domain.SelectPlanCommand
import com.nexusflow.app.feature.task.domain.TaskId
import com.nexusflow.app.feature.task.domain.TaskState
import com.nexusflow.contracts.api.ConstraintKind
import com.nexusflow.contracts.api.ConstraintResponse
import com.nexusflow.contracts.api.ConstraintSource
import com.nexusflow.contracts.api.ConstraintStrength
import com.nexusflow.contracts.api.ConstraintValueResponse
import com.nexusflow.contracts.api.ConversationMessageResponse
import com.nexusflow.contracts.api.CreateTaskRequest
import com.nexusflow.contracts.api.GeneratePlansRequest
import com.nexusflow.contracts.api.GeneratePlansResponse
import com.nexusflow.contracts.api.KResponse
import com.nexusflow.contracts.api.MessageRole
import com.nexusflow.contracts.api.PlanEstimatedCostResponse
import com.nexusflow.contracts.api.PlanResponse
import com.nexusflow.contracts.api.PlanSourceRefResponse
import com.nexusflow.contracts.api.PlanTimelineItemResponse
import com.nexusflow.contracts.api.SelectPlanRequest
import com.nexusflow.contracts.api.SendTaskMessageRequest
import com.nexusflow.contracts.api.TaskDetailResponse
import com.nexusflow.contracts.api.TaskSummaryResponse
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import com.nexusflow.contracts.api.TaskState as WireTaskState

class DefaultTaskRepositoryTest {
    @Test
    fun `loads summaries and detail through contract to domain mapping`() =
        runTest {
            val api =
                RecordingTaskApi(
                    listResponses =
                        listOf(
                            KResponse(
                                code = 200,
                                data =
                                    listOf(
                                        TaskSummaryResponse(
                                            id = "task-1",
                                            title = "Liverpool night",
                                            currentGoal = "Plan a match night",
                                            state = WireTaskState.Planning,
                                            updatedAt = Now,
                                        ),
                                    ),
                            ),
                        ),
                    detailResponses = listOf(KResponse(code = 200, data = detailResponse())),
                )
            val repository = repository(api)

            val summaries = repository.loadTaskSummaries().getOrThrow()
            val detail = repository.loadTaskDetail(TaskId("task-1")).getOrThrow()

            assertEquals(TaskState.Planning, summaries.single().state)
            assertEquals("Plan a match night", detail.currentGoal)
            assertEquals(1, detail.version)
            assertEquals(TaskState.Planning, detail.state)
            assertEquals("Saturday evening", (detail.constraints.single().value as ConstraintValue.TimeWindow).originalText)
            assertEquals("Please keep it under 300", detail.messages.single().content)
            assertEquals("Anfield walk", detail.plans.single().timeline.single().title)
            assertEquals(120, detail.plans.single().estimatedCost?.wholeUnits)
        }

    @Test
    fun `create task sends first user message with separate stable identities and returns final detail`() =
        runTest {
            val api =
                RecordingTaskApi(
                    createResponses =
                        listOf(
                            KResponse(
                                code = 200,
                                data = detailResponse(version = 1, currentGoal = "Pre-message goal"),
                            ),
                            KResponse(
                                code = 200,
                                data = detailResponse(version = 1, currentGoal = "Pre-message goal"),
                            ),
                        ),
                    sendResponses =
                        listOf(
                            KResponse(code = 503, message = "Task understanding is temporarily unavailable"),
                            KResponse(
                                code = 200,
                                data =
                                    detailResponse(
                                        version = 2,
                                        currentGoal = "Final message goal",
                                        messageClientId = "message-1",
                                    ),
                            ),
                        ),
                )
            val repository = repository(api)
            val command =
                CreateTaskCommand(
                    creationRequestId = "create-1",
                    initialMessageId = "message-1",
                    requestText = "Plan a match night",
                    timeZoneId = "Asia/Shanghai",
                )

            val first = repository.createTask(command)
            val second = repository.createTask(command).getOrThrow()

            assertIs<AppException.Unavailable>(first.exceptionOrNull())
            assertEquals("task-1", second.id.value)
            assertEquals(2, second.version)
            assertEquals("Final message goal", second.currentGoal)
            assertEquals(
                listOf("create:create-1", "send:message-1", "create:create-1", "send:message-1"),
                api.calls,
            )
            assertTrue(command.creationRequestId != command.initialMessageId)
            assertEquals(listOf("Plan a match night", "Plan a match night"), api.createRequests.map { it.goal })
            assertEquals(listOf("Plan a match night", "Plan a match night"), api.sendRequests.map { it.text })
            assertEquals(listOf("Asia/Shanghai", "Asia/Shanghai"), api.sendRequests.map { it.timeZoneId })
        }

    @Test
    fun `generates and selects plans without leaking task IDs into logged endpoint paths`() =
        runTest {
            val api =
                RecordingTaskApi(
                    generateResponses =
                        listOf(
                            KResponse(
                                code = 200,
                                data = GeneratePlansResponse(planningRunId = "run-1", plans = listOf(planResponse())),
                            ),
                        ),
                    selectResponses =
                        listOf(
                            KResponse(
                                code = 200,
                                data = detailResponse(selectedPlanId = "plan-1", state = WireTaskState.WaitingForApproval),
                            ),
                        ),
                )
            val logger = RecordingLogger()
            val repository = repository(api, logger)

            val plans = repository.generatePlans(GeneratePlansCommand(TaskId("task-1"), "run-1")).getOrThrow()
            val selected = repository.selectPlan(SelectPlanCommand(TaskId("task-1"), PlanId("plan-1"))).getOrThrow()

            assertEquals(listOf("generate:run-1", "select:plan-1"), api.calls)
            assertEquals("Fixture route", plans.single().title)
            assertEquals(PlanId("plan-1"), selected.selectedPlanId)
            assertTrue(logger.entries.none { entry -> entry.fields.values.any { it.contains("task-1") } })
        }
}

private fun repository(
    api: RecordingTaskApi,
    logger: AppLogger = RecordingLogger(),
): DefaultTaskRepository =
    DefaultTaskRepository(
        remoteDataSource = TaskRemoteDataSource(api, ApiCallExecutor(logger)),
    )

private class RecordingTaskApi(
    listResponses: List<KResponse<List<TaskSummaryResponse>>> = emptyList(),
    detailResponses: List<KResponse<TaskDetailResponse>> = emptyList(),
    createResponses: List<KResponse<TaskDetailResponse>> = emptyList(),
    sendResponses: List<KResponse<TaskDetailResponse>> = emptyList(),
    generateResponses: List<KResponse<GeneratePlansResponse>> = emptyList(),
    selectResponses: List<KResponse<TaskDetailResponse>> = emptyList(),
) : TaskApi {
    val calls = mutableListOf<String>()
    val createRequests = mutableListOf<CreateTaskRequest>()
    val sendRequests = mutableListOf<SendTaskMessageRequest>()
    private val listResponses = ArrayDeque(listResponses)
    private val detailResponses = ArrayDeque(detailResponses)
    private val createResponses = ArrayDeque(createResponses)
    private val sendResponses = ArrayDeque(sendResponses)
    private val generateResponses = ArrayDeque(generateResponses)
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
        calls += "send:${request.clientMessageId}"
        sendRequests += request
        return sendResponses.removeFirst()
    }

    override suspend fun generatePlans(
        taskId: String,
        request: GeneratePlansRequest,
    ): KResponse<GeneratePlansResponse> {
        calls += "generate:${request.clientRequestId}"
        return generateResponses.removeFirst()
    }

    override suspend fun selectPlan(
        taskId: String,
        request: SelectPlanRequest,
    ): KResponse<TaskDetailResponse> {
        calls += "select:${request.planId}"
        return selectResponses.removeFirst()
    }
}

private class RecordingLogger : AppLogger {
    val entries = mutableListOf<Entry>()

    override fun log(
        level: LogLevel,
        tag: LogTag,
        event: String,
        fields: LogFields,
        cause: Throwable?,
    ) {
        entries += Entry(fields.values)
    }

    data class Entry(
        val fields: Map<String, String>,
    )
}

private fun detailResponse(
    selectedPlanId: String? = null,
    state: WireTaskState = WireTaskState.Planning,
    version: Long = 1,
    currentGoal: String = "Plan a match night",
    messageClientId: String = "create-1",
): TaskDetailResponse =
    TaskDetailResponse(
        id = "task-1",
        title = "Liverpool night",
        currentGoal = currentGoal,
        state = state,
        version = version,
        constraints =
            listOf(
                ConstraintResponse(
                    id = "constraint-1",
                    kind = ConstraintKind.TimeWindow,
                    value =
                        ConstraintValueResponse.TimeWindow(
                            startAt = null,
                            endAt = null,
                            timeZoneId = "Asia/Shanghai",
                            originalText = "Saturday evening",
                        ),
                    strength = ConstraintStrength.Hard,
                    source = ConstraintSource.UserExplicit,
                    evidenceMessageId = "message-1",
                    confirmedAt = Now,
                    createdAt = Now,
                    updatedAt = Now,
                ),
            ),
        messages =
            listOf(
                ConversationMessageResponse(
                    id = "message-1",
                    role = MessageRole.User,
                    content = "Please keep it under 300",
                    clientMessageId = messageClientId,
                    aiRequestId = "ai-1",
                    understoodAt = Now,
                    createdAt = Now,
                ),
            ),
        plans = listOf(planResponse()),
        selectedPlanId = selectedPlanId,
        createdAt = Now,
        updatedAt = Now,
    )

private fun planResponse(): PlanResponse =
    PlanResponse(
        id = "plan-1",
        taskId = "task-1",
        planningRunId = "run-1",
        direction = "fixture",
        title = "Fixture route",
        summary = "Walk, dinner, then match.",
        timeline =
            listOf(
                PlanTimelineItemResponse(
                    title = "Anfield walk",
                    startAt = null,
                    endAt = null,
                    location = "Anfield",
                ),
            ),
        estimatedCost = PlanEstimatedCostResponse(wholeUnits = 120, currencyCode = "GBP"),
        commuteMinutes = 20,
        satisfiedConstraintIds = listOf("constraint-1"),
        tradeoffs = listOf("Weather dependent"),
        reasons = listOf("Fits the requested time window"),
        sourceRefs = listOf(PlanSourceRefResponse(label = "Fixture", uri = null)),
        validUntil = null,
        createdAt = Now,
    )

private val Now = Instant.parse("2026-08-28T10:15:00Z")
