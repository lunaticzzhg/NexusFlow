package com.nexusflow.ai.understanding

import kotlinx.datetime.Instant

data class UnderstandingContext(
    val aiRequestId: String,
    val taskId: String,
    val taskVersion: Long,
    val currentGoal: String,
    val confirmedConstraints: List<ConfirmedConstraint>,
    val currentMessage: String,
    val referenceTime: Instant,
    val timeZoneId: String,
)

data class ConfirmedConstraint(
    val kind: ConstraintKind,
    val value: ConstraintValue,
    val strength: ConstraintStrength,
)

data class UnderstandingOutcome(
    val userIntent: UserIntent,
    val extractedConstraints: List<ConstraintCandidate>,
    val missingInformation: List<String>,
    val clarificationNeeded: Boolean,
    val assistantMessageDraft: String?,
    val metadata: UnderstandingMetadata,
)

enum class UserIntent {
    PlanRequest,
    ConstraintUpdate,
    ClarificationResponse,
}

data class ConstraintCandidate(
    val kind: ConstraintKind,
    val value: ConstraintValue,
    val strength: ConstraintStrength,
    val evidenceText: String,
)

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

data class UnderstandingMetadata(
    val provider: String,
    val model: String,
    val promptVersion: String,
    val providerRequestId: String?,
    val attemptCount: Int,
)

sealed class UserMessageUnderstandingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class ProviderUnavailableException(cause: Throwable? = null) :
    UserMessageUnderstandingException("Understanding provider is unavailable", cause)

class ProviderTimeoutException(cause: Throwable? = null) :
    UserMessageUnderstandingException("Understanding provider timed out", cause)

class ProviderRefusedException :
    UserMessageUnderstandingException("Understanding provider refused the request")

class InvalidStructuredOutputException(message: String, cause: Throwable? = null) :
    UserMessageUnderstandingException(message, cause)
