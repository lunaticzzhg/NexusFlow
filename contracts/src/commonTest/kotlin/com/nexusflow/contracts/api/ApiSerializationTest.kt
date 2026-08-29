package com.nexusflow.contracts.api

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
import kotlin.test.assertTrue

class ApiSerializationTest {
    private val json = Json { encodeDefaults = false }

    @Test
    fun `auth session wire field names remain stable`() {
        val session = AuthSessionResponse(
            accessToken = "access-token",
            accessTokenExpiresInSeconds = 900,
            refreshToken = "refresh-token",
            refreshTokenExpiresInSeconds = 2_592_000,
            userId = "user-1",
            tenantId = "tenant-1",
        )

        val encoded = json.encodeToString(session)

        assertEquals(
            "{\"accessToken\":\"access-token\",\"accessTokenExpiresInSeconds\":900," +
                "\"refreshToken\":\"refresh-token\",\"refreshTokenExpiresInSeconds\":2592000," +
                "\"userId\":\"user-1\",\"tenantId\":\"tenant-1\"}",
            encoded,
        )
        assertEquals(session, json.decodeFromString<AuthSessionResponse>(encoded))
    }

    @Test
    fun `dev login request wire field names remain stable`() {
        val request = DevLoginRequest(email = "dev@nexusflow.local", password = "devpass")

        val encoded = json.encodeToString(request)

        assertEquals("{\"email\":\"dev@nexusflow.local\",\"password\":\"devpass\"}", encoded)
        assertEquals(request, json.decodeFromString<DevLoginRequest>(encoded))
    }

    @Test
    fun `responses use one stable envelope for success and failure`() {
        val success = KResponse(code = 200, data = "payload")
        val failure = KResponse<String>(code = 422, message = "Invalid request")

        assertEquals("{\"code\":200,\"data\":\"payload\"}", json.encodeToString(success))
        assertEquals("{\"code\":422,\"message\":\"Invalid request\"}", json.encodeToString(failure))
        assertEquals(success, json.decodeFromString<KResponse<String>>(json.encodeToString(success)))
        assertEquals(failure, json.decodeFromString<KResponse<String>>(json.encodeToString(failure)))
    }

    @Test
    fun `task state wire values remain stable`() {
        assertEquals("\"draft\"", json.encodeToString(TaskState.Draft))
        assertEquals("\"collecting_constraints\"", json.encodeToString(TaskState.CollectingConstraints))
        assertEquals("\"planning\"", json.encodeToString(TaskState.Planning))
        assertEquals("\"waiting_for_approval\"", json.encodeToString(TaskState.WaitingForApproval))
        assertEquals("\"executing\"", json.encodeToString(TaskState.Executing))
        assertEquals("\"needs_attention\"", json.encodeToString(TaskState.NeedsAttention))
        assertEquals("\"completed\"", json.encodeToString(TaskState.Completed))
        assertEquals("\"cancelled\"", json.encodeToString(TaskState.Cancelled))
        assertEquals(TaskState.Planning, json.decodeFromString<TaskState>("\"planning\""))
    }

    @Test
    fun `task requests and summaries serialize with explicit field names`() {
        val createRequest = CreateTaskRequest(clientRequestId = "create-1", goal = "Plan Saturday")
        val messageRequest = SendTaskMessageRequest(
            clientMessageId = "message-1",
            text = "周六晚上想看利物浦，预算 300",
            timeZoneId = "Asia/Shanghai",
        )
        val summary = TaskSummaryResponse(
            id = "task-1",
            title = "Plan Saturday",
            currentGoal = "Plan Saturday",
            state = TaskState.Draft,
            updatedAt = Instant.parse("2026-08-28T10:15:30Z"),
        )

        assertEquals(
            "{\"clientRequestId\":\"create-1\",\"goal\":\"Plan Saturday\"}",
            json.encodeToString(createRequest),
        )
        assertEquals(
            "{\"clientMessageId\":\"message-1\",\"text\":\"周六晚上想看利物浦，预算 300\"," +
                "\"timeZoneId\":\"Asia/Shanghai\"}",
            json.encodeToString(messageRequest),
        )
        assertEquals(
            "{\"id\":\"task-1\",\"title\":\"Plan Saturday\",\"currentGoal\":\"Plan Saturday\"," +
                "\"state\":\"draft\",\"updatedAt\":\"2026-08-28T10:15:30Z\"}",
            json.encodeToString(summary),
        )
        assertEquals(summary, json.decodeFromString<TaskSummaryResponse>(json.encodeToString(summary)))
    }

    @Test
    fun `constraint values serialize as typed values`() {
        val constraint = ConstraintResponse(
            id = "constraint-1",
            kind = ConstraintKind.BudgetLimit,
            value = ConstraintValueResponse.BudgetLimit(wholeUnits = 300),
            strength = ConstraintStrength.Hard,
            source = ConstraintSource.UserExplicit,
            evidenceMessageId = "message-1",
            confirmedAt = Instant.parse("2026-08-28T10:16:00Z"),
            createdAt = Instant.parse("2026-08-28T10:16:00Z"),
            updatedAt = Instant.parse("2026-08-28T10:16:00Z"),
        )

        val encoded = json.encodeToString(constraint)
        val element = json.parseToJsonElement(encoded).jsonObject
        val value = element.getValue("value").jsonObject

        assertEquals("budget_limit", element.getValue("kind").jsonPrimitive.content)
        assertEquals("hard", element.getValue("strength").jsonPrimitive.content)
        assertEquals("user_explicit", element.getValue("source").jsonPrimitive.content)
        assertEquals("budget_limit", value.getValue("type").jsonPrimitive.content)
        assertEquals(JsonPrimitive(300), value.getValue("wholeUnits"))
        assertFalse("currencyCode" in value)
        assertEquals(constraint, json.decodeFromString<ConstraintResponse>(encoded))
    }

    @Test
    fun `all supported constraint value shapes round trip`() {
        val values = listOf(
            ConstraintValueResponse.TimeWindow(
                startAt = Instant.parse("2026-08-29T11:00:00Z"),
                endAt = Instant.parse("2026-08-29T14:00:00Z"),
                timeZoneId = "Asia/Shanghai",
                originalText = "周六晚上",
            ),
            ConstraintValueResponse.CommuteLimit(maxMinutes = 45),
            ConstraintValueResponse.Location(text = "Anfield"),
            ConstraintValueResponse.ActivityDomain(value = "football"),
            ConstraintValueResponse.Topic(text = "Liverpool"),
            ConstraintValueResponse.ExperiencePreference(text = "quiet"),
        )

        values.forEach { value ->
            assertEquals(value, json.decodeFromString<ConstraintValueResponse>(json.encodeToString(value)))
        }
    }

    @Test
    fun `conversation messages preserve optional understanding fields`() {
        val pendingUserMessage = ConversationMessageResponse(
            id = "message-1",
            role = MessageRole.User,
            content = "周六晚上想看利物浦，预算 300",
            clientMessageId = "client-message-1",
            createdAt = Instant.parse("2026-08-28T10:15:30Z"),
        )
        val assistantMessage = ConversationMessageResponse(
            id = "message-2",
            role = MessageRole.Assistant,
            content = "还需要确认地点。",
            aiRequestId = "ai-request-1",
            understoodAt = Instant.parse("2026-08-28T10:16:00Z"),
            createdAt = Instant.parse("2026-08-28T10:16:00Z"),
        )

        val pendingJson = json.parseToJsonElement(json.encodeToString(pendingUserMessage)).jsonObject
        assertEquals("user", pendingJson.getValue("role").jsonPrimitive.content)
        assertFalse("aiRequestId" in pendingJson)
        assertFalse("understoodAt" in pendingJson)
        assertEquals(pendingUserMessage, json.decodeFromString<ConversationMessageResponse>(json.encodeToString(pendingUserMessage)))
        assertEquals(assistantMessage, json.decodeFromString<ConversationMessageResponse>(json.encodeToString(assistantMessage)))
    }

    @Test
    fun `plan responses and planning requests round trip`() {
        val plan = planResponse()
        val response = GeneratePlansResponse(planningRunId = "planning-run-1", plans = listOf(plan))

        assertEquals(
            "{\"clientRequestId\":\"planning-request-1\"}",
            json.encodeToString(GeneratePlansRequest(clientRequestId = "planning-request-1")),
        )
        assertEquals(
            "{\"planId\":\"plan-1\"}",
            json.encodeToString(SelectPlanRequest(planId = "plan-1")),
        )

        val encoded = json.encodeToString(response)
        val element = json.parseToJsonElement(encoded).jsonObject
        val plans = element.getValue("plans") as JsonArray
        val firstPlan = plans.first().jsonObject
        assertEquals("planning-run-1", element.getValue("planningRunId").jsonPrimitive.content)
        assertEquals("fixture", firstPlan.getValue("direction").jsonPrimitive.content)
        assertEquals(JsonPrimitive(300), firstPlan.getValue("estimatedCost").jsonObject.getValue("wholeUnits"))
        assertEquals(response, json.decodeFromString<GeneratePlansResponse>(encoded))
    }

    @Test
    fun `task detail omits absent selected plan and preserves present selected plan`() {
        val detail = taskDetail(selectedPlanId = null)
        val encodedWithoutSelection = json.encodeToString(detail)
        assertFalse("selectedPlanId" in json.parseToJsonElement(encodedWithoutSelection).jsonObject)
        assertEquals(detail, json.decodeFromString<TaskDetailResponse>(encodedWithoutSelection))

        val selectedDetail = taskDetail(selectedPlanId = "plan-1")
        val encodedWithSelection = json.encodeToString(selectedDetail)
        assertTrue("selectedPlanId" in json.parseToJsonElement(encodedWithSelection).jsonObject)
        assertEquals(selectedDetail, json.decodeFromString<TaskDetailResponse>(encodedWithSelection))
    }

    private fun taskDetail(selectedPlanId: String?): TaskDetailResponse =
        TaskDetailResponse(
            id = "task-1",
            title = "周六晚上想看利物浦",
            currentGoal = "周六晚上想看利物浦，预算 300",
            state = TaskState.Planning,
            version = 2,
            constraints = listOf(
                ConstraintResponse(
                    id = "constraint-1",
                    kind = ConstraintKind.Topic,
                    value = ConstraintValueResponse.Topic(text = "利物浦"),
                    strength = ConstraintStrength.Hard,
                    source = ConstraintSource.UserExplicit,
                    evidenceMessageId = "message-1",
                    confirmedAt = Instant.parse("2026-08-28T10:16:00Z"),
                    createdAt = Instant.parse("2026-08-28T10:16:00Z"),
                    updatedAt = Instant.parse("2026-08-28T10:16:00Z"),
                ),
            ),
            messages = listOf(
                ConversationMessageResponse(
                    id = "message-1",
                    role = MessageRole.User,
                    content = "周六晚上想看利物浦，预算 300",
                    clientMessageId = "client-message-1",
                    aiRequestId = "ai-request-1",
                    understoodAt = Instant.parse("2026-08-28T10:16:00Z"),
                    createdAt = Instant.parse("2026-08-28T10:15:30Z"),
                ),
            ),
            plans = listOf(planResponse()),
            selectedPlanId = selectedPlanId,
            createdAt = Instant.parse("2026-08-28T10:15:00Z"),
            updatedAt = Instant.parse("2026-08-28T10:16:00Z"),
        )

    private fun planResponse(): PlanResponse =
        PlanResponse(
            id = "plan-1",
            taskId = "task-1",
            planningRunId = "planning-run-1",
            direction = "fixture",
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
            satisfiedConstraintIds = listOf("constraint-1"),
            tradeoffs = listOf("Fixture data only"),
            reasons = listOf("Demonstrates structured plan contract"),
            sourceRefs = listOf(PlanSourceRefResponse(label = "Fixture")),
            validUntil = Instant.parse("2026-08-29T10:00:00Z"),
            createdAt = Instant.parse("2026-08-28T10:17:00Z"),
        )
}
