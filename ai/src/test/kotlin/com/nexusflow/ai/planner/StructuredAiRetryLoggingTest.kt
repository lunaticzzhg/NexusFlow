package com.nexusflow.ai.planner

import com.nexusflow.ai.provider.StructuredModelProvider
import com.nexusflow.ai.provider.StructuredModelRequest
import com.nexusflow.ai.provider.StructuredModelResult
import com.nexusflow.ai.provider.StructuredModelResultMetadata
import com.nexusflow.contracts.backendai.planning.CandidateOpportunity
import com.nexusflow.contracts.backendai.planning.PlanDirection
import com.nexusflow.contracts.backendai.planning.ExplainPlansRequest
import com.nexusflow.contracts.backendai.planning.PlanExplanationFact
import com.nexusflow.contracts.backendai.planning.PlanForExplanation
import com.nexusflow.contracts.backendai.planning.CreatePlansRequest
import com.nexusflow.observability.LogFields
import com.nexusflow.observability.LogLevel
import com.nexusflow.observability.StructuredLogger
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class StructuredAiRetryLoggingTest {
    @Test
    fun `logs retry from plan composer repair decision`() =
        runBlocking {
            val logger = RecordingLogger()
            val provider = ScriptedProvider("""{"wrong":"shape"}""", compositionPayload())

            StructuredPlanComposer(provider, logger = logger).compose(planningContext())

            val retry = logger.entries.single()
            assertEquals("ai_request_retry", retry.event)
            assertEquals("plan_compose", retry.fields["operation"])
            assertEquals("2", retry.fields["next_attempt"])
            assertEquals("invalid_plan_proposal", retry.fields["failure_category"])
        }

    @Test
    fun `logs retry from plan explainer repair decision`() =
        runBlocking {
            val logger = RecordingLogger()
            val provider = ScriptedProvider("""{"wrong":"shape"}""", explanationPayload())

            StructuredPlanExplainer(provider, logger = logger).explain(explanationContext())

            val retry = logger.entries.single()
            assertEquals("ai_request_retry", retry.event)
            assertEquals("plan_explain", retry.fields["operation"])
            assertEquals("2", retry.fields["next_attempt"])
            assertEquals("explanation_invalid", retry.fields["failure_category"])
        }

    private fun planningContext(): CreatePlansRequest =
        CreatePlansRequest(
            planningRequestId = "planning-1",
            taskId = "task-1",
            taskRevision = 1,
            intent = "Find a movie",
            requirements = emptyList(),
            opportunities =
                listOf(
                    CandidateOpportunity(
                        id = "opp-1",
                        domain = "movie",
                        title = "Movie",
                        summary = "Screening",
                        location = "Futian",
                        activityMode = "out_of_home",
                        startsAt = Now,
                        endsAt = Later,
                        estimatedCostWholeUnits = 100,
                        currencyCode = "CNY",
                        commuteMinutes = 15,
                        sourceLabel = "test",
                        sourceUpdatedAt = Now,
                        validUntil = Later,
                    ),
                ),
            referenceTime = Now,
            timeZoneId = "Asia/Shanghai",
        )

    private fun explanationContext(): ExplainPlansRequest =
        ExplainPlansRequest(
            planningRequestId = "planning-1",
            plans =
                listOf(
                    PlanForExplanation(
                        planId = "plan-1",
                        direction = PlanDirection.BestMatch,
                        opportunityRefs = listOf("opp-1"),
                        facts = listOf(PlanExplanationFact("fact-1", "Movie fact")),
                    ),
                ),
            referenceTime = Now,
            timeZoneId = "Asia/Shanghai",
        )
}

private fun compositionPayload(): String =
    RetryJson.encodeToString(
        PlanCompositionPayload(
            drafts = listOf(PlanDraftPayload("best_match", listOf("opp-1"))),
        ),
    )

private fun explanationPayload(): String =
    RetryJson.encodeToString(
        PlanExplanationPayload(
            narratives =
                listOf(
                    PlanNarrativePayload(
                        planId = "plan-1",
                        title = "Movie",
                        summary = "A grounded plan.",
                        reasons = listOf(PlanNarrativePointPayload("Uses supplied fact.", listOf("fact-1"))),
                        tradeoffs = emptyList(),
                    ),
                ),
        ),
    )

private class ScriptedProvider(
    private vararg val outputs: String,
) : StructuredModelProvider {
    private var calls = 0

    override suspend fun generate(request: StructuredModelRequest): StructuredModelResult {
        val output = outputs[calls.coerceAtMost(outputs.lastIndex)]
        calls += 1
        return StructuredModelResult(
            outputText = output,
            metadata =
                StructuredModelResultMetadata(
                    provider = "test",
                    model = "test",
                    providerRequestId = "provider-request",
                    attemptCount = request.metadata.attemptNumber,
                    requestDiagnostics = request.metadata.diagnostics,
                ),
        )
    }
}

private class RecordingLogger : StructuredLogger {
    val entries = mutableListOf<Entry>()

    override fun log(
        level: LogLevel,
        component: String,
        event: String,
        fields: LogFields,
        cause: Throwable?,
    ) {
        entries += Entry(level, event, fields.values)
    }
}

private data class Entry(
    val level: LogLevel,
    val event: String,
    val fields: Map<String, String>,
)

private val RetryJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
    encodeDefaults = false
}

private val Now = Instant.parse("2026-08-29T00:00:00Z")
private val Later = Instant.parse("2026-08-29T04:00:00Z")
