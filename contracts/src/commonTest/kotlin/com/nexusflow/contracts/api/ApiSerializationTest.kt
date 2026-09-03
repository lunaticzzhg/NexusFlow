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
    fun `task request and summary use intent and requirements`() {
        val createRequest = CreateTaskRequest(
            clientRequestId = "create-1",
            message = "Plan Saturday",
            timeZoneId = "Asia/Shanghai",
        )
        val messageRequest = SendTaskMessageRequest(
            clientMessageId = "message-1",
            text = "周六晚上想看利物浦，预算 300",
            timeZoneId = "Asia/Shanghai",
        )
        val summary = TaskSummaryResponse(
            id = "task-1",
            intent = "Plan Saturday",
            requirements = listOf(RequirementSummaryResponse("requirement-1", "周六晚上", RequirementStrength.Must)),
            selectedPlanId = null,
            updatedAt = Instant.parse("2026-08-28T10:15:30Z"),
        )

        assertEquals(
            "{\"clientRequestId\":\"create-1\",\"message\":\"Plan Saturday\",\"timeZoneId\":\"Asia/Shanghai\"}",
            json.encodeToString(createRequest),
        )
        assertEquals(
            "{\"clientMessageId\":\"message-1\",\"text\":\"周六晚上想看利物浦，预算 300\"," +
                "\"timeZoneId\":\"Asia/Shanghai\"}",
            json.encodeToString(messageRequest),
        )
        assertEquals(
            "{\"id\":\"task-1\",\"intent\":\"Plan Saturday\",\"requirements\":[{\"id\":\"requirement-1\"," +
                "\"label\":\"周六晚上\",\"strength\":\"must\"}],\"updatedAt\":\"2026-08-28T10:15:30Z\"}",
            json.encodeToString(summary),
        )
        assertEquals(summary, json.decodeFromString<TaskSummaryResponse>(json.encodeToString(summary)))
    }

    @Test
    fun `requirement values serialize as typed values`() {
        val requirement = RequirementResponse(
            id = "requirement-1",
            kind = RequirementKind.BudgetLimit,
            value = RequirementValueResponse.BudgetLimit(wholeUnits = 300),
            strength = RequirementStrength.Must,
            source = RequirementSource.UserExplicit,
            evidenceMessageId = "message-1",
            createdAt = Instant.parse("2026-08-28T10:16:00Z"),
            updatedAt = Instant.parse("2026-08-28T10:16:00Z"),
        )

        val encoded = json.encodeToString(requirement)
        val element = json.parseToJsonElement(encoded).jsonObject
        val value = element.getValue("value").jsonObject

        assertEquals("budget_limit", element.getValue("kind").jsonPrimitive.content)
        assertEquals("must", element.getValue("strength").jsonPrimitive.content)
        assertEquals("user_explicit", element.getValue("source").jsonPrimitive.content)
        assertEquals("budget_limit", value.getValue("type").jsonPrimitive.content)
        assertEquals(JsonPrimitive(300), value.getValue("wholeUnits"))
        assertFalse("currencyCode" in value)
        assertEquals(requirement, json.decodeFromString<RequirementResponse>(encoded))
    }

    @Test
    fun `all supported requirement value shapes round trip`() {
        val values = listOf(
            RequirementValueResponse.TimeWindow(
                startAt = Instant.parse("2026-08-29T11:00:00Z"),
                endAt = Instant.parse("2026-08-29T14:00:00Z"),
                timeZoneId = "Asia/Shanghai",
                originalText = "周六晚上",
            ),
            RequirementValueResponse.CommuteLimit(maxMinutes = 45),
            RequirementValueResponse.CommutePreference(CommutePreferenceValue.PreferShorter),
            RequirementValueResponse.Location(text = "Anfield"),
            RequirementValueResponse.ActivityDomain(value = "football"),
            RequirementValueResponse.ActivityMode(ActivityModeValue.AtHome),
            RequirementValueResponse.ActivityMode(ActivityModeValue.OutOfHome),
            RequirementValueResponse.Topic(text = "Liverpool"),
            RequirementValueResponse.ExperiencePreference(text = "quiet"),
        )

        values.forEach { value ->
            assertEquals(value, json.decodeFromString<RequirementValueResponse>(json.encodeToString(value)))
        }
    }

    @Test
    fun `task messages preserve optional understanding fields`() {
        val pendingUserMessage = TaskMessageResponse(
            id = "message-1",
            role = MessageRole.User,
            content = "周六晚上想看利物浦，预算 300",
            clientMessageId = "client-message-1",
            createdAt = Instant.parse("2026-08-28T10:15:30Z"),
        )
        val assistantMessage = TaskMessageResponse(
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
        assertEquals(pendingUserMessage, json.decodeFromString<TaskMessageResponse>(json.encodeToString(pendingUserMessage)))
        assertEquals(assistantMessage, json.decodeFromString<TaskMessageResponse>(json.encodeToString(assistantMessage)))
    }

    @Test
    fun `plan response uses task revision and opportunity references`() {
        val response = planResponse()
        val encoded = json.encodeToString(response)
        val element = json.parseToJsonElement(encoded).jsonObject

        assertEquals("task-1", element.getValue("taskId").jsonPrimitive.content)
        assertEquals(JsonPrimitive(2), element.getValue("revision"))
        assertEquals("best_match", element.getValue("direction").jsonPrimitive.content)
        assertEquals(JsonPrimitive(300), element.getValue("estimatedCost").jsonObject.getValue("wholeUnits"))
        assertEquals(JsonPrimitive("opportunity-1"), (element.getValue("opportunityRefs") as JsonArray).first())
        assertEquals(response, json.decodeFromString<PlanResponse>(encoded))
    }

    @Test
    fun `task detail omits absent selected plan and carries active plan revision`() {
        val detail = TaskDetailResponse(
            task =
                TaskResponse(
                    id = "task-1",
                    intent = "周六晚上想看利物浦，预算 300",
                    revision = 2,
                    selectedPlanId = null,
                    createdAt = Instant.parse("2026-08-28T10:15:00Z"),
                    updatedAt = Instant.parse("2026-08-28T10:16:00Z"),
                ),
            requirements = listOf(
                RequirementResponse(
                    id = "requirement-1",
                    kind = RequirementKind.Topic,
                    value = RequirementValueResponse.Topic(text = "利物浦"),
                    strength = RequirementStrength.Must,
                    source = RequirementSource.UserExplicit,
                    evidenceMessageId = "message-1",
                    createdAt = Instant.parse("2026-08-28T10:16:00Z"),
                    updatedAt = Instant.parse("2026-08-28T10:16:00Z"),
                ),
            ),
            messages = listOf(
                TaskMessageResponse(
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
            planning = PlanningStatusResponse(PlanningStatus.Idle),
        )

        val encoded = json.encodeToString(detail)
        val element = json.parseToJsonElement(encoded).jsonObject

        assertFalse("selectedPlanId" in element.getValue("task").jsonObject)
        assertEquals(JsonPrimitive(2), element.getValue("task").jsonObject.getValue("revision"))
        assertEquals(detail, json.decodeFromString<TaskDetailResponse>(encoded))
    }

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
            requirementEvaluations =
                listOf(
                    RequirementEvaluationResponse(
                        requirementId = "requirement-1",
                        result = RequirementEvaluationResult.Satisfied,
                    ),
                ),
            tradeoffs = listOf("Fixture data only"),
            reasons = listOf("Demonstrates structured plan contract"),
            sourceRefs = listOf(
                PlanSourceRefResponse(
                    label = "Fixture",
                    sourceUpdatedAt = Instant.parse("2026-08-28T10:10:00Z"),
                ),
            ),
            opportunityRefs = listOf("opportunity-1"),
            validUntil = Instant.parse("2026-08-29T10:00:00Z"),
            createdAt = Instant.parse("2026-08-28T10:17:00Z"),
        )
}
