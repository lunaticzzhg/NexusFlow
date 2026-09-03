package com.nexusflow.app.feature.task.domain

import kotlinx.datetime.Instant
import kotlin.jvm.JvmInline

@JvmInline
value class TaskId(
    val value: String,
)

data class TaskSummary(
    val id: TaskId,
    val intent: String,
    val requirements: List<RequirementSummary>,
    val selectedPlanId: PlanId?,
)

data class RequirementSummary(
    val id: RequirementId,
    val label: String,
    val strength: RequirementStrength,
)

data class CreateTaskCommand(
    val creationRequestId: String,
    val requestText: String,
    val timeZoneId: String,
)

data class SendTaskMessageCommand(
    val taskId: TaskId,
    val clientMessageId: String,
    val text: String,
    val timeZoneId: String,
)

data class UpdateRequirementCommand(
    val taskId: TaskId,
    val requirementId: RequirementId,
    val kind: RequirementKind,
    val value: RequirementValue,
    val strength: RequirementStrength,
)

data class RemoveRequirementCommand(
    val taskId: TaskId,
    val requirementId: RequirementId,
)

data class SelectPlanCommand(
    val taskId: TaskId,
    val planId: PlanId,
)

data class TaskDetail(
    val id: TaskId,
    val intent: String,
    val revision: Long,
    val requirements: List<TaskRequirement>,
    val messages: List<TaskMessage>,
    val plans: List<TaskPlan>,
    val selectedPlanId: PlanId?,
    val planningState: PlanningState,
)

enum class PlanningState {
    Idle,
    Planning,
    Failed,
}

@JvmInline
value class RequirementId(
    val value: String,
)

@JvmInline
value class PlanId(
    val value: String,
)

data class TaskRequirement(
    val id: RequirementId,
    val kind: RequirementKind,
    val value: RequirementValue,
    val strength: RequirementStrength,
    val source: RequirementSource,
)

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

sealed interface RequirementValue {
    data class TimeWindow(
        val originalText: String,
        val timeZoneId: String,
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

    data class ActivityMode(
        val value: ActivityModeValue,
    ) : RequirementValue

    data class Text(
        val value: String,
    ) : RequirementValue
}

enum class CommutePreferenceValue {
    PreferShorter,
}

enum class ActivityModeValue {
    AtHome,
    OutOfHome,
}

data class TaskMessage(
    val role: MessageRole,
    val content: String,
)

enum class MessageRole {
    User,
    Assistant,
}

data class TaskPlan(
    val id: PlanId,
    val revision: Long,
    val direction: PlanDirection,
    val title: String,
    val summary: String,
    val timeline: List<PlanTimelineItem>,
    val estimatedCost: PlanEstimatedCost?,
    val commuteMinutes: Int?,
    val requirementEvaluations: List<RequirementEvaluation>,
    val tradeoffs: List<String>,
    val reasons: List<String>,
    val sourceRefs: List<PlanSourceRef>,
    val opportunityRefs: List<String>,
    val validUntil: Instant?,
)

enum class PlanDirection {
    BestMatch,
    MoreRelaxed,
    NewExperience,
}

data class RequirementEvaluation(
    val requirementId: RequirementId,
    val result: RequirementEvaluationResult,
    val explanation: String?,
)

enum class RequirementEvaluationResult {
    Satisfied,
    NotApplicable,
}

data class PlanTimelineItem(
    val title: String,
    val startAt: Instant?,
    val endAt: Instant?,
    val location: String?,
)

data class PlanEstimatedCost(
    val wholeUnits: Long,
    val currencyCode: String?,
)

data class PlanSourceRef(
    val label: String,
    val sourceUpdatedAt: Instant?,
    val uri: String?,
)

fun TaskPlan.isExpiredAt(now: Instant): Boolean = validUntil?.let { it <= now } ?: false
