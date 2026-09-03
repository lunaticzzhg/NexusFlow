package com.nexusflow.ai.understanding

import com.nexusflow.ai.planner.CandidateOpportunityPayload
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
    fun `understanding output carries requirement changes and intent patch`() {
        val payload = StructuredUnderstandingPayload(
            userIntent = "requirement_update",
            intentPatch = "Watch Liverpool this weekend",
            requirementChanges =
                listOf(
                    StructuredRequirementPayload(
                        kind = "topic",
                        strength = "must",
                        evidenceText = "Liverpool",
                        textValue = "Liverpool",
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

        assertEquals("requirement_update", element.getValue("userIntent").jsonPrimitive.content)
        assertEquals("Watch Liverpool this weekend", element.getValue("intentPatch").jsonPrimitive.content)
        assertEquals("requirementChanges", element.keys.first { it == "requirementChanges" })
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
                        sourceLabel = "Controlled Sports Feed",
                        sourceUpdatedAt = Instant.parse("2026-08-29T10:00:00Z"),
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
