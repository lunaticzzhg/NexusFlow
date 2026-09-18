package com.nexusflow.ai.understanding

import com.nexusflow.ai.planner.CandidateOpportunityPayload
import com.nexusflow.ai.planner.CandidateSourceRefPayload
import com.nexusflow.ai.planner.PlanCompositionPayload
import com.nexusflow.ai.planner.PlanDraftPayload
import com.nexusflow.ai.planner.PlanningCoreContextPayload
import com.nexusflow.ai.planner.PlanningRequirementPayload
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class StructuredPayloadModelsTest {
    private val json = Json { encodeDefaults = false }

    @Test
    fun `understanding output carries constraint deltas and planning goal patch`() {
        val payload = StructuredUnderstandingPayload(
            turnIntent = "planning",
            planningGoalPatch = "Watch Liverpool this weekend",
            constraintDeltas =
                listOf(
                    StructuredConstraintDeltaPayload(
                        operation = "upsert",
                        kind = "topic",
                        evidenceText = "Liverpool",
                        value = StructuredRequirementValuePayload(
                            type = "topic",
                            textValue = "Liverpool",
                        ),
                        strength = "must",
                    ),
                ),
            clarification =
                StructuredClarificationPayload(
                    needed = false,
                    missingInformation = emptyList(),
                    reasonCategory = "none",
                ),
            contextSelection = StructuredContextSelectionPayload(selectedKeys = emptyList()),
        )

        val encoded = json.encodeToString(payload)
        val element = json.parseToJsonElement(encoded).jsonObject

        assertEquals("planning", element.getValue("turnIntent").jsonPrimitive.content)
        assertEquals("Watch Liverpool this weekend", element.getValue("planningGoalPatch").jsonPrimitive.content)
        assertEquals("constraintDeltas", element.keys.first { it == "constraintDeltas" })
    }

    @Test
    fun `understanding output carries nullable remove delta value`() {
        val payload = StructuredUnderstandingPayload(
            turnIntent = "planning",
            constraintDeltas = listOf(
                StructuredConstraintDeltaPayload(
                    operation = "remove",
                    kind = "budget_limit",
                    evidenceText = "no budget limit",
                    value = null,
                    strength = null,
                ),
            ),
            clarification =
                StructuredClarificationPayload(
                    needed = false,
                    missingInformation = emptyList(),
                    reasonCategory = "none",
                ),
            contextSelection = StructuredContextSelectionPayload(selectedKeys = emptyList()),
        )

        val element = json.parseToJsonElement(json.encodeToString(payload)).jsonObject
        val delta = (element.getValue("constraintDeltas") as JsonArray).first().jsonObject

        assertEquals("planning", element.getValue("turnIntent").jsonPrimitive.content)
        assertEquals("remove", delta.getValue("operation").jsonPrimitive.content)
        assertEquals("budget_limit", delta.getValue("kind").jsonPrimitive.content)
    }

    @Test
    fun `planning context exposes requirements and opportunity snapshots`() {
        val payload = PlanningCoreContextPayload(
            intent = "Watch Liverpool this weekend",
            requirements = listOf(PlanningRequirementPayload("topic", "Liverpool", "must")),
            opportunities =
                listOf(
                    CandidateOpportunityPayload(
                        id = "opportunity-1",
                        domain = "sports",
                        title = "Liverpool supporters pub screening",
                        summary = "Reserved table",
                        location = "Futian",
                        activityMode = "out_of_home",
                        startsAt = Instant.parse("2026-08-29T12:00:00Z"),
                        endsAt = Instant.parse("2026-08-29T15:00:00Z"),
                        estimatedCostWholeUnits = 180,
                        currencyCode = "CNY",
                        commuteMinutes = 18,
                        sources = listOf(
                            CandidateSourceRefPayload(
                                label = "Controlled Sports Feed",
                                uri = "controlled://sports",
                                sourceUpdatedAt = Instant.parse("2026-08-29T10:00:00Z"),
                                sourceId = "controlled-sports-feed",
                                authority = "StructuredPrimary",
                                factKeys = listOf("Title", "StartTime", "Location"),
                            ),
                        ),
                        validUntil = Instant.parse("2026-08-30T10:00:00Z"),
                    ),
                ),
        )

        val element = json.parseToJsonElement(json.encodeToString(payload)).jsonObject

        assertEquals("Watch Liverpool this weekend", element.getValue("intent").jsonPrimitive.content)
        assertEquals("requirements", element.keys.first { it == "requirements" })
        assertEquals("opportunities", element.keys.first { it == "opportunities" })
    }

    @Test
    fun `planner output only returns draft opportunity ids`() {
        val payload = PlanCompositionPayload(
            drafts =
                listOf(
                    PlanDraftPayload(
                        direction = "best_match",
                        opportunityRefs = listOf("opportunity-1"),
                    ),
                ),
        )

        val element = json.parseToJsonElement(json.encodeToString(payload)).jsonObject
        val draft = (element.getValue("drafts") as JsonArray).first().jsonObject

        assertEquals("best_match", draft.getValue("direction").jsonPrimitive.content)
        assertEquals("opportunity-1", (draft.getValue("opportunityRefs") as JsonArray).first().jsonPrimitive.content)
    }
}
