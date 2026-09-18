package com.nexusflow.app.feature.task.data

import com.nexusflow.app.core.network.ApiCallExecutor
import com.nexusflow.app.core.observability.AppLogger
import com.nexusflow.app.core.observability.LogFields
import com.nexusflow.app.core.observability.LogLevel
import com.nexusflow.app.core.observability.LogTag
import com.nexusflow.app.feature.task.domain.ConversationId
import com.nexusflow.app.feature.task.domain.CreateConversationCommand
import com.nexusflow.app.feature.task.domain.PlanId
import com.nexusflow.app.feature.task.domain.PlanningState
import com.nexusflow.app.feature.task.domain.RequirementKind
import com.nexusflow.app.feature.task.domain.RequirementStrength
import com.nexusflow.app.feature.task.domain.RequirementValue
import com.nexusflow.app.feature.task.domain.ResponseRunId
import com.nexusflow.app.feature.task.domain.ResponseRunStatus
import com.nexusflow.app.feature.task.domain.SelectPlanCommand
import com.nexusflow.app.feature.task.domain.SendConversationMessageCommand
import com.nexusflow.app.feature.task.domain.TaskId
import com.nexusflow.app.feature.task.domain.UpdateRequirementCommand
import com.nexusflow.contracts.appbackend.common.KResponse
import com.nexusflow.contracts.appbackend.conversation.ConversationCurrentTaskResponse
import com.nexusflow.contracts.appbackend.conversation.ConversationDetailResponse
import com.nexusflow.contracts.appbackend.conversation.ConversationMessageResponse
import com.nexusflow.contracts.appbackend.conversation.ConversationResponse
import com.nexusflow.contracts.appbackend.conversation.CreateConversationRequest
import com.nexusflow.contracts.appbackend.conversation.CreateConversationResponse
import com.nexusflow.contracts.appbackend.conversation.ResponseRunResponse
import com.nexusflow.contracts.appbackend.conversation.ResponseRunSnapshotResponse
import com.nexusflow.contracts.appbackend.conversation.ResponseRunStatusResponse
import com.nexusflow.contracts.appbackend.conversation.SendConversationMessageRequest
import com.nexusflow.contracts.appbackend.conversation.SendConversationMessageResponse
import com.nexusflow.contracts.appbackend.plan.PlanDirection
import com.nexusflow.contracts.appbackend.plan.PlanEstimatedCostResponse
import com.nexusflow.contracts.appbackend.plan.PlanResponse
import com.nexusflow.contracts.appbackend.plan.PlanSourceRefResponse
import com.nexusflow.contracts.appbackend.plan.PlanTimelineItemResponse
import com.nexusflow.contracts.appbackend.plan.RequirementEvaluationResponse
import com.nexusflow.contracts.appbackend.plan.RequirementEvaluationResult
import com.nexusflow.contracts.appbackend.task.MessageRole
import com.nexusflow.contracts.appbackend.task.PlanningStatus
import com.nexusflow.contracts.appbackend.task.PlanningStatusResponse
import com.nexusflow.contracts.appbackend.task.RequirementResponse
import com.nexusflow.contracts.appbackend.task.RequirementSource
import com.nexusflow.contracts.appbackend.task.RequirementSummaryResponse
import com.nexusflow.contracts.appbackend.task.RequirementValueResponse
import com.nexusflow.contracts.appbackend.task.TaskDetailResponse
import com.nexusflow.contracts.appbackend.task.TaskResponse
import com.nexusflow.contracts.appbackend.task.TaskSummaryResponse
import com.nexusflow.contracts.appbackend.task.UpdateRequirementRequest
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import com.nexusflow.contracts.appbackend.task.RequirementKind as WireRequirementKind
import com.nexusflow.contracts.appbackend.task.RequirementStrength as WireRequirementStrength

class DefaultTaskRepositoryTest {
    @Test
    fun `loads summaries and detail through requirement contracts`() =
        runTest {
            val api =
                RecordingTaskApi(
                    listResponses = listOf(KResponse(code = 200, data = listOf(summaryResponse()))),
                    detailResponses = listOf(KResponse(code = 200, data = detailResponse(planningStatus = PlanningStatus.NoCandidates))),
                )
            val repository = repository(api)

            val summaries = repository.loadTaskSummaries().getOrThrow()
            val detail = repository.loadTaskDetail(TaskId("task-1")).getOrThrow()

            assertEquals("Plan a match night", summaries.single().intent)
            assertEquals(ConversationId("conversation-1"), summaries.single().conversationId)
            assertEquals(RequirementStrength.Must, summaries.single().requirements.single().strength)
            assertEquals(2, detail.revision)
            assertEquals("Liverpool", (detail.requirements.single().value as RequirementValue.Text).value)
            assertEquals(PlanId("plan-1"), detail.plans.single().id)
            assertEquals(PlanningState.NoCandidates, detail.planningState)
            assertEquals(emptyList(), detail.messages)
        }

    @Test
    fun `create conversation sends message based request`() =
        runTest {
            val api =
                RecordingTaskApi(
                    createConversationResponses = listOf(KResponse(code = 200, data = createConversationResponse(currentTask = null))),
                )
            val repository = repository(api)

            val detail =
                repository.createConversation(
                    CreateConversationCommand(
                        creationRequestId = "create-1",
                        requestText = "Plan Saturday",
                        timeZoneId = "Asia/Shanghai",
                    ),
                ).getOrThrow()

            assertEquals(ConversationId("conversation-1"), detail.id)
            assertEquals(null, detail.currentTask)
            assertEquals(listOf("create-conversation:create-1"), api.calls)
            assertEquals(CreateConversationRequest("create-1", "Plan Saturday", "Asia/Shanghai"), api.createConversationRequests.single())
        }

    @Test
    fun `loads pure conversation without current task`() =
        runTest {
            val api =
                RecordingTaskApi(
                    conversationResponses = listOf(KResponse(code = 200, data = conversationDetailResponse(currentTask = null))),
                )
            val repository = repository(api)

            val detail = repository.loadConversationDetail(ConversationId("conversation-1")).getOrThrow()

            assertEquals(listOf("conversation:conversation-1"), api.calls)
            assertEquals(ConversationId("conversation-1"), detail.id)
            assertEquals("message-1", detail.messages.single().id)
            assertEquals(null, detail.messages.single().turnIndex)
            assertEquals(emptyList(), detail.responseRuns)
            assertEquals(null, detail.currentTask)
        }

    @Test
    fun `send conversation message maps updated current task`() =
        runTest {
            val api =
                RecordingTaskApi(
                    sendConversationResponses =
                        listOf(
                            KResponse(code = 200, data = sendConversationMessageResponse(currentTask = currentTaskResponse())),
                        ),
                )
            val repository = repository(api)

            val detail =
                repository.sendConversationMessage(
                    SendConversationMessageCommand(
                        conversationId = ConversationId("conversation-1"),
                        clientMessageId = "client-message-2",
                        text = "Budget 500",
                        timeZoneId = "Asia/Shanghai",
                    ),
                ).getOrThrow()

            assertEquals(listOf("send-conversation:conversation-1:client-message-2"), api.calls)
            assertEquals(
                SendConversationMessageRequest("client-message-2", "Budget 500", "Asia/Shanghai"),
                api.sendConversationRequests.single(),
            )
            assertEquals(TaskId("task-1"), detail.currentTask?.id)
            assertEquals(PlanId("plan-1"), detail.currentTask?.plans?.single()?.id)
        }

    @Test
    fun `maps response runs and response run HTTP endpoints`() =
        runTest {
            val run = responseRunResponse(status = ResponseRunStatusResponse.Streaming)
            val api =
                RecordingTaskApi(
                    conversationResponses =
                        listOf(
                            KResponse(code = 200, data = conversationDetailResponse(responseRuns = listOf(run))),
                        ),
                    snapshotResponses =
                        listOf(
                            KResponse(
                                code = 200,
                                data =
                                    ResponseRunSnapshotResponse(
                                        run = run,
                                        conversation = conversationResponse(responseRuns = listOf(run)),
                                        currentTask = currentTaskResponse(),
                                        streamAttempt = 2,
                                        lastSeq = 12,
                                        partialText = "partial",
                                        realtimeSnapshotAvailable = true,
                                    ),
                            ),
                        ),
                    cancelResponses =
                        listOf(
                            KResponse(code = 200, data = responseRunResponse(status = ResponseRunStatusResponse.Cancelled)),
                        ),
                    retryResponses =
                        listOf(
                            KResponse(code = 200, data = responseRunResponse(status = ResponseRunStatusResponse.Queued)),
                        ),
                )
            val repository = repository(api)

            val detail = repository.loadConversationDetail(ConversationId("conversation-1")).getOrThrow()
            val snapshot =
                repository.loadResponseRunSnapshot(
                    conversationId = ConversationId("conversation-1"),
                    responseRunId = ResponseRunId("run-1"),
                ).getOrThrow()
            val cancelled = repository.cancelResponseRun(ConversationId("conversation-1"), ResponseRunId("run-1")).getOrThrow()
            val retried = repository.retryResponseRun(ConversationId("conversation-1"), ResponseRunId("run-1")).getOrThrow()

            assertEquals(ResponseRunStatus.Streaming, detail.responseRuns.single().status)
            assertEquals(1, detail.messages.single().turnIndex)
            assertEquals("partial", snapshot.partialText)
            assertEquals(ResponseRunStatus.Cancelled, cancelled.status)
            assertEquals(ResponseRunStatus.Queued, retried.status)
            assertEquals(
                listOf(
                    "conversation:conversation-1",
                    "response-run-snapshot:conversation-1:run-1",
                    "response-run-cancel:conversation-1:run-1",
                    "response-run-retry:conversation-1:run-1",
                ),
                api.calls,
            )
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
    createConversationResponses: List<KResponse<CreateConversationResponse>> = emptyList(),
    conversationResponses: List<KResponse<ConversationDetailResponse>> = emptyList(),
    sendConversationResponses: List<KResponse<SendConversationMessageResponse>> = emptyList(),
    listResponses: List<KResponse<List<TaskSummaryResponse>>> = emptyList(),
    detailResponses: List<KResponse<TaskDetailResponse>> = emptyList(),
    snapshotResponses: List<KResponse<ResponseRunSnapshotResponse>> = emptyList(),
    cancelResponses: List<KResponse<ResponseRunResponse>> = emptyList(),
    retryResponses: List<KResponse<ResponseRunResponse>> = emptyList(),
    updateResponses: List<KResponse<TaskDetailResponse>> = emptyList(),
    removeResponses: List<KResponse<TaskDetailResponse>> = emptyList(),
    selectResponses: List<KResponse<TaskDetailResponse>> = emptyList(),
) : TaskApi {
    val calls = mutableListOf<String>()
    val createConversationRequests = mutableListOf<CreateConversationRequest>()
    val sendConversationRequests = mutableListOf<SendConversationMessageRequest>()
    val updateRequests = mutableListOf<UpdateRequirementRequest>()
    private val createConversationResponses = ArrayDeque(createConversationResponses)
    private val conversationResponses = ArrayDeque(conversationResponses)
    private val sendConversationResponses = ArrayDeque(sendConversationResponses)
    private val listResponses = ArrayDeque(listResponses)
    private val detailResponses = ArrayDeque(detailResponses)
    private val snapshotResponses = ArrayDeque(snapshotResponses)
    private val cancelResponses = ArrayDeque(cancelResponses)
    private val retryResponses = ArrayDeque(retryResponses)
    private val updateResponses = ArrayDeque(updateResponses)
    private val removeResponses = ArrayDeque(removeResponses)
    private val selectResponses = ArrayDeque(selectResponses)

    override suspend fun createConversation(request: CreateConversationRequest): KResponse<CreateConversationResponse> {
        calls += "create-conversation:${request.clientRequestId}"
        createConversationRequests += request
        return createConversationResponses.removeFirst()
    }

    override suspend fun listTasks(): KResponse<List<TaskSummaryResponse>> {
        calls += "list"
        return listResponses.removeFirst()
    }

    override suspend fun getTask(taskId: String): KResponse<TaskDetailResponse> {
        calls += "detail:$taskId"
        return detailResponses.removeFirst()
    }

    override suspend fun getConversation(conversationId: String): KResponse<ConversationDetailResponse> {
        calls += "conversation:$conversationId"
        return conversationResponses.removeFirst()
    }

    override suspend fun sendConversationMessage(
        conversationId: String,
        request: SendConversationMessageRequest,
    ): KResponse<SendConversationMessageResponse> {
        calls += "send-conversation:$conversationId:${request.clientMessageId}"
        sendConversationRequests += request
        return sendConversationResponses.removeFirst()
    }

    override suspend fun getResponseRunSnapshot(
        conversationId: String,
        responseRunId: String,
    ): KResponse<ResponseRunSnapshotResponse> {
        calls += "response-run-snapshot:$conversationId:$responseRunId"
        return snapshotResponses.removeFirst()
    }

    override suspend fun cancelResponseRun(
        conversationId: String,
        responseRunId: String,
    ): KResponse<ResponseRunResponse> {
        calls += "response-run-cancel:$conversationId:$responseRunId"
        return cancelResponses.removeFirst()
    }

    override suspend fun retryResponseRun(
        conversationId: String,
        responseRunId: String,
    ): KResponse<ResponseRunResponse> {
        calls += "response-run-retry:$conversationId:$responseRunId"
        return retryResponses.removeFirst()
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
        conversationId = "conversation-1",
    )

private fun createConversationResponse(currentTask: ConversationCurrentTaskResponse? = currentTaskResponse()): CreateConversationResponse =
    CreateConversationResponse(
        conversation = conversationResponse(),
        currentTask = currentTask,
    )

private fun conversationDetailResponse(
    currentTask: ConversationCurrentTaskResponse? = currentTaskResponse(),
    responseRuns: List<ResponseRunResponse> = emptyList(),
): ConversationDetailResponse =
    ConversationDetailResponse(
        conversation = conversationResponse(responseRuns = responseRuns),
        currentTask = currentTask,
    )

private fun sendConversationMessageResponse(
    currentTask: ConversationCurrentTaskResponse? = currentTaskResponse(),
): SendConversationMessageResponse =
    SendConversationMessageResponse(
        conversation = conversationResponse(),
        currentTask = currentTask,
    )

private fun conversationResponse(responseRuns: List<ResponseRunResponse> = emptyList()): ConversationResponse =
    ConversationResponse(
        id = "conversation-1",
        messages =
            listOf(
                ConversationMessageResponse(
                    id = "message-1",
                    role = MessageRole.User,
                    content = "Watch Liverpool",
                    clientMessageId = "client-message-1",
                    turnIndex = responseRuns.firstOrNull()?.turnIndex,
                    createdAt = Now,
                ),
            ),
        responseRuns = responseRuns,
        createdAt = Now,
        updatedAt = Now,
    )

private fun responseRunResponse(status: ResponseRunStatusResponse): ResponseRunResponse =
    ResponseRunResponse(
        id = "run-1",
        userMessageId = "message-1",
        turnIndex = 1,
        status = status,
        attempt = 1,
        retryable = status == ResponseRunStatusResponse.FailedRetryable,
        createdAt = Now,
        updatedAt = Now,
        completedAt = if (status == ResponseRunStatusResponse.Completed) Now else null,
    )

private fun currentTaskResponse(
    selectedPlanId: String? = null,
    planningStatus: PlanningStatus = PlanningStatus.Idle,
): ConversationCurrentTaskResponse =
    ConversationCurrentTaskResponse(
        task = taskResponse(selectedPlanId = selectedPlanId),
        requirements = requirementResponses(),
        plans = listOf(planResponse()),
        planning = PlanningStatusResponse(planningStatus),
    )

private fun detailResponse(
    selectedPlanId: String? = null,
    planningStatus: PlanningStatus = PlanningStatus.Idle,
): TaskDetailResponse =
    TaskDetailResponse(
        task = taskResponse(selectedPlanId = selectedPlanId),
        requirements = requirementResponses(),
        plans = listOf(planResponse()),
        planning = PlanningStatusResponse(planningStatus),
    )

private fun taskResponse(selectedPlanId: String? = null): TaskResponse =
    TaskResponse(
        id = "task-1",
        intent = "Plan a match night",
        revision = 2,
        selectedPlanId = selectedPlanId,
        createdAt = Now,
        updatedAt = Now,
    )

private fun requirementResponses(): List<RequirementResponse> =
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
