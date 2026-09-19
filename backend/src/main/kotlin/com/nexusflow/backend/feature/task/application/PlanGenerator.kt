package com.nexusflow.backend.feature.task.application

import com.nexusflow.backend.feature.task.domain.ActivityModeValue
import com.nexusflow.backend.feature.task.domain.Opportunity
import com.nexusflow.backend.feature.task.domain.TaskDetail
import com.nexusflow.contracts.backendai.common.AiCapabilityException
import com.nexusflow.contracts.backendai.planning.CandidateOpportunity as AiCandidateOpportunity
import com.nexusflow.contracts.backendai.planning.CandidateSourceRef as AiCandidateSourceRef
import com.nexusflow.contracts.backendai.planning.CreatePlansRequest as AiCreatePlansRequest
import com.nexusflow.contracts.backendai.planning.PlanComposer
import com.nexusflow.contracts.backendai.planning.PlanProposal as AiPlanProposal
import kotlinx.coroutines.CancellationException
import java.time.Instant

internal class PlanGenerator(
    private val planComposer: PlanComposer?,
    private val timeZoneId: String,
) {
    suspend fun generate(
        detail: TaskDetail,
        opportunities: List<Opportunity>,
        now: Instant,
        optionalContext: PlanningOptionalContext,
    ): List<AiPlanProposal> {
        val composer = planComposer ?: throw TaskDependencyUnavailableException("Planning is temporarily unavailable")
        return try {
            composer.compose(
                AiCreatePlansRequest(
                    planningRequestId = "plan-${detail.task.id.value}-${detail.task.revision}",
                    taskId = detail.task.id.value.toString(),
                    taskRevision = detail.task.revision,
                    intent = detail.task.intent,
                    requirements = detail.requirements.map { it.toAiPlanningRequirement() },
                    opportunities = opportunities.map { it.toAiCandidateOpportunity() },
                    referenceTime = now.toContractInstant(),
                    timeZoneId = timeZoneId,
                    optionalContext = optionalContext.blocks.map { it.toAiPayload() },
                    diagnostics = optionalContext.diagnostics,
                ),
            ).drafts
        } catch (error: CancellationException) {
            throw error
        } catch (_: AiCapabilityException) {
            throw TaskDependencyUnavailableException("Planning is temporarily unavailable")
        }
    }
}

private fun Opportunity.toAiCandidateOpportunity(): AiCandidateOpportunity =
    AiCandidateOpportunity(
        id = id.value.toString(),
        domain = kind.name,
        title = title,
        summary = facts.summary,
        location = facts.location?.displayName,
        activityMode = when (facts.activityMode) {
            ActivityModeValue.AtHome -> "at_home"
            ActivityModeValue.OutOfHome -> "out_of_home"
            null -> "unknown"
        }.takeUnless { facts.activityMode == null },
        availability = facts.availability?.name,
        startsAt = facts.startTime?.toContractInstant(),
        endsAt = facts.endTime?.toContractInstant(),
        estimatedCostWholeUnits = facts.price?.wholeUnits,
        currencyCode = facts.price?.currencyCode,
        commuteMinutes = facts.commute?.minutes,
        sources = sources.take(MAX_AI_SOURCE_REFS).map { source ->
            AiCandidateSourceRef(
                label = source.label,
                uri = source.uri,
                sourceUpdatedAt = source.sourceUpdatedAt?.toContractInstant(),
                sourceId = source.sourceId,
                authority = source.authority.name,
                factKeys = source.factKeys.map { it.name }.sorted(),
            )
        },
        validUntil = validUntil?.toContractInstant(),
    )
