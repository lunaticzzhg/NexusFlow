package com.nexusflow.ai.planner

import com.nexusflow.ai.provider.ExplanationInvalidException
import com.nexusflow.ai.provider.StructuredModelProvider
import com.nexusflow.ai.provider.StructuredModelRequest
import com.nexusflow.ai.provider.StructuredModelResult
import com.nexusflow.ai.provider.StructuredModelResultMetadata
import com.nexusflow.ai.provider.StructuredModelUsage
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StructuredPlanExplainerTest {
    @Test
    fun `explains every plan with fact refs from the same validated plan`() =
        runBlocking {
            val provider = ScriptedExplanationProvider(
                explanationPayload(
                    PlanNarrativePayload(
                        planId = "plan-1",
                        title = "Short commute cinema",
                        summary = "A movie plan grounded in controlled facts.",
                        reasons = listOf(PlanNarrativePointPayload("Matches movie intent.", listOf("fact-1"))),
                        tradeoffs = listOf(PlanNarrativePointPayload("Later start.", listOf("fact-2"))),
                    ),
                ),
            )

            val result = StructuredPlanExplainer(provider).explain(context())

            assertEquals("plan-1", result.narratives.single().planId)
            assertEquals(listOf("fact-1"), result.narratives.single().reasons.single().factIds)
            val userPayload = provider.requests.single().userPayload
            assertEquals(setOf("referenceTime", "timeZoneId"), userPayload["request"]!!.jsonObject.keys)
            val plan = userPayload["coreContext"]!!.jsonObject["plans"]!!.jsonArray.single().jsonObject
            assertEquals("plan-1", plan["planId"]!!.jsonPrimitive.content)
            assertEquals("fact-1", plan["facts"]!!.jsonArray.first().jsonObject["id"]!!.jsonPrimitive.content)
            assertFalse(userPayload.toString().contains("planning-request-1"))
        }

    @Test
    fun `explanation metadata carries safe diagnostics and provider usage`() =
        runBlocking {
            val provider = ScriptedExplanationProvider(
                explanationPayload(
                    PlanNarrativePayload(
                        planId = "plan-1",
                        title = "Short commute cinema",
                        summary = "Grounded plan.",
                        reasons = listOf(PlanNarrativePointPayload("Matches movie intent.", listOf("fact-1"))),
                        tradeoffs = emptyList(),
                    ),
                ),
            )

            val result = StructuredPlanExplainer(provider).explain(context())

            val requestPayload = provider.requests.single().userPayload.toString()
            assertEquals(6, result.metadata.usage?.inputTokens)
            assertEquals(15, result.metadata.usage?.totalTokens)
            assertEquals(0, result.metadata.diagnostics.includedContextBlockCount)
            assertTrue(result.metadata.diagnostics.fullUserPayloadSerializedChars > 0)
            assertFalse(requestPayload.contains("fullUserPayloadSerializedChars"))
        }

    @Test
    fun `rejects unknown fact refs`() =
        runBlocking {
            val provider = ScriptedExplanationProvider(
                explanationPayload(
                    PlanNarrativePayload(
                        planId = "plan-1",
                        title = "Short commute cinema",
                        summary = "Grounded plan.",
                        reasons = listOf(PlanNarrativePointPayload("Unsupported.", listOf("invented-fact"))),
                        tradeoffs = emptyList(),
                    ),
                ),
            )

            assertFailsWith<ExplanationInvalidException> {
                StructuredPlanExplainer(provider).explain(context())
            }
            Unit
        }

    @Test
    fun `repairs invalid explanation once`() =
        runBlocking {
            val provider = ScriptedExplanationProvider(
                """{"wrong":"shape"}""",
                explanationPayload(
                    PlanNarrativePayload(
                        planId = "plan-1",
                        title = "Short commute cinema",
                        summary = "Grounded plan.",
                        reasons = listOf(PlanNarrativePointPayload("Matches movie intent.", listOf("fact-1"))),
                        tradeoffs = emptyList(),
                    ),
                ),
            )

            val result = StructuredPlanExplainer(provider).explain(context())

            assertEquals(2, provider.requests.size)
            assertEquals("Short commute cinema", result.narratives.single().title)
            assertTrue(provider.requests.last().systemPrompt.contains("Repair only"))
        }

    @Test
    fun `repairs semantic explanation once`() =
        runBlocking {
            val provider = ScriptedExplanationProvider(
                explanationPayload(
                    PlanNarrativePayload(
                        planId = "plan-1",
                        title = "Short commute cinema",
                        summary = "Grounded plan.",
                        reasons = listOf(PlanNarrativePointPayload("Unsupported.", listOf("invented-fact"))),
                        tradeoffs = emptyList(),
                    ),
                ),
                explanationPayload(
                    PlanNarrativePayload(
                        planId = "plan-1",
                        title = "Short commute cinema",
                        summary = "Grounded plan.",
                        reasons = listOf(PlanNarrativePointPayload("Matches movie intent.", listOf("fact-1"))),
                        tradeoffs = emptyList(),
                    ),
                ),
            )

            val result = StructuredPlanExplainer(provider).explain(context())

            assertEquals(2, provider.requests.size)
            assertEquals(listOf("fact-1"), result.narratives.single().reasons.single().factIds)
        }

    @Test
    fun `repairs duplicate plan narratives once`() =
        runBlocking {
            val provider = ScriptedExplanationProvider(
                explanationPayload(
                    PlanNarrativePayload(
                        planId = "plan-1",
                        title = "Short commute cinema",
                        summary = "Grounded plan.",
                        reasons = listOf(PlanNarrativePointPayload("Matches movie intent.", listOf("fact-1"))),
                        tradeoffs = emptyList(),
                    ),
                    PlanNarrativePayload(
                        planId = "plan-1",
                        title = "Duplicate cinema",
                        summary = "Second narrative for the same plan.",
                        reasons = listOf(PlanNarrativePointPayload("Matches movie intent.", listOf("fact-1"))),
                        tradeoffs = emptyList(),
                    ),
                ),
                explanationPayload(
                    PlanNarrativePayload(
                        planId = "plan-1",
                        title = "Short commute cinema",
                        summary = "Grounded plan.",
                        reasons = listOf(PlanNarrativePointPayload("Matches movie intent.", listOf("fact-1"))),
                        tradeoffs = emptyList(),
                    ),
                ),
            )

            val result = StructuredPlanExplainer(provider).explain(context())

            assertEquals(2, provider.requests.size)
            assertEquals("plan-1", result.narratives.single().planId)
        }

    @Test
    fun `stops explanation after one repair`() =
        runBlocking {
            val provider = ScriptedExplanationProvider("""{"wrong":"shape"}""", """{"still":"wrong"}""")

            assertFailsWith<ExplanationInvalidException> {
                StructuredPlanExplainer(provider).explain(context())
            }
            assertEquals(2, provider.requests.size)
        }

    private fun context(): PlanExplanationContext =
        PlanExplanationContext(
            planningRequestId = "planning-request-1",
            plans = listOf(
                PlanForExplanation(
                    planId = "plan-1",
                    direction = PlanDirection.BestMatch,
                    opportunityRefs = listOf("opp-1"),
                    facts = listOf(
                        PlanExplanationFact("fact-1", "The opportunity is a movie."),
                        PlanExplanationFact("fact-2", "The start time is later in the evening."),
                    ),
                ),
            ),
            referenceTime = Instant.parse("2026-08-29T00:00:00Z"),
            timeZoneId = "Asia/Shanghai",
        )
}

private fun explanationPayload(vararg narratives: PlanNarrativePayload): String =
    ExplanationJson.encodeToString(PlanExplanationPayload(narratives.toList()))

private class ScriptedExplanationProvider(
    private vararg val outputs: String,
) : StructuredModelProvider {
    val requests = mutableListOf<StructuredModelRequest>()

    override suspend fun generate(request: StructuredModelRequest): StructuredModelResult {
        requests += request
        val outputIndex = requests.lastIndex.coerceAtMost(outputs.lastIndex)
        return StructuredModelResult(
            outputText = outputs[outputIndex],
            metadata = StructuredModelResultMetadata(
                provider = "test-provider",
                model = "test-model",
                providerRequestId = "provider-request-${requests.size}",
                attemptCount = request.metadata.attemptNumber,
                usage = StructuredModelUsage(
                    inputTokens = 6,
                    outputTokens = 9,
                    totalTokens = 15,
                ),
                requestDiagnostics = request.metadata.diagnostics,
            ),
        )
    }
}

private val ExplanationJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
    encodeDefaults = false
}
