package com.nexusflow.contracts.appbackend

import com.nexusflow.contracts.appbackend.auth.AuthSessionResponse
import com.nexusflow.contracts.appbackend.auth.DevLoginRequest
import com.nexusflow.contracts.appbackend.common.KResponse
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
import com.nexusflow.contracts.appbackend.task.CreateTaskRequest
import com.nexusflow.contracts.appbackend.task.RequirementKind
import com.nexusflow.contracts.appbackend.task.RequirementResponse
import com.nexusflow.contracts.appbackend.task.RequirementSource
import com.nexusflow.contracts.appbackend.task.RequirementStrength
import com.nexusflow.contracts.appbackend.task.RequirementSummaryResponse
import com.nexusflow.contracts.appbackend.task.RequirementValueResponse
import com.nexusflow.contracts.appbackend.task.SendTaskMessageRequest
import com.nexusflow.contracts.appbackend.task.TaskDetailResponse
import com.nexusflow.contracts.appbackend.task.TaskMessageResponse
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
    fun `task request summary and planning status keep app backend shape`() {
        val createRequest = CreateTaskRequest(
            clientRequestId = "create-1",
            message = "周六晚上想看利物浦，预算 300",
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
            updatedAt = Instant.parse("2026-08-28T10:15:30Z"),
        )

        assertEquals(
            "{\"clientRequestId\":\"create-1\",\"message\":\"周六晚上想看利物浦，预算 300\"," +
                "\"timeZoneId\":\"Asia/Shanghai\"}",
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
