package com.nexusflow.backend.feature.task.domain

import java.time.Instant

class PlanValidator {
    fun validate(
        context: PlanningContextSnapshot,
        drafts: List<PlanDraft>,
    ): PlanValidationResult {
        val opportunitiesById = context.opportunities.associateBy { it.id }
        val failures = mutableListOf<PlanValidationFailure>()
        val seenSignatures = mutableSetOf<List<String>>()
        val plans = mutableListOf<Plan>()

        drafts.forEach { draft ->
            val candidateFailures = validateDraft(context, opportunitiesById, draft, seenSignatures)
            if (candidateFailures.isEmpty()) {
                plans += materializePlan(context, draft, opportunitiesById)
            } else {
                failures += candidateFailures
            }
        }

        return if (failures.isEmpty() && plans.isNotEmpty()) {
            PlanValidationResult.Accepted(plans)
        } else {
            PlanValidationResult.Rejected(failures.ifEmpty {
                listOf(PlanValidationFailure(null, PlanValidationFailureCode.EmptyPlanSet))
            })
        }
    }

    private fun validateDraft(
        context: PlanningContextSnapshot,
        opportunitiesById: Map<OpportunityId, Opportunity>,
        draft: PlanDraft,
        seenSignatures: MutableSet<List<String>>,
    ): List<PlanValidationFailure> {
        val failures = mutableListOf<PlanValidationFailure>()
        if (draft.opportunityRefs.isEmpty()) {
            failures += draft.failure(PlanValidationFailureCode.EmptyOpportunityRefs)
        }
        val referenced = draft.opportunityRefs.mapNotNull { opportunitiesById[it] }
        if (draft.opportunityRefs.any { it !in opportunitiesById }) {
            failures += draft.failure(PlanValidationFailureCode.UnknownOpportunityRef)
        }
        if (referenced.isEmpty()) {
            failures += draft.failure(PlanValidationFailureCode.EmptyTimeline)
        }
        if (!timelineIsOrdered(referenced)) {
            failures += draft.failure(PlanValidationFailureCode.TimelineOutOfOrder)
        }
        referenced.forEach { opportunity ->
            failures += validateOpportunity(context, draft, opportunity)
        }
        failures += validatePlanLevelMustRequirements(context, draft, referenced)

        val validUntil = referenced.mapNotNull { it.validUntil }.minOrNull()
        if (validUntil == null) {
            failures += draft.failure(PlanValidationFailureCode.MissingValidUntil)
        } else if (!validUntil.isAfter(context.referenceTime)) {
            failures += draft.failure(PlanValidationFailureCode.ExpiredCandidate)
        }

        val signature = draft.opportunityRefs.map { it.value.toString() }.distinct().sorted()
        if (signature.isNotEmpty() && !seenSignatures.add(signature)) {
            failures += draft.failure(PlanValidationFailureCode.DuplicatePlan)
        }

        return failures.distinct()
    }

    private fun validatePlanLevelMustRequirements(
        context: PlanningContextSnapshot,
        draft: PlanDraft,
        opportunities: List<Opportunity>,
    ): List<PlanValidationFailure> {
        val totalCost = opportunities.mapNotNull { it.facts.price?.wholeUnits }.sum()
        return context.mustRequirements.mapNotNull { requirement ->
            val value = requirement.value
            if (value is RequirementValue.BudgetLimit && totalCost > value.wholeUnits) {
                draft.failure(PlanValidationFailureCode.MustBudgetLimitRejected)
            } else {
                null
            }
        }
    }

    private fun validateOpportunity(
        context: PlanningContextSnapshot,
        draft: PlanDraft,
        opportunity: Opportunity,
    ): List<PlanValidationFailure> {
        val failures = mutableListOf<PlanValidationFailure>()
        val start = opportunity.facts.startTime
        val end = opportunity.facts.endTime
        if (start == null || end == null || !start.isBefore(end)) {
            failures += draft.failure(PlanValidationFailureCode.InvalidTimelineBounds)
        }
        if (opportunity.validUntil == null || !opportunity.validUntil.isAfter(context.referenceTime)) {
            failures += draft.failure(PlanValidationFailureCode.ExpiredCandidate)
        }
        if (opportunity.sources.isEmpty() || opportunity.sources.any { it.label.isBlank() }) {
            failures += draft.failure(PlanValidationFailureCode.MissingSourceRefs)
        }
        if (opportunity.facts.availability == AvailabilityFact.Unavailable) {
            failures += draft.failure(PlanValidationFailureCode.UnavailableOpportunity)
        }

        context.mustRequirements.forEach { requirement ->
            when (val value = requirement.value) {
                is RequirementValue.TimeWindow ->
                    if (!opportunity.within(value)) {
                        failures += draft.failure(PlanValidationFailureCode.MustTimeWindowRejected)
                    }
                is RequirementValue.BudgetLimit -> Unit
                is RequirementValue.CommuteLimit ->
                    if (opportunity.facts.commute != null && opportunity.facts.commute.minutes > value.maxMinutes) {
                        failures += draft.failure(PlanValidationFailureCode.MustCommuteLimitRejected)
                    }
                is RequirementValue.ActivityMode ->
                    if (opportunity.facts.activityMode != value.value) {
                        failures += draft.failure(PlanValidationFailureCode.MustActivityModeRejected)
                    }
                is RequirementValue.Location ->
                    if (!opportunity.matchesLocation(value.text)) {
                        failures += draft.failure(PlanValidationFailureCode.MustLocationRejected)
                    }
                is RequirementValue.ActivityDomain ->
                    if (!opportunity.kind.matchesDomainText(value.value)) {
                        failures += draft.failure(PlanValidationFailureCode.MustActivityDomainRejected)
                    }
                is RequirementValue.Topic ->
                    if (value.text.normalizedPlanningToken() !in opportunity.topicTags()) {
                        failures += draft.failure(PlanValidationFailureCode.MustTopicRejected)
                    }
                is RequirementValue.CommutePreference,
                is RequirementValue.ExperiencePreference,
                -> Unit
            }
        }

        return failures
    }

    private fun materializePlan(
        context: PlanningContextSnapshot,
        draft: PlanDraft,
        opportunitiesById: Map<OpportunityId, Opportunity>,
    ): Plan {
        val opportunities = draft.opportunityRefs.map { opportunitiesById.getValue(it) }
        val validUntil = opportunities.mapNotNull { it.validUntil }.min()
        val totalCost = opportunities.mapNotNull { it.facts.price?.wholeUnits }.sum()
        val currencyCode = opportunities.mapNotNull { it.facts.price?.currencyCode }.firstOrNull()
        return Plan(
            id = draft.id,
            taskId = context.task.id,
            revision = context.task.revision,
            direction = draft.direction,
            title = "${draft.direction.displayLabel()}: ${opportunities.first().title}",
            summary = opportunities.first().facts.summary ?: opportunities.first().title,
            timeline = opportunities.map { opportunity ->
                PlanTimelineItem(
                    title = opportunity.title,
                    startAt = opportunity.facts.startTime,
                    endAt = opportunity.facts.endTime,
                    location = opportunity.facts.location?.displayName,
                )
            },
            estimatedCost = if (totalCost > 0) PlanEstimatedCost(totalCost, currencyCode) else null,
            commuteMinutes = opportunities.mapNotNull { it.facts.commute?.minutes }.maxOrNull(),
            requirementEvaluations = context.requirements.map {
                RequirementEvaluation(it.id, RequirementEvaluationResult.Satisfied)
            },
            tradeoffs = emptyList(),
            reasons = emptyList(),
            sourceRefs =
                opportunities
                    .flatMap { it.sources }
                    .distinct()
                    .map { source ->
                        PlanSourceRef(
                            label = source.label,
                            uri = source.uri,
                            sourceUpdatedAt = source.sourceUpdatedAt,
                        )
                    },
            opportunityRefs = draft.opportunityRefs.distinct(),
            validUntil = validUntil,
            createdAt = context.referenceTime,
        )
    }

    private val PlanningContextSnapshot.mustRequirements: List<Requirement>
        get() = requirements.filter { it.strength == RequirementStrength.Must }

    private fun Opportunity.within(window: RequirementValue.TimeWindow): Boolean {
        val start = facts.startTime ?: return false
        val end = facts.endTime ?: return false
        val startsAfterLowerBound = window.startAt == null || !start.isBefore(window.startAt)
        val endsBeforeUpperBound = window.endAt == null || !end.isAfter(window.endAt)
        return startsAfterLowerBound && endsBeforeUpperBound
    }

    private fun Opportunity.matchesLocation(text: String): Boolean {
        val normalized = text.normalizedPlanningToken()
        val location = facts.location ?: return normalized.isBlank()
        return normalized.isBlank() ||
            location.normalizedName.normalizedPlanningToken() == normalized ||
            normalized in locationTags()
    }

    private fun Opportunity.topicTags(): Set<String> =
        (facts.attributes["topics"] as? FactValue.Text)
            ?.value
            ?.split(",")
            ?.mapTo(mutableSetOf()) { it.normalizedPlanningToken() }
            ?: emptySet()

    private fun Opportunity.locationTags(): Set<String> =
        (facts.attributes["locations"] as? FactValue.Text)
            ?.value
            ?.split(",")
            ?.mapTo(mutableSetOf()) { it.normalizedPlanningToken() }
            ?: emptySet()

    private fun timelineIsOrdered(opportunities: List<Opportunity>): Boolean =
        opportunities.zipWithNext().all { (left, right) ->
            val leftStart = left.facts.startTime ?: return@all false
            val rightStart = right.facts.startTime ?: return@all false
            !leftStart.isAfter(rightStart)
        }

    private fun PlanDirection.displayLabel(): String =
        when (this) {
            PlanDirection.BestMatch -> "Best match"
            PlanDirection.MoreRelaxed -> "More relaxed"
            PlanDirection.NewExperience -> "New experience"
        }

    private fun PlanDraft.failure(code: PlanValidationFailureCode): PlanValidationFailure =
        PlanValidationFailure(draftId = id, code = code)
}

data class PlanningContextSnapshot(
    val task: Task,
    val requirements: List<Requirement>,
    val opportunities: List<Opportunity>,
    val referenceTime: Instant,
)

data class PlanDraft(
    val id: PlanId,
    val direction: PlanDirection,
    val opportunityRefs: List<OpportunityId>,
)

sealed interface PlanValidationResult {
    data class Accepted(
        val plans: List<Plan>,
    ) : PlanValidationResult

    data class Rejected(
        val failures: List<PlanValidationFailure>,
    ) : PlanValidationResult
}

data class PlanValidationFailure(
    val draftId: PlanId?,
    val code: PlanValidationFailureCode,
)

enum class PlanValidationFailureCode {
    EmptyPlanSet,
    EmptyOpportunityRefs,
    UnknownOpportunityRef,
    MissingSourceRefs,
    UnavailableOpportunity,
    EmptyTimeline,
    InvalidTimelineBounds,
    TimelineOutOfOrder,
    MissingValidUntil,
    ExpiredCandidate,
    MustTimeWindowRejected,
    MustBudgetLimitRejected,
    MustCommuteLimitRejected,
    MustActivityModeRejected,
    MustLocationRejected,
    MustActivityDomainRejected,
    MustTopicRejected,
    DuplicatePlan,
}
