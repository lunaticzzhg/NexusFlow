package com.nexusflow.app.feature.task.domain

import kotlin.jvm.JvmInline

@JvmInline
value class TaskId(
    val value: String,
)

data class TaskSummary(
    val id: TaskId,
    val title: String,
    val currentGoal: String,
    val state: TaskState,
)

data class CreateTaskCommand(
    val creationRequestId: String,
    val initialMessageId: String,
    val requestText: String,
    val timeZoneId: String,
)

data class SendTaskMessageCommand(
    val taskId: TaskId,
    val clientMessageId: String,
    val text: String,
    val timeZoneId: String,
)

data class GeneratePlansCommand(
    val taskId: TaskId,
    val clientRequestId: String,
)

data class SelectPlanCommand(
    val taskId: TaskId,
    val planId: PlanId,
)

data class TaskDetail(
    val id: TaskId,
    val title: String,
    val currentGoal: String,
    val state: TaskState,
    val version: Long,
    val constraints: List<TaskConstraint>,
    val messages: List<TaskMessage>,
    val plans: List<TaskPlan>,
    val selectedPlanId: PlanId?,
)

enum class TaskState {
    Draft,
    CollectingConstraints,
    Planning,
    WaitingForApproval,
    Executing,
    NeedsAttention,
    Completed,
    Cancelled,
}

@JvmInline
value class ConstraintId(
    val value: String,
)

@JvmInline
value class PlanId(
    val value: String,
)

data class TaskConstraint(
    val id: ConstraintId,
    val kind: ConstraintKind,
    val value: ConstraintValue,
    val strength: ConstraintStrength,
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
        val originalText: String,
        val timeZoneId: String,
    ) : ConstraintValue

    data class BudgetLimit(
        val wholeUnits: Long,
        val currencyCode: String?,
    ) : ConstraintValue

    data class CommuteLimit(
        val maxMinutes: Int,
    ) : ConstraintValue

    data class Text(
        val value: String,
    ) : ConstraintValue
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
    val title: String,
    val summary: String,
    val timeline: List<PlanTimelineItem>,
    val estimatedCost: PlanEstimatedCost?,
    val commuteMinutes: Int?,
    val tradeoffs: List<String>,
    val reasons: List<String>,
)

data class PlanTimelineItem(
    val title: String,
    val location: String?,
)

data class PlanEstimatedCost(
    val wholeUnits: Long,
    val currencyCode: String?,
)
