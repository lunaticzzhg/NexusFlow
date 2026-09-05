package com.nexusflow.contracts.backendai

import com.nexusflow.contracts.backendai.common.StructuredModelRequestDiagnostics
import com.nexusflow.contracts.backendai.planning.CandidateOpportunity
import com.nexusflow.contracts.backendai.planning.CreatePlansResult
import com.nexusflow.contracts.backendai.planning.PlanDirection
import com.nexusflow.contracts.backendai.planning.PlanProposal
import com.nexusflow.contracts.backendai.planning.ExplainPlansResult
import com.nexusflow.contracts.backendai.planning.ExplainPlansRequest
import com.nexusflow.contracts.backendai.planning.PlanExplanationFact
import com.nexusflow.contracts.backendai.planning.PlanForExplanation
import com.nexusflow.contracts.backendai.planning.PlanNarrative
import com.nexusflow.contracts.backendai.planning.PlanNarrativePoint
import com.nexusflow.contracts.backendai.planning.CreatePlansRequest
import com.nexusflow.contracts.backendai.planning.PlanningRequirement
import com.nexusflow.contracts.backendai.planning.PlanningRequirementStrength
import com.nexusflow.contracts.backendai.understanding.ClarificationProposal
import com.nexusflow.contracts.backendai.understanding.ClarificationReasonCategory
import com.nexusflow.contracts.backendai.understanding.ContextSelectionProposal
import com.nexusflow.contracts.backendai.understanding.RequirementChangeProposal
import com.nexusflow.contracts.backendai.understanding.RequirementKind
import com.nexusflow.contracts.backendai.understanding.RequirementStrength
import com.nexusflow.contracts.backendai.understanding.RequirementValue
import com.nexusflow.contracts.backendai.understanding.UnderstandMessageRequest
import com.nexusflow.contracts.backendai.understanding.UnderstandingMetadata
import com.nexusflow.contracts.backendai.understanding.UnderstandMessageResult
import com.nexusflow.contracts.backendai.understanding.UserIntent
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

class BackendAiSerializationTest {
    private val json = Json { encodeDefaults = false }

    @Test
    fun `understanding request and result serialize as proposals`() {
        val request = UnderstandMessageRequest(
            aiRequestId = "understand-1",
            taskId = "task-1",
            taskRevision = 3,
            intent = "Find dinner",
            requirements = emptyList(),
            currentMessage = "Budget 300",
            referenceTime = Now,
            timeZoneId = "Asia/Shanghai",
        )
        val result = UnderstandMessageResult(
            userIntent = UserIntent.RequirementUpdate,
            intentPatch = null,
            requirementChanges = listOf(
                RequirementChangeProposal(
                    kind = RequirementKind.BudgetLimit,
                    value = RequirementValue.BudgetLimit(300, "CNY"),
                    strength = RequirementStrength.Must,
                    evidenceText = "Budget 300",
                ),
            ),
            clarification = ClarificationProposal(false, emptyList(), ClarificationReasonCategory.None, null),
            contextSelection = ContextSelectionProposal(),
            metadata = UnderstandingMetadata("test-provider", "test-model", "understand-user-message-v1", null, 1),
        )

        val requestElement = json.parseToJsonElement(json.encodeToString(request)).jsonObject
        val resultElement = json.parseToJsonElement(json.encodeToString(result)).jsonObject

        assertEquals(JsonPrimitive(3), requestElement.getValue("taskRevision"))
        assertEquals("requirementChanges", resultElement.keys.first { it == "requirementChanges" })
        assertEquals("budget_limit", (resultElement.getValue("requirementChanges") as JsonArray).first().jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals(result, json.decodeFromString<UnderstandMessageResult>(json.encodeToString(result)))
    }

    @Test
    fun `planning request only exposes backend supplied opportunities and proposal refs`() {
        val request = CreatePlansRequest(
            planningRequestId = "plan-task-1-3",
            taskId = "task-1",
            taskRevision = 3,
            intent = "Find dinner",
            requirements = listOf(PlanningRequirement("requirement-1", "BudgetLimit", "300 CNY", PlanningRequirementStrength.Must)),
            opportunities = listOf(opportunity()),
            referenceTime = Now,
            timeZoneId = "Asia/Shanghai",
            diagnostics = StructuredModelRequestDiagnostics(includedContextBlockCount = 0),
        )
        val result = CreatePlansResult(drafts = listOf(PlanProposal(PlanDirection.BestMatch, listOf("opportunity-1"))))

        val requestElement = json.parseToJsonElement(json.encodeToString(request)).jsonObject
        val resultElement = json.parseToJsonElement(json.encodeToString(result)).jsonObject

        assertFalse(requestElement.toString().contains("providerRequestId"))
        assertEquals("opportunity-1", (resultElement.getValue("drafts") as JsonArray).first().jsonObject.getValue("opportunityRefs").let { it as JsonArray }.first().jsonPrimitive.content)
        assertEquals(request, json.decodeFromString<CreatePlansRequest>(json.encodeToString(request)))
    }

    @Test
    fun `plan explanation is grounded in validated facts`() {
        val request = ExplainPlansRequest(
            planningRequestId = "plan-task-1-3",
            plans = listOf(
                PlanForExplanation(
                    planId = "plan-1",
                    direction = PlanDirection.BestMatch,
                    opportunityRefs = listOf("opportunity-1"),
                    facts = listOf(PlanExplanationFact("fact-1", "Backend supplied fact")),
                ),
            ),
            referenceTime = Now,
            timeZoneId = "Asia/Shanghai",
        )
        val result = ExplainPlansResult(
            narratives = listOf(
                PlanNarrative(
                    planId = "plan-1",
                    title = "Dinner",
                    summary = "Grounded summary.",
                    reasons = listOf(PlanNarrativePoint("Uses supplied fact.", listOf("fact-1"))),
                    tradeoffs = emptyList(),
                ),
            ),
        )

        assertEquals(request, json.decodeFromString<ExplainPlansRequest>(json.encodeToString(request)))
        assertEquals(result, json.decodeFromString<ExplainPlansResult>(json.encodeToString(result)))
    }

    private fun opportunity(): CandidateOpportunity =
        CandidateOpportunity(
            id = "opportunity-1",
            domain = "dining",
            title = "Dinner slot",
            summary = "Reserved table",
            location = "Futian",
            activityMode = "out_of_home",
            startsAt = Now,
            endsAt = Instant.parse("2026-08-29T04:00:00Z"),
            estimatedCostWholeUnits = 300,
            currencyCode = "CNY",
            commuteMinutes = 20,
            sourceLabel = "Controlled Feed",
            sourceUpdatedAt = Now,
            validUntil = Instant.parse("2026-08-30T00:00:00Z"),
        )
}

private val Now = Instant.parse("2026-08-29T00:00:00Z")
