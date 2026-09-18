package com.nexusflow.contracts.appbackend

import com.nexusflow.contracts.appbackend.auth.AuthSessionResponse
import com.nexusflow.contracts.appbackend.auth.DevLoginRequest
import com.nexusflow.contracts.appbackend.common.KResponse
import com.nexusflow.contracts.appbackend.conversation.ConversationCurrentTaskResponse
import com.nexusflow.contracts.appbackend.conversation.ConversationDetailResponse
import com.nexusflow.contracts.appbackend.conversation.ConversationMessageResponse
import com.nexusflow.contracts.appbackend.conversation.ConversationResponse
import com.nexusflow.contracts.appbackend.conversation.CreateConversationRequest
import com.nexusflow.contracts.appbackend.conversation.CreateConversationResponse
import com.nexusflow.contracts.appbackend.conversation.ResponseRunActivityKindResponse
import com.nexusflow.contracts.appbackend.conversation.ResponseRunActivityResponse
import com.nexusflow.contracts.appbackend.conversation.ResponseRunEventEnvelope
import com.nexusflow.contracts.appbackend.conversation.ResponseRunEventPayload
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
import com.nexusflow.contracts.appbackend.task.RequirementKind
import com.nexusflow.contracts.appbackend.task.RequirementResponse
import com.nexusflow.contracts.appbackend.task.RequirementSource
import com.nexusflow.contracts.appbackend.task.RequirementStrength
import com.nexusflow.contracts.appbackend.task.RequirementSummaryResponse
import com.nexusflow.contracts.appbackend.task.RequirementValueResponse
import com.nexusflow.contracts.appbackend.task.TaskDetailResponse
import com.nexusflow.contracts.appbackend.task.TaskResponse
import com.nexusflow.contracts.appbackend.task.TaskSummaryResponse
import kotlinx.datetime.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AppBackendSerializationTest {
    private val json = Json { encodeDefaults = false }

    @Test
    fun `auth session and envelope wire field names remain stable`() {
        val session = AuthSessionResponse(
            accessToken = "access-token",
            accessTokenExpiresInSeconds = 900,
            refreshToken = "refresh-token",
            refreshTokenExpiresInSeconds = 2_592_000,
            userId = "user-1",
            tenantId = "tenant-1",
        )

        assertEquals(
            "{\"accessToken\":\"access-token\",\"accessTokenExpiresInSeconds\":900," +
                "\"refreshToken\":\"refresh-token\",\"refreshTokenExpiresInSeconds\":2592000," +
                "\"userId\":\"user-1\",\"tenantId\":\"tenant-1\"}",
            json.encodeToString(session),
        )
        assertEquals("{\"email\":\"dev@nexusflow.local\",\"password\":\"devpass\"}", json.encodeToString(DevLoginRequest("dev@nexusflow.local", "devpass")))
        assertEquals("{\"code\":200,\"data\":\"payload\"}", json.encodeToString(KResponse(code = 200, data = "payload")))
        assertEquals(session, json.decodeFromString<AuthSessionResponse>(json.encodeToString(session)))
    }

    @Test
    fun `task summary and planning status keep app backend shape`() {
        val summary = TaskSummaryResponse(
            id = "task-1",
            intent = "Plan Saturday",
            requirements = listOf(RequirementSummaryResponse("requirement-1", "周六晚上", RequirementStrength.Must)),
            updatedAt = Instant.parse("2026-08-28T10:15:30Z"),
        )

        assertEquals(
            "{\"id\":\"task-1\",\"intent\":\"Plan Saturday\",\"requirements\":[{\"id\":\"requirement-1\"," +
                "\"label\":\"周六晚上\",\"strength\":\"must\"}],\"updatedAt\":\"2026-08-28T10:15:30Z\"}",
            json.encodeToString(summary),
        )
        assertEquals(null, json.decodeFromString<TaskSummaryResponse>(json.encodeToString(summary)).conversationId)

        val linkedSummary = summary.copy(conversationId = "conversation-1")
        assertEquals(
            "{\"id\":\"task-1\",\"intent\":\"Plan Saturday\",\"requirements\":[{\"id\":\"requirement-1\"," +
                "\"label\":\"周六晚上\",\"strength\":\"must\"}],\"updatedAt\":\"2026-08-28T10:15:30Z\"," +
                "\"conversationId\":\"conversation-1\"}",
            json.encodeToString(linkedSummary),
        )
        assertEquals(
            "conversation-1",
            json.decodeFromString<TaskSummaryResponse>(json.encodeToString(linkedSummary)).conversationId,
        )
        assertEquals("{\"status\":\"no_feasible_plan\"}", json.encodeToString(PlanningStatusResponse(PlanningStatus.NoFeasiblePlan)))
    }

    @Test
    fun `requirement values and task detail round trip`() {
        val detail = TaskDetailResponse(
            task = TaskResponse(
                id = "task-1",
                intent = "周六晚上想看利物浦，预算 300",
                revision = 2,
                createdAt = Instant.parse("2026-08-28T10:15:00Z"),
                updatedAt = Instant.parse("2026-08-28T10:16:00Z"),
            ),
            requirements = listOf(
                RequirementResponse(
                    id = "requirement-1",
                    kind = RequirementKind.BudgetLimit,
                    value = RequirementValueResponse.BudgetLimit(wholeUnits = 300),
                    strength = RequirementStrength.Must,
                    source = RequirementSource.UserExplicit,
                    evidenceMessageId = "message-1",
                    createdAt = Instant.parse("2026-08-28T10:16:00Z"),
                    updatedAt = Instant.parse("2026-08-28T10:16:00Z"),
                ),
            ),
            plans = listOf(planResponse()),
            planning = PlanningStatusResponse(PlanningStatus.Ready),
        )

        val encoded = json.encodeToString(detail)
        val element = json.parseToJsonElement(encoded).jsonObject
        val requirementValue = element.getValue("requirements").let { it as JsonArray }.first().jsonObject.getValue("value").jsonObject

        assertFalse("selectedPlanId" in element.getValue("task").jsonObject)
        assertEquals("budget_limit", requirementValue.getValue("type").jsonPrimitive.content)
        assertEquals(JsonPrimitive(300), requirementValue.getValue("wholeUnits"))
        assertEquals(detail, json.decodeFromString<TaskDetailResponse>(encoded))
    }

    @Test
    fun `plan response exposes revision and verified opportunity references`() {
        val response = planResponse()
        val element = json.parseToJsonElement(json.encodeToString(response)).jsonObject

        assertEquals("task-1", element.getValue("taskId").jsonPrimitive.content)
        assertEquals(JsonPrimitive(2), element.getValue("revision"))
        assertEquals("best_match", element.getValue("direction").jsonPrimitive.content)
        assertEquals(JsonPrimitive("opportunity-1"), (element.getValue("opportunityRefs") as JsonArray).first())
        assertEquals(response, json.decodeFromString<PlanResponse>(json.encodeToString(response)))
    }

    @Test
    fun `create conversation request and response keep app backend shape`() {
        val request = CreateConversationRequest(
            clientRequestId = "create-conversation-1",
            message = "你好",
            timeZoneId = "Asia/Shanghai",
        )
        val response = CreateConversationResponse(
            conversation = conversationResponse(
                messages = listOf(
                    conversationMessageResponse(
                        id = "message-1",
                        role = MessageRole.User,
                        content = "你好",
                        clientMessageId = "create-conversation-1",
                    ),
                ),
            ),
        )

        assertEquals(
            "{\"clientRequestId\":\"create-conversation-1\",\"message\":\"你好\"," +
                "\"timeZoneId\":\"Asia/Shanghai\"}",
            json.encodeToString(request),
        )
        assertEquals(
            "{\"conversation\":{\"id\":\"conversation-1\",\"messages\":[{\"id\":\"message-1\",\"role\":\"user\"," +
                "\"content\":\"你好\",\"clientMessageId\":\"create-conversation-1\"," +
                "\"createdAt\":\"2026-08-28T10:15:30Z\"}],\"createdAt\":\"2026-08-28T10:15:00Z\"," +
                "\"updatedAt\":\"2026-08-28T10:16:00Z\"}}",
            json.encodeToString(response),
        )
        assertEquals(request, json.decodeFromString<CreateConversationRequest>(json.encodeToString(request)))
        assertEquals(response, json.decodeFromString<CreateConversationResponse>(json.encodeToString(response)))
    }

    @Test
    fun `pure conversation detail has messages and no current task`() {
        val detail = ConversationDetailResponse(
            conversation = conversationResponse(
                messages = listOf(
                    conversationMessageResponse(
                        id = "message-1",
                        role = MessageRole.User,
                        content = "Kotlin Flow 是什么？",
                        clientMessageId = "client-message-1",
                    ),
                    conversationMessageResponse(
                        id = "message-2",
                        role = MessageRole.Assistant,
                        content = "Flow 是 Kotlin 协程里的冷异步数据流。",
                        aiRequestId = "ai-request-1",
                    ),
                ),
            ),
        )

        val encoded = json.encodeToString(detail)
        val element = json.parseToJsonElement(encoded).jsonObject
        val messages = element.getValue("conversation").jsonObject.getValue("messages") as JsonArray

        assertFalse("currentTask" in element)
        assertEquals(JsonPrimitive(2), JsonPrimitive(messages.size))
        assertEquals("assistant", messages[1].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals(detail, json.decodeFromString<ConversationDetailResponse>(encoded))
        assertEquals(null, json.decodeFromString<ConversationDetailResponse>(encoded).currentTask)
    }

    @Test
    fun `planning conversation detail includes one current task with planning payload`() {
        val detail = ConversationDetailResponse(
            conversation = conversationResponse(
                messages = listOf(
                    conversationMessageResponse(
                        id = "message-1",
                        role = MessageRole.User,
                        content = "周六晚上想看利物浦，预算 300",
                        clientMessageId = "client-message-1",
                        understoodAt = Instant.parse("2026-08-28T10:16:00Z"),
                    ),
                ),
            ),
            currentTask = ConversationCurrentTaskResponse(
                task = TaskResponse(
                    id = "task-1",
                    intent = "周六晚上想看利物浦，预算 300",
                    revision = 2,
                    createdAt = Instant.parse("2026-08-28T10:15:00Z"),
                    updatedAt = Instant.parse("2026-08-28T10:16:00Z"),
                ),
                requirements = listOf(requirementResponse()),
                plans = listOf(planResponse()),
                planning = PlanningStatusResponse(PlanningStatus.Ready),
            ),
        )

        val encoded = json.encodeToString(detail)
        val element = json.parseToJsonElement(encoded).jsonObject
        val currentTask = element.getValue("currentTask").jsonObject

        assertEquals("task-1", currentTask.getValue("task").jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals(JsonPrimitive(1), JsonPrimitive((currentTask.getValue("requirements") as JsonArray).size))
        assertEquals(JsonPrimitive(1), JsonPrimitive((currentTask.getValue("plans") as JsonArray).size))
        assertEquals("ready", currentTask.getValue("planning").jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals(detail, json.decodeFromString<ConversationDetailResponse>(encoded))
    }

    @Test
    fun `send conversation message request and response preserve idempotency and nullable task`() {
        val request = SendConversationMessageRequest(
            clientMessageId = "client-message-2",
            text = "谢谢",
            timeZoneId = "Asia/Shanghai",
        )
        val response = SendConversationMessageResponse(
            conversation = conversationResponse(
                messages = listOf(
                    conversationMessageResponse(
                        id = "message-1",
                        role = MessageRole.User,
                        content = "Kotlin Flow 是什么？",
                        clientMessageId = "client-message-1",
                    ),
                    conversationMessageResponse(
                        id = "message-2",
                        role = MessageRole.User,
                        content = "谢谢",
                        clientMessageId = "client-message-2",
                    ),
                ),
            ),
        )

        val encodedRequest = json.encodeToString(request)
        val encodedResponse = json.encodeToString(response)
        val requestElement = json.parseToJsonElement(encodedRequest).jsonObject
        val responseElement = json.parseToJsonElement(encodedResponse).jsonObject

        assertEquals("client-message-2", requestElement.getValue("clientMessageId").jsonPrimitive.content)
        assertEquals("Asia/Shanghai", requestElement.getValue("timeZoneId").jsonPrimitive.content)
        assertFalse("currentTask" in responseElement)
        assertEquals(request, json.decodeFromString<SendConversationMessageRequest>(encodedRequest))
        assertEquals(response, json.decodeFromString<SendConversationMessageResponse>(encodedResponse))
        assertEquals(null, json.decodeFromString<SendConversationMessageResponse>(encodedResponse).currentTask)
    }

    @Test
    fun `conversation response exposes response runs and keeps old payload compatibility`() {
        val response = ConversationDetailResponse(
            conversation = conversationResponse(
                messages = listOf(
                    conversationMessageResponse(
                        id = "message-1",
                        role = MessageRole.User,
                        content = "你好",
                        clientMessageId = "client-message-1",
                        turnIndex = 1,
                    ),
                ),
                responseRuns = listOf(
                    ResponseRunResponse(
                        id = "run-1",
                        userMessageId = "message-1",
                        turnIndex = 1,
                        status = ResponseRunStatusResponse.Queued,
                        attempt = 0,
                        retryable = false,
                        createdAt = Instant.parse("2026-08-28T10:15:30Z"),
                        updatedAt = Instant.parse("2026-08-28T10:15:30Z"),
                    ),
                ),
            ),
        )

        val encoded = json.encodeToString(response)
        val element = json.parseToJsonElement(encoded).jsonObject.getValue("conversation").jsonObject
        val message = (element.getValue("messages") as JsonArray).first().jsonObject
        val run = (element.getValue("responseRuns") as JsonArray).first().jsonObject
        val oldPayload = """
            {
              "conversation": {
                "id": "conversation-1",
                "messages": [
                  {
                    "id": "message-1",
                    "role": "user",
                    "content": "你好",
                    "clientMessageId": "client-message-1",
                    "createdAt": "2026-08-28T10:15:30Z"
                  }
                ],
                "createdAt": "2026-08-28T10:15:00Z",
                "updatedAt": "2026-08-28T10:16:00Z"
              }
            }
        """.trimIndent()

        assertEquals(JsonPrimitive(1), message.getValue("turnIndex"))
        assertEquals("run-1", run.getValue("id").jsonPrimitive.content)
        assertEquals("message-1", run.getValue("userMessageId").jsonPrimitive.content)
        assertEquals("QUEUED", run.getValue("status").jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), run.getValue("retryable"))
        assertEquals(response, json.decodeFromString<ConversationDetailResponse>(encoded))
        val decodedOld = json.decodeFromString<ConversationDetailResponse>(oldPayload)
        assertEquals(null, decodedOld.conversation.messages.single().turnIndex)
        assertEquals(emptyList(), decodedOld.conversation.responseRuns)
    }

    @Test
    fun `response run snapshot and event payload keep realtime wire shape`() {
        val run = ResponseRunResponse(
            id = "run-1",
            userMessageId = "message-1",
            turnIndex = 1,
            status = ResponseRunStatusResponse.Streaming,
            attempt = 2,
            retryable = false,
            createdAt = Instant.parse("2026-08-28T10:15:30Z"),
            updatedAt = Instant.parse("2026-08-28T10:16:00Z"),
        )
        val snapshot = ResponseRunSnapshotResponse(
            run = run,
            conversation = conversationResponse(
                messages = listOf(
                    conversationMessageResponse(
                        id = "message-1",
                        role = MessageRole.User,
                        content = "你好",
                        turnIndex = 1,
                    ),
                ),
                responseRuns = listOf(run),
            ),
            streamAttempt = 2,
            lastSeq = 7,
            partialText = "partial",
            activities = listOf(
                ResponseRunActivityResponse(
                    id = "activity-1",
                    kind = ResponseRunActivityKindResponse.Web,
                    startedAt = Instant.parse("2026-08-28T10:15:31Z"),
                    completedAt = Instant.parse("2026-08-28T10:15:32Z"),
                ),
            ),
            realtimeSnapshotAvailable = true,
        )
        val event = ResponseRunEventEnvelope(
            runId = "run-1",
            attempt = 2,
            seq = 8,
            occurredAt = Instant.parse("2026-08-28T10:16:01Z"),
            payload = ResponseRunEventPayload.Delta("hello"),
        )

        val snapshotElement = json.parseToJsonElement(json.encodeToString(snapshot)).jsonObject
        val eventElement = json.parseToJsonElement(json.encodeToString(event)).jsonObject

        assertEquals(JsonPrimitive(2), snapshotElement.getValue("streamAttempt"))
        assertEquals(JsonPrimitive(7), snapshotElement.getValue("lastSeq"))
        assertEquals("partial", snapshotElement.getValue("partialText").jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), snapshotElement.getValue("realtimeSnapshotAvailable"))
        assertEquals("delta", eventElement.getValue("payload").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("hello", eventElement.getValue("payload").jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals(snapshot, json.decodeFromString<ResponseRunSnapshotResponse>(json.encodeToString(snapshot)))
        assertEquals(event, json.decodeFromString<ResponseRunEventEnvelope>(json.encodeToString(event)))
    }

    private fun conversationResponse(
        messages: List<ConversationMessageResponse>,
        responseRuns: List<ResponseRunResponse> = emptyList(),
    ): ConversationResponse =
        ConversationResponse(
            id = "conversation-1",
            messages = messages,
            responseRuns = responseRuns,
            createdAt = Instant.parse("2026-08-28T10:15:00Z"),
            updatedAt = Instant.parse("2026-08-28T10:16:00Z"),
        )

    private fun conversationMessageResponse(
        id: String,
        role: MessageRole,
        content: String,
        clientMessageId: String? = null,
        aiRequestId: String? = null,
        turnIndex: Long? = null,
        understoodAt: Instant? = null,
    ): ConversationMessageResponse =
        ConversationMessageResponse(
            id = id,
            role = role,
            content = content,
            clientMessageId = clientMessageId,
            aiRequestId = aiRequestId,
            turnIndex = turnIndex,
            understoodAt = understoodAt,
            createdAt = Instant.parse("2026-08-28T10:15:30Z"),
        )

    private fun requirementResponse(): RequirementResponse =
        RequirementResponse(
            id = "requirement-1",
            kind = RequirementKind.BudgetLimit,
            value = RequirementValueResponse.BudgetLimit(wholeUnits = 300),
            strength = RequirementStrength.Must,
            source = RequirementSource.UserExplicit,
            evidenceMessageId = "message-1",
            createdAt = Instant.parse("2026-08-28T10:16:00Z"),
            updatedAt = Instant.parse("2026-08-28T10:16:00Z"),
        )

    private fun planResponse(): PlanResponse =
        PlanResponse(
            id = "plan-1",
            taskId = "task-1",
            revision = 2,
            direction = PlanDirection.BestMatch,
            title = "Watch Liverpool",
            summary = "A simple fixture plan.",
            timeline = listOf(
                PlanTimelineItemResponse(
                    title = "Match time",
                    startAt = Instant.parse("2026-08-29T11:00:00Z"),
                    endAt = Instant.parse("2026-08-29T13:00:00Z"),
                    location = "Home",
                ),
            ),
            estimatedCost = PlanEstimatedCostResponse(wholeUnits = 300),
            commuteMinutes = 0,
            requirementEvaluations = listOf(RequirementEvaluationResponse("requirement-1", RequirementEvaluationResult.Satisfied)),
            tradeoffs = listOf("Fixture data only"),
            reasons = listOf("Demonstrates structured plan contract"),
            sourceRefs = listOf(PlanSourceRefResponse(label = "Fixture")),
            opportunityRefs = listOf("opportunity-1"),
            validUntil = Instant.parse("2026-08-29T10:00:00Z"),
            createdAt = Instant.parse("2026-08-28T10:17:00Z"),
        )
}
