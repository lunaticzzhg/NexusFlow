package com.nexusflow.backend.feature.task.application

import com.nexusflow.backend.feature.task.domain.ActivityModeValue
import com.nexusflow.backend.feature.task.domain.Opportunity
import com.nexusflow.backend.feature.task.domain.Plan
import com.nexusflow.backend.feature.task.domain.PlanDirection
import com.nexusflow.contracts.backendai.common.AiCapabilityException
import com.nexusflow.contracts.backendai.planning.ExplainPlansRequest as AiExplainPlansRequest
import com.nexusflow.contracts.backendai.planning.PlanDirection as AiPlanDirection
import com.nexusflow.contracts.backendai.planning.PlanExplainer
import com.nexusflow.contracts.backendai.planning.PlanExplanationFact as AiPlanExplanationFact
import com.nexusflow.contracts.backendai.planning.PlanForExplanation as AiPlanForExplanation
import com.nexusflow.contracts.backendai.planning.PlanNarrative as AiPlanNarrative
import kotlinx.coroutines.CancellationException
import java.time.Instant

internal class PlanExplanationService(
    private val planExplainer: PlanExplainer?,
    private val timeZoneId: String,
    private val logger: PlanningLogger,
) {
    suspend fun explain(
        requestId: String,
        plans: List<Plan>,
        opportunities: List<Opportunity>,
        now: Instant,
    ): List<Plan> {
        val explainer = planExplainer
            ?: return plans.also {
                logger.planningDegraded(requestId, plans.firstOrNull()?.revision, "explanation", "dependency_unavailable")
            }
        val explanationContext = AiExplainPlansRequest(
            planningRequestId = requestId,
            plans = plans.map { it.toAiPlanForExplanation(opportunities) },
            referenceTime = now.toContractInstant(),
            timeZoneId = timeZoneId,
        )
        val explanation = try {
            explainer.explain(explanationContext)
        } catch (error: CancellationException) {
            throw error
        } catch (_: AiCapabilityException) {
            logger.planningDegraded(requestId, plans.firstOrNull()?.revision, "explanation", "structured_model_exception")
            return plans
        }
        val factsByPlan = explanationContext.plans.associate { plan ->
            plan.planId to plan.facts.map { it.id }.toSet()
        }
        val narratives = explanation.narratives.associateBy { it.planId }
        return plans.map { plan ->
            val planId = plan.id.value.toString()
            val narrative = narratives[planId]
                ?: return@map plan.also {
                    logger.planningDegraded(requestId, plan.revision, "explanation", "missing_narrative")
                }
            if (narrative.hasInvalidFactRefs(factsByPlan.getValue(planId))) {
                return@map plan.also {
                    logger.planningDegraded(requestId, plan.revision, "explanation", "invalid_fact_reference")
                }
            }
            plan.copy(
                title = narrative.title,
                summary = narrative.summary,
                reasons = narrative.reasons.map { it.text },
                tradeoffs = narrative.tradeoffs.map { it.text },
            )
        }
    }
}

private fun AiPlanNarrative.hasInvalidFactRefs(allowedFactIds: Set<String>): Boolean {
    val referenced = (reasons + tradeoffs).flatMap { it.factIds }
    return referenced.any { it !in allowedFactIds }
}

private fun Plan.toAiPlanForExplanation(opportunities: List<Opportunity>): AiPlanForExplanation {
    val opportunitiesById = opportunities.associateBy { it.id }
    val facts = opportunityRefs.flatMap { opportunityId ->
        val opportunity = opportunitiesById.getValue(opportunityId)
        listOf(
            AiPlanExplanationFact("opportunity:${opportunity.id.value}:title", "Opportunity title: ${opportunity.title}"),
            AiPlanExplanationFact("opportunity:${opportunity.id.value}:time", "Runs from ${opportunity.facts.startTime} to ${opportunity.facts.endTime}"),
            AiPlanExplanationFact("opportunity:${opportunity.id.value}:location", "Location: ${opportunity.facts.location?.displayName.orEmpty()}"),
            AiPlanExplanationFact(
                "opportunity:${opportunity.id.value}:cost",
                "Estimated cost: ${opportunity.facts.price?.wholeUnits?.toString() ?: "unknown"}",
            ),
            AiPlanExplanationFact(
                "opportunity:${opportunity.id.value}:commute",
                "Commute minutes: ${opportunity.facts.commute?.minutes?.toString() ?: "unknown"}",
            ),
            AiPlanExplanationFact(
                "opportunity:${opportunity.id.value}:sources",
                "Sources: ${
                    opportunity.sources
                        .take(MAX_AI_SOURCE_REFS)
                        .joinToString("; ") { source ->
                            listOf(
                                source.label,
                                source.authority.name,
                                source.factKeys.joinToString(",") { it.name },
                                source.sourceUpdatedAt?.toString().orEmpty(),
                            ).filter(String::isNotBlank).joinToString(" ")
                        }
                }",
            ),
            AiPlanExplanationFact("opportunity:${opportunity.id.value}:validUntil", "Valid until ${opportunity.validUntil}"),
        )
    }
    return AiPlanForExplanation(
        planId = id.value.toString(),
        direction = direction.toAiDirection(),
        opportunityRefs = opportunityRefs.map { it.value.toString() },
        facts = facts,
    )
}

private fun PlanDirection.toAiDirection(): AiPlanDirection =
    when (this) {
        PlanDirection.BestMatch -> AiPlanDirection.BestMatch
        PlanDirection.MoreRelaxed -> AiPlanDirection.MoreRelaxed
        PlanDirection.NewExperience -> AiPlanDirection.NewExperience
    }
