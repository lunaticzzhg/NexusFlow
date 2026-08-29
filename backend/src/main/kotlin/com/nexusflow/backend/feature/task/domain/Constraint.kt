package com.nexusflow.backend.feature.task.domain

import java.time.Instant

enum class ConstraintKind {
    TimeWindow,
    BudgetLimit,
    CommuteLimit,
    Location,
    ActivityDomain,
    Topic,
    ExperiencePreference,
}

enum class ConstraintStrength {
    Hard,
    Soft,
}

enum class ConstraintSource {
    UserExplicit,
    AcceptedSuggestion,
    OpportunityContext,
    SystemDerived,
}

data class TaskConstraint(
    val id: ConstraintId,
    val taskId: TaskId,
    val kind: ConstraintKind,
    val value: ConstraintValue,
    val strength: ConstraintStrength,
    val source: ConstraintSource,
    val evidenceMessageId: MessageId,
    val confirmedAt: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
)

sealed interface ConstraintValue {
    data class TimeWindow(
        val startAt: Instant?,
        val endAt: Instant?,
        val timeZoneId: String,
        val originalText: String,
    ) : ConstraintValue

    data class BudgetLimit(
        val wholeUnits: Long,
        val currencyCode: String?,
    ) : ConstraintValue

    data class CommuteLimit(
        val maxMinutes: Int,
    ) : ConstraintValue

    data class Location(
        val text: String,
    ) : ConstraintValue

    data class ActivityDomain(
        val value: String,
    ) : ConstraintValue

    data class Topic(
        val text: String,
    ) : ConstraintValue

    data class ExperiencePreference(
        val text: String,
    ) : ConstraintValue
}
