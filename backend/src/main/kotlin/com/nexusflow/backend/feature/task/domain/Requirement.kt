package com.nexusflow.backend.feature.task.domain

import java.time.Instant

enum class RequirementKind {
    TimeWindow,
    BudgetLimit,
    CommuteLimit,
    CommutePreference,
    Location,
    ActivityDomain,
    ActivityMode,
    Topic,
    ExperiencePreference,
}

enum class RequirementStrength {
    Must,
    Prefer,
}

enum class RequirementSource {
    UserExplicit,
    SystemDerived,
}

data class Requirement(
    val id: RequirementId,
    val taskId: TaskId,
    val kind: RequirementKind,
    val value: RequirementValue,
    val strength: RequirementStrength,
    val source: RequirementSource,
    val evidence: RequirementEvidence?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

sealed interface RequirementEvidence {
    data class UserMessage(
        val messageId: MessageId,
    ) : RequirementEvidence
}

sealed interface RequirementValue {
    data class TimeWindow(
        val startAt: Instant?,
        val endAt: Instant?,
        val timeZoneId: String,
        val originalText: String,
    ) : RequirementValue

    data class BudgetLimit(
        val wholeUnits: Long,
        val currencyCode: String?,
    ) : RequirementValue

    data class CommuteLimit(
        val maxMinutes: Int,
    ) : RequirementValue

    data class CommutePreference(
        val value: CommutePreferenceValue,
    ) : RequirementValue

    data class Location(
        val text: String,
    ) : RequirementValue

    data class ActivityDomain(
        val value: String,
    ) : RequirementValue

    data class ActivityMode(
        val value: ActivityModeValue,
    ) : RequirementValue

    data class Topic(
        val text: String,
    ) : RequirementValue

    data class ExperiencePreference(
        val text: String,
    ) : RequirementValue
}

enum class CommutePreferenceValue {
    PreferShorter,
}

enum class ActivityModeValue {
    AtHome,
    OutOfHome,
}
