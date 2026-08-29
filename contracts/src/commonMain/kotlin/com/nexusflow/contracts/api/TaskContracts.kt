package com.nexusflow.contracts.api

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class TaskState {
    @SerialName("draft")
    Draft,

    @SerialName("collecting_constraints")
    CollectingConstraints,

    @SerialName("planning")
    Planning,

    @SerialName("waiting_for_approval")
    WaitingForApproval,

    @SerialName("executing")
    Executing,

    @SerialName("needs_attention")
    NeedsAttention,

    @SerialName("completed")
    Completed,

    @SerialName("cancelled")
    Cancelled,
}

@Serializable
data class CreateTaskRequest(
    @SerialName("clientRequestId")
    val clientRequestId: String,
    @SerialName("goal")
    val goal: String,
)

@Serializable
data class SendTaskMessageRequest(
    @SerialName("clientMessageId")
    val clientMessageId: String,
    @SerialName("text")
    val text: String,
    @SerialName("timeZoneId")
    val timeZoneId: String,
)

@Serializable
data class TaskSummaryResponse(
    @SerialName("id")
    val id: String,
    @SerialName("title")
    val title: String,
    @SerialName("currentGoal")
    val currentGoal: String,
    @SerialName("state")
    val state: TaskState,
    @SerialName("updatedAt")
    val updatedAt: Instant,
)

@Serializable
data class TaskDetailResponse(
    @SerialName("id")
    val id: String,
    @SerialName("title")
    val title: String,
    @SerialName("currentGoal")
    val currentGoal: String,
    @SerialName("state")
    val state: TaskState,
    @SerialName("version")
    val version: Long,
    @SerialName("constraints")
    val constraints: List<ConstraintResponse>,
    @SerialName("messages")
    val messages: List<ConversationMessageResponse>,
    @SerialName("plans")
    val plans: List<PlanResponse>,
    @SerialName("selectedPlanId")
    val selectedPlanId: String? = null,
    @SerialName("createdAt")
    val createdAt: Instant,
    @SerialName("updatedAt")
    val updatedAt: Instant,
)

@Serializable
data class ConversationMessageResponse(
    @SerialName("id")
    val id: String,
    @SerialName("role")
    val role: MessageRole,
    @SerialName("content")
    val content: String,
    @SerialName("clientMessageId")
    val clientMessageId: String? = null,
    @SerialName("aiRequestId")
    val aiRequestId: String? = null,
    @SerialName("understoodAt")
    val understoodAt: Instant? = null,
    @SerialName("createdAt")
    val createdAt: Instant,
)

@Serializable
enum class MessageRole {
    @SerialName("user")
    User,

    @SerialName("assistant")
    Assistant,
}

@Serializable
data class ConstraintResponse(
    @SerialName("id")
    val id: String,
    @SerialName("kind")
    val kind: ConstraintKind,
    @SerialName("value")
    val value: ConstraintValueResponse,
    @SerialName("strength")
    val strength: ConstraintStrength,
    @SerialName("source")
    val source: ConstraintSource,
    @SerialName("evidenceMessageId")
    val evidenceMessageId: String,
    @SerialName("confirmedAt")
    val confirmedAt: Instant,
    @SerialName("createdAt")
    val createdAt: Instant,
    @SerialName("updatedAt")
    val updatedAt: Instant,
)

@Serializable
enum class ConstraintKind {
    @SerialName("time_window")
    TimeWindow,

    @SerialName("budget_limit")
    BudgetLimit,

    @SerialName("commute_limit")
    CommuteLimit,

    @SerialName("location")
    Location,

    @SerialName("activity_domain")
    ActivityDomain,

    @SerialName("topic")
    Topic,

    @SerialName("experience_preference")
    ExperiencePreference,
}

@Serializable
enum class ConstraintStrength {
    @SerialName("hard")
    Hard,

    @SerialName("soft")
    Soft,
}

@Serializable
enum class ConstraintSource {
    @SerialName("user_explicit")
    UserExplicit,

    @SerialName("accepted_suggestion")
    AcceptedSuggestion,

    @SerialName("opportunity_context")
    OpportunityContext,

    @SerialName("system_derived")
    SystemDerived,
}

@Serializable
sealed class ConstraintValueResponse {
    @Serializable
    @SerialName("time_window")
    data class TimeWindow(
        @SerialName("startAt")
        val startAt: Instant? = null,
        @SerialName("endAt")
        val endAt: Instant? = null,
        @SerialName("timeZoneId")
        val timeZoneId: String,
        @SerialName("originalText")
        val originalText: String,
    ) : ConstraintValueResponse()

    @Serializable
    @SerialName("budget_limit")
    data class BudgetLimit(
        @SerialName("wholeUnits")
        val wholeUnits: Long,
        @SerialName("currencyCode")
        val currencyCode: String? = null,
    ) : ConstraintValueResponse()

    @Serializable
    @SerialName("commute_limit")
    data class CommuteLimit(
        @SerialName("maxMinutes")
        val maxMinutes: Int,
    ) : ConstraintValueResponse()

    @Serializable
    @SerialName("location")
    data class Location(
        @SerialName("text")
        val text: String,
    ) : ConstraintValueResponse()

    @Serializable
    @SerialName("activity_domain")
    data class ActivityDomain(
        @SerialName("value")
        val value: String,
    ) : ConstraintValueResponse()

    @Serializable
    @SerialName("topic")
    data class Topic(
        @SerialName("text")
        val text: String,
    ) : ConstraintValueResponse()

    @Serializable
    @SerialName("experience_preference")
    data class ExperiencePreference(
        @SerialName("text")
        val text: String,
    ) : ConstraintValueResponse()
}
