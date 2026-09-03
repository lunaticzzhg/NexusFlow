package com.nexusflow.contracts.api

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class CreateTaskRequest(
    @SerialName("clientRequestId")
    val clientRequestId: String,
    @SerialName("message")
    val message: String,
    @SerialName("timeZoneId")
    val timeZoneId: String,
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
data class UpdateRequirementRequest(
    @SerialName("kind")
    val kind: RequirementKind,
    @SerialName("value")
    val value: RequirementValueResponse,
    @SerialName("strength")
    val strength: RequirementStrength,
)

@Serializable
data class TaskSummaryResponse(
    @SerialName("id")
    val id: String,
    @SerialName("intent")
    val intent: String,
    @SerialName("requirements")
    val requirements: List<RequirementSummaryResponse>,
    @SerialName("selectedPlanId")
    val selectedPlanId: String? = null,
    @SerialName("updatedAt")
    val updatedAt: Instant,
)

@Serializable
data class TaskDetailResponse(
    @SerialName("task")
    val task: TaskResponse,
    @SerialName("requirements")
    val requirements: List<RequirementResponse>,
    @SerialName("messages")
    val messages: List<TaskMessageResponse>,
    @SerialName("plans")
    val plans: List<PlanResponse>,
    @SerialName("planning")
    val planning: PlanningStatusResponse,
)

@Serializable
data class TaskResponse(
    @SerialName("id")
    val id: String,
    @SerialName("intent")
    val intent: String,
    @SerialName("revision")
    val revision: Long,
    @SerialName("selectedPlanId")
    val selectedPlanId: String? = null,
    @SerialName("createdAt")
    val createdAt: Instant,
    @SerialName("updatedAt")
    val updatedAt: Instant,
)

@Serializable
data class RequirementSummaryResponse(
    @SerialName("id")
    val id: String,
    @SerialName("label")
    val label: String,
    @SerialName("strength")
    val strength: RequirementStrength,
)

@Serializable
data class PlanningStatusResponse(
    @SerialName("status")
    val status: PlanningStatus,
)

@Serializable
enum class PlanningStatus {
    @SerialName("idle")
    Idle,
}

@Serializable
data class TaskMessageResponse(
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
data class RequirementResponse(
    @SerialName("id")
    val id: String,
    @SerialName("kind")
    val kind: RequirementKind,
    @SerialName("value")
    val value: RequirementValueResponse,
    @SerialName("strength")
    val strength: RequirementStrength,
    @SerialName("source")
    val source: RequirementSource,
    @SerialName("evidenceMessageId")
    val evidenceMessageId: String? = null,
    @SerialName("createdAt")
    val createdAt: Instant,
    @SerialName("updatedAt")
    val updatedAt: Instant,
)

@Serializable
enum class RequirementKind {
    @SerialName("time_window")
    TimeWindow,

    @SerialName("budget_limit")
    BudgetLimit,

    @SerialName("commute_limit")
    CommuteLimit,

    @SerialName("commute_preference")
    CommutePreference,

    @SerialName("location")
    Location,

    @SerialName("activity_domain")
    ActivityDomain,

    @SerialName("activity_mode")
    ActivityMode,

    @SerialName("topic")
    Topic,

    @SerialName("experience_preference")
    ExperiencePreference,
}

@Serializable
enum class RequirementStrength {
    @SerialName("must")
    Must,

    @SerialName("prefer")
    Prefer,
}

@Serializable
enum class RequirementSource {
    @SerialName("user_explicit")
    UserExplicit,

    @SerialName("system_derived")
    SystemDerived,
}

@Serializable
sealed class RequirementValueResponse {
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
    ) : RequirementValueResponse()

    @Serializable
    @SerialName("budget_limit")
    data class BudgetLimit(
        @SerialName("wholeUnits")
        val wholeUnits: Long,
        @SerialName("currencyCode")
        val currencyCode: String? = null,
    ) : RequirementValueResponse()

    @Serializable
    @SerialName("commute_limit")
    data class CommuteLimit(
        @SerialName("maxMinutes")
        val maxMinutes: Int,
    ) : RequirementValueResponse()

    @Serializable
    @SerialName("commute_preference")
    data class CommutePreference(
        @SerialName("value")
        val value: CommutePreferenceValue,
    ) : RequirementValueResponse()

    @Serializable
    @SerialName("location")
    data class Location(
        @SerialName("text")
        val text: String,
    ) : RequirementValueResponse()

    @Serializable
    @SerialName("activity_domain")
    data class ActivityDomain(
        @SerialName("value")
        val value: String,
    ) : RequirementValueResponse()

    @Serializable
    @SerialName("activity_mode")
    data class ActivityMode(
        @SerialName("value")
        val value: ActivityModeValue,
    ) : RequirementValueResponse()

    @Serializable
    @SerialName("topic")
    data class Topic(
        @SerialName("text")
        val text: String,
    ) : RequirementValueResponse()

    @Serializable
    @SerialName("experience_preference")
    data class ExperiencePreference(
        @SerialName("text")
        val text: String,
    ) : RequirementValueResponse()
}

@Serializable
enum class CommutePreferenceValue {
    @SerialName("prefer_shorter")
    PreferShorter,
}

@Serializable
enum class ActivityModeValue {
    @SerialName("at_home")
    AtHome,

    @SerialName("out_of_home")
    OutOfHome,
}
