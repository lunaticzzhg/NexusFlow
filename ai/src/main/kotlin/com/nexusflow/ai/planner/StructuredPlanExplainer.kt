package com.nexusflow.ai.planner

import com.nexusflow.ai.provider.ExplanationInvalidException
import com.nexusflow.ai.provider.StructuredModelCapability
import com.nexusflow.ai.provider.StructuredModelProvider
import com.nexusflow.ai.provider.StructuredModelRequest
import com.nexusflow.ai.provider.StructuredModelRequestDiagnostics
import com.nexusflow.ai.provider.StructuredModelRequestMetadata
import com.nexusflow.ai.provider.StructuredOutputSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

class StructuredPlanExplainer(
    private val provider: StructuredModelProvider,
    private val json: Json = Json {
        ignoreUnknownKeys = false
        explicitNulls = false
        encodeDefaults = true
    },
) : PlanExplainer {
    override suspend fun explain(context: PlanExplanationContext): PlanExplanation {
        var attempt = 1
        while (true) {
            try {
                return requestOnce(context, attempt)
            } catch (error: RepairablePlanExplanationException) {
                if (attempt == MAX_ATTEMPTS) {
                    throw ExplanationInvalidException(error.message ?: "Plan explanation is invalid", error)
                }
                attempt += 1
            }
        }
    }

    private suspend fun requestOnce(
        context: PlanExplanationContext,
        attempt: Int,
    ): PlanExplanation {
        val userPayload = context.toPayload()
        val requestDiagnostics = context.toRequestDiagnostics(userPayload)
        val result = try {
            provider.generate(
                StructuredModelRequest(
                    systemPrompt = explainSystemPrompt(attempt),
                    userPayload = userPayload,
                    outputSchema = StructuredOutputSchema(EXPLAIN_PLANS_SCHEMA_NAME, ExplainPlansSchema),
                    metadata = StructuredModelRequestMetadata(
                        requestId = context.planningRequestId,
                        promptVersion = EXPLAIN_PLANS_PROMPT_VERSION,
                        capability = StructuredModelCapability.PlanExplanation,
                        attemptNumber = attempt,
                        diagnostics = requestDiagnostics,
                    ),
                ),
            )
        } catch (error: CancellationException) {
            throw error
        }
        val payload = try {
            json.decodeFromString<PlanExplanationPayload>(result.outputText)
        } catch (error: SerializationException) {
            throw RepairablePlanExplanationException("Plan explanation response was not valid structured output", error)
        }
        return payload.toExplanation(context, result.metadata.toPlanModelMetadata(EXPLAIN_PLANS_PROMPT_VERSION))
    }

    private fun PlanExplanationPayload.toExplanation(
        context: PlanExplanationContext,
        metadata: PlanModelMetadata,
    ): PlanExplanation {
        val plansById = context.plans.associateBy { it.planId }
        val narrativePlanIds = narratives.map { it.planId }
        if (narrativePlanIds.toSet() != plansById.keys || narrativePlanIds.size != narrativePlanIds.toSet().size) {
            throw RepairablePlanExplanationException("Plan explanation must return one narrative for every plan")
        }
        return PlanExplanation(
            narratives = narratives.map { narrative ->
                val plan = plansById[narrative.planId]
                    ?: throw RepairablePlanExplanationException("Plan narrative referenced an unknown plan")
                val factIds = plan.facts.map { it.id }.toSet()
                PlanNarrative(
                    planId = narrative.planId,
                    title = narrative.title.requireNarrativeText("title"),
                    summary = narrative.summary.requireNarrativeText("summary"),
                    reasons = narrative.reasons.map { it.toPoint(factIds) },
                    tradeoffs = narrative.tradeoffs.map { it.toPoint(factIds) },
                )
            },
            metadata = metadata,
        )
    }

    private fun PlanNarrativePointPayload.toPoint(allowedFactIds: Set<String>): PlanNarrativePoint {
        val cleanFactIds = factIds.map { it.trim() }
        if (text.isBlank()) {
            throw RepairablePlanExplanationException("Narrative point text must be nonblank")
        }
        if (cleanFactIds.any(String::isBlank) || cleanFactIds.any { it !in allowedFactIds }) {
            throw RepairablePlanExplanationException("Narrative point referenced an unknown fact")
        }
        return PlanNarrativePoint(text = text.trim(), factIds = cleanFactIds.distinct())
    }

    private fun String.requireNarrativeText(field: String): String =
        trim().takeIf(String::isNotBlank)
            ?: throw RepairablePlanExplanationException("Plan narrative $field must be nonblank")

    private fun explainSystemPrompt(attempt: Int): String {
        val repairInstruction = if (attempt > 1) {
            "\nRepair only JSON structure and factIds from the provided validated facts."
        } else {
            ""
        }
        return """
            Prompt version: $EXPLAIN_PLANS_PROMPT_VERSION

            Explain each validated plan using only coreContext.plans and their provided facts.
            Every reason and tradeoff must reference factIds from the same plan.
            Do not claim live calendar checks, real feeds, or facts not present in the input.$repairInstruction
        """.trimIndent()
    }

    private fun PlanExplanationContext.toPayload(): JsonObject =
        json.encodeToJsonElement(
            PlanExplanationModelPayload(
                request = PlanExplanationModelRequest(
                    referenceTime = referenceTime,
                    timeZoneId = timeZoneId,
                ),
                coreContext = PlanExplanationCoreContextPayload(
                    plans = plans.map { plan -> plan.toModelPayload() },
                ),
            ),
        ).jsonObject

    private fun PlanExplanationContext.toRequestDiagnostics(userPayload: JsonObject): StructuredModelRequestDiagnostics =
        StructuredModelRequestDiagnostics(
            fullUserPayloadSerializedChars = json.encodeToString(JsonObject.serializer(), userPayload).length,
        )

    private fun com.nexusflow.ai.provider.StructuredModelResultMetadata.toPlanModelMetadata(
        promptVersion: String,
    ): PlanModelMetadata =
        PlanModelMetadata(
            provider = provider,
            model = model,
            promptVersion = promptVersion,
            providerRequestId = providerRequestId,
            attemptCount = attemptCount,
            usage = usage,
            diagnostics = requestDiagnostics,
        )

    private fun PlanForExplanation.toModelPayload(): PlanForExplanationPayload =
        PlanForExplanationPayload(
            planId = planId,
            direction = direction.name,
            opportunityRefs = opportunityRefs,
            facts = facts.map { fact -> fact.toModelPayload() },
        )

    private fun PlanExplanationFact.toModelPayload(): PlanExplanationFactPayload =
        PlanExplanationFactPayload(
            id = id,
            text = text,
        )
}

private const val MAX_ATTEMPTS = 2

private class RepairablePlanExplanationException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
