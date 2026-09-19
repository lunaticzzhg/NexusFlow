package com.nexusflow.backend.feature.conversation.application

import com.nexusflow.backend.feature.conversation.domain.ConversationAnswerResultCommitter
import com.nexusflow.backend.feature.conversation.domain.ConsumeConversationAnswerResultCommand
import com.nexusflow.backend.feature.responserun.domain.ConsumeResponseRunResult
import com.nexusflow.backend.feature.responserun.domain.ResponseRunFailureCategory
import com.nexusflow.backend.feature.responserun.domain.ResponseRunResult
import com.nexusflow.backend.feature.responserun.domain.ResponseRunResultPayload
import com.nexusflow.backend.feature.responserun.domain.FactValuePayload
import com.nexusflow.backend.feature.responserun.domain.OpportunityPayload
import com.nexusflow.backend.feature.responserun.domain.PlanPayload
import com.nexusflow.backend.feature.responserun.domain.RequirementValuePayload
import com.nexusflow.backend.feature.responserun.domain.RequirementWritePayload
import com.nexusflow.backend.feature.task.domain.ActivityModeValue
import com.nexusflow.backend.feature.task.domain.AvailabilityFact
import com.nexusflow.backend.feature.task.domain.CommutePreferenceValue
import com.nexusflow.backend.feature.task.domain.DurationFact
import com.nexusflow.backend.feature.task.domain.FactValue
import com.nexusflow.backend.feature.task.domain.LocationFact
import com.nexusflow.backend.feature.task.domain.MoneyFact
import com.nexusflow.backend.feature.task.domain.Opportunity
import com.nexusflow.backend.feature.task.domain.OpportunityFactKey
import com.nexusflow.backend.feature.task.domain.OpportunityFacts
import com.nexusflow.backend.feature.task.domain.OpportunityId
import com.nexusflow.backend.feature.task.domain.OpportunityKind
import com.nexusflow.backend.feature.task.domain.PersistPlansCommand
import com.nexusflow.backend.feature.task.domain.PersistPlansResult
import com.nexusflow.backend.feature.task.domain.Plan
import com.nexusflow.backend.feature.task.domain.PlanDirection
import com.nexusflow.backend.feature.task.domain.PlanEstimatedCost
import com.nexusflow.backend.feature.task.domain.PlanId
import com.nexusflow.backend.feature.task.domain.PlanningResultCommitter
import com.nexusflow.backend.feature.task.domain.PlanSourceRef
import com.nexusflow.backend.feature.task.domain.PlanTimelineItem
import com.nexusflow.backend.feature.task.domain.ConsumePlanningResultCommand
import com.nexusflow.backend.feature.task.domain.ConsumePlanningUnderstandingCommand
import com.nexusflow.backend.feature.task.domain.RequirementEvaluation
import com.nexusflow.backend.feature.task.domain.RequirementEvaluationResult
import com.nexusflow.backend.feature.task.domain.RequirementId
import com.nexusflow.backend.feature.task.domain.RequirementKind
import com.nexusflow.backend.feature.task.domain.RequirementStrength
import com.nexusflow.backend.feature.task.domain.RequirementValue
import com.nexusflow.backend.feature.task.domain.RequirementWrite
import com.nexusflow.backend.feature.task.domain.SourceAuthority
import com.nexusflow.backend.feature.task.domain.SourceRef
import com.nexusflow.backend.feature.task.domain.TaskId
import java.time.Clock
import java.time.Instant
import java.util.UUID

class ResponseRunResultConsumer(
    private val conversationAnswerCommitter: ConversationAnswerResultCommitter,
    private val planningResultCommitter: PlanningResultCommitter,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun consume(result: ResponseRunResult): ConsumeResponseRunResult =
        when (val payload = result.payload) {
            is ResponseRunResultPayload.ConversationAnswer ->
                conversationAnswerCommitter.consumeConversationAnswerResult(
                    ConsumeConversationAnswerResultCommand(
                        result = result,
                        payload = payload,
                        now = clock.instant(),
                    ),
                )
            is ResponseRunResultPayload.PlanningUnderstanding -> consumePlanningUnderstanding(result, payload)
            is ResponseRunResultPayload.PlanningResult -> consumePlanningResult(result, payload)
        }

    private suspend fun consumePlanningUnderstanding(
        result: ResponseRunResult,
        payload: ResponseRunResultPayload.PlanningUnderstanding,
    ): ConsumeResponseRunResult =
        planningResultCommitter.consumePlanningUnderstanding(
            ConsumePlanningUnderstandingCommand(
                result = result,
                payload = payload,
                newTaskId = TaskId(UUID.randomUUID()),
                requirements = payload.requirements.map { it.toRequirementWrite() },
                removedRequirementKinds = payload.removedRequirementKinds.map { RequirementKind.valueOf(it) },
                now = clock.instant(),
            ),
        )

    private suspend fun consumePlanningResult(
        result: ResponseRunResult,
        payload: ResponseRunResultPayload.PlanningResult,
    ): ConsumeResponseRunResult =
        planningResultCommitter.consumePlanningResult(
            ConsumePlanningResultCommand(
                result = result,
                payload = payload,
                opportunities = payload.opportunities.map { it.toOpportunity() },
                plans = payload.plans.map { it.toPlan() },
                failureCategory = if (payload.outcome == "Unavailable") ResponseRunFailureCategory.ProviderTemporary else null,
                now = clock.instant(),
            ),
        )

    private fun RequirementWritePayload.toRequirementWrite(): RequirementWrite =
        RequirementWrite(
            id = RequirementId(UUID.fromString(id)),
            kind = RequirementKind.valueOf(kind),
            value = value.toRequirementValue(),
            strength = RequirementStrength.valueOf(strength),
        )

    private fun RequirementValuePayload.toRequirementValue(): RequirementValue =
        when (this) {
            is RequirementValuePayload.TimeWindow -> RequirementValue.TimeWindow(
                startAt = startAt?.let(Instant::parse),
                endAt = endAt?.let(Instant::parse),
                timeZoneId = timeZoneId,
                originalText = originalText,
            )
            is RequirementValuePayload.BudgetLimit -> RequirementValue.BudgetLimit(wholeUnits, currencyCode)
            is RequirementValuePayload.CommuteLimit -> RequirementValue.CommuteLimit(maxMinutes)
            is RequirementValuePayload.CommutePreference -> RequirementValue.CommutePreference(CommutePreferenceValue.valueOf(value))
            is RequirementValuePayload.Location -> RequirementValue.Location(text)
            is RequirementValuePayload.ActivityDomain -> RequirementValue.ActivityDomain(value)
            is RequirementValuePayload.ActivityMode -> RequirementValue.ActivityMode(ActivityModeValue.valueOf(value))
            is RequirementValuePayload.Topic -> RequirementValue.Topic(text)
            is RequirementValuePayload.ExperiencePreference -> RequirementValue.ExperiencePreference(text)
        }

    private fun OpportunityPayload.toOpportunity(): Opportunity =
        Opportunity(
            id = OpportunityId(UUID.fromString(id)),
            provider = provider,
            externalKey = externalKey,
            kind = OpportunityKind.valueOf(kind),
            title = title,
            facts = OpportunityFacts(
                summary = facts.summary,
                startTime = facts.startTime?.let(Instant::parse),
                endTime = facts.endTime?.let(Instant::parse),
                location = facts.location?.let { LocationFact(it.displayName, it.normalizedName) },
                activityMode = facts.activityMode?.let(ActivityModeValue::valueOf),
                price = facts.price?.let { MoneyFact(it.wholeUnits, it.currencyCode) },
                commute = facts.commute?.let { DurationFact(it.minutes) },
                availability = facts.availability?.let(AvailabilityFact::valueOf),
                attributes = facts.attributes.mapValues { (_, value) -> value.toFactValue() },
            ),
            sources = sources.map { source ->
                SourceRef(
                    label = source.label,
                    uri = source.uri,
                    sourceUpdatedAt = source.sourceUpdatedAt?.let(Instant::parse),
                    sourceId = source.sourceId,
                    authority = SourceAuthority.valueOf(source.authority),
                    factKeys = source.factKeys.mapTo(mutableSetOf()) { OpportunityFactKey.valueOf(it) },
                )
            },
            observedAt = Instant.parse(observedAt),
            validUntil = validUntil?.let(Instant::parse),
        )

    private fun FactValuePayload.toFactValue(): FactValue =
        when (this) {
            is FactValuePayload.Text -> FactValue.Text(value)
            is FactValuePayload.Number -> FactValue.Number(value)
            is FactValuePayload.Flag -> FactValue.Flag(value)
        }

    private fun PlanPayload.toPlan(): Plan =
        Plan(
            id = PlanId(UUID.fromString(id)),
            taskId = TaskId(UUID.fromString(taskId)),
            revision = revision,
            direction = PlanDirection.valueOf(direction),
            title = title,
            summary = summary,
            timeline = timeline.map { item ->
                PlanTimelineItem(
                    title = item.title,
                    startAt = item.startAt?.let(Instant::parse),
                    endAt = item.endAt?.let(Instant::parse),
                    location = item.location,
                )
            },
            estimatedCost = estimatedCost?.let { PlanEstimatedCost(it.wholeUnits, it.currencyCode) },
            commuteMinutes = commuteMinutes,
            requirementEvaluations = requirementEvaluations.map { evaluation ->
                RequirementEvaluation(
                    requirementId = RequirementId(UUID.fromString(evaluation.requirementId)),
                    result = RequirementEvaluationResult.valueOf(evaluation.result),
                    explanation = evaluation.explanation,
                )
            },
            tradeoffs = tradeoffs,
            reasons = reasons,
            sourceRefs = sourceRefs.map { source ->
                PlanSourceRef(
                    label = source.label,
                    uri = source.uri,
                    sourceUpdatedAt = source.sourceUpdatedAt?.let(Instant::parse),
                )
            },
            opportunityRefs = opportunityRefs.map { OpportunityId(UUID.fromString(it)) },
            validUntil = validUntil?.let(Instant::parse),
            createdAt = Instant.parse(createdAt),
        )
}
