package com.nexusflow.contracts.appbackend.conversation

import com.nexusflow.contracts.appbackend.plan.PlanResponse
import com.nexusflow.contracts.appbackend.task.MessageRole
import com.nexusflow.contracts.appbackend.task.PlanningStatusResponse
import com.nexusflow.contracts.appbackend.task.RequirementResponse
import com.nexusflow.contracts.appbackend.task.TaskResponse
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** App asks Backend to create a Conversation from one user message under a client idempotency key. */
@Serializable
data class CreateConversationRequest(
    @SerialName("clientRequestId")
    val clientRequestId: String,
    @SerialName("message")
    val message: String,
    @SerialName("timeZoneId")
    val timeZoneId: String,
)

/** Backend returns the newly created Conversation and its optional current Planning projection. */
@Serializable
data class CreateConversationResponse(
    @SerialName("conversation")
    val conversation: ConversationResponse,
    @SerialName("currentTask")
    val currentTask: ConversationCurrentTaskResponse? = null,
)

/** Backend returns the current authoritative Conversation detail projection for the App. */
@Serializable
data class ConversationDetailResponse(
    @SerialName("conversation")
    val conversation: ConversationResponse,
    @SerialName("currentTask")
    val currentTask: ConversationCurrentTaskResponse? = null,
)

/** App sends one new message to an existing Conversation under a client message idempotency key. */
@Serializable
data class SendConversationMessageRequest(
    @SerialName("clientMessageId")
    val clientMessageId: String,
    @SerialName("text")
    val text: String,
    @SerialName("timeZoneId")
    val timeZoneId: String,
)

/** Backend returns the updated Conversation and its optional current Planning projection. */
@Serializable
data class SendConversationMessageResponse(
    @SerialName("conversation")
    val conversation: ConversationResponse,
    @SerialName("currentTask")
    val currentTask: ConversationCurrentTaskResponse? = null,
)

@Serializable
data class ConversationResponse(
    @SerialName("id")
    val id: String,
    @SerialName("messages")
    val messages: List<ConversationMessageResponse>,
    @SerialName("responseRuns")
    val responseRuns: List<ResponseRunResponse> = emptyList(),
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
    @SerialName("turnIndex")
    val turnIndex: Long? = null,
    @SerialName("understoodAt")
    val understoodAt: Instant? = null,
    @SerialName("createdAt")
    val createdAt: Instant,
)

@Serializable
data class ResponseRunResponse(
    @SerialName("id")
    val id: String,
    @SerialName("userMessageId")
    val userMessageId: String,
    @SerialName("turnIndex")
    val turnIndex: Long,
    @SerialName("status")
    val status: ResponseRunStatusResponse,
    @SerialName("stage")
    val stage: ResponseRunStageResponse = ResponseRunStageResponse.Turn,
    @SerialName("attempt")
    val attempt: Int,
    @SerialName("retryable")
    val retryable: Boolean,
    @SerialName("assistantMessageId")
    val assistantMessageId: String? = null,
    @SerialName("failureCategory")
    val failureCategory: ResponseRunFailureCategoryResponse? = null,
    @SerialName("createdAt")
    val createdAt: Instant,
    @SerialName("updatedAt")
    val updatedAt: Instant,
    @SerialName("completedAt")
    val completedAt: Instant? = null,
)

@Serializable
enum class ResponseRunStageResponse {
    @SerialName("TURN")
    Turn,

    @SerialName("PLANNING")
    Planning,
}

@Serializable
enum class ResponseRunStatusResponse {
    @SerialName("QUEUED")
    Queued,

    @SerialName("PROCESSING")
    Processing,

    @SerialName("STREAMING")
    Streaming,

    @SerialName("COMPLETED")
    Completed,

    @SerialName("FAILED_RETRYABLE")
    FailedRetryable,

    @SerialName("FAILED")
    Failed,

    @SerialName("TIMED_OUT")
    TimedOut,

    @SerialName("CANCELLED")
    Cancelled,
}

@Serializable
enum class ResponseRunFailureCategoryResponse {
    @SerialName("PROVIDER_TEMPORARY")
    ProviderTemporary,

    @SerialName("AI_INVALID_RESULT")
    AiInvalidResult,

    @SerialName("WORKER_LOST")
    WorkerLost,

    @SerialName("RUN_TIMEOUT")
    RunTimeout,

    @SerialName("INTERNAL_INVARIANT")
    InternalInvariant,
}

@Serializable
data class ResponseRunSnapshotResponse(
    @SerialName("run")
    val run: ResponseRunResponse,
    @SerialName("conversation")
    val conversation: ConversationResponse,
    @SerialName("currentTask")
    val currentTask: ConversationCurrentTaskResponse? = null,
    @SerialName("streamAttempt")
    val streamAttempt: Int,
    @SerialName("lastSeq")
    val lastSeq: Long,
    @SerialName("partialText")
    val partialText: String,
    @SerialName("activities")
    val activities: List<ResponseRunActivityResponse> = emptyList(),
    @SerialName("realtimeSnapshotAvailable")
    val realtimeSnapshotAvailable: Boolean,
)

@Serializable
data class ResponseRunActivityResponse(
    @SerialName("id")
    val id: String,
    @SerialName("kind")
    val kind: ResponseRunActivityKindResponse,
    @SerialName("message")
    val message: String? = null,
    @SerialName("startedAt")
    val startedAt: Instant,
    @SerialName("completedAt")
    val completedAt: Instant? = null,
    @SerialName("failed")
    val failed: Boolean = false,
)

@Serializable
enum class ResponseRunActivityKindResponse {
    @SerialName("THINKING")
    Thinking,

    @SerialName("WEATHER")
    Weather,

    @SerialName("PLACE_SEARCH")
    PlaceSearch,

    @SerialName("ROUTE")
    Route,

    @SerialName("MOVIE")
    Movie,

    @SerialName("SPORTS")
    Sports,

    @SerialName("MUSIC")
    Music,

    @SerialName("WEB")
    Web,

    @SerialName("OTHER_RESEARCH")
    OtherResearch,
}

@Serializable
data class ResponseRunEventEnvelope(
    @SerialName("runId")
    val runId: String,
    @SerialName("attempt")
    val attempt: Int,
    @SerialName("seq")
    val seq: Long,
    @SerialName("occurredAt")
    val occurredAt: Instant,
    @SerialName("payload")
    val payload: ResponseRunEventPayload,
)

@Serializable
sealed interface ResponseRunEventPayload {
    @Serializable
    @SerialName("thinking")
    data object Thinking : ResponseRunEventPayload

    @Serializable
    @SerialName("tool_started")
    data class ToolStarted(
        @SerialName("activityId")
        val activityId: String,
        @SerialName("kind")
        val kind: ResponseRunActivityKindResponse,
    ) : ResponseRunEventPayload

    @Serializable
    @SerialName("tool_completed")
    data class ToolCompleted(
        @SerialName("activityId")
        val activityId: String,
        @SerialName("kind")
        val kind: ResponseRunActivityKindResponse,
    ) : ResponseRunEventPayload

    @Serializable
    @SerialName("tool_failed")
    data class ToolFailed(
        @SerialName("activityId")
        val activityId: String,
        @SerialName("kind")
        val kind: ResponseRunActivityKindResponse,
    ) : ResponseRunEventPayload

    @Serializable
    @SerialName("streaming_started")
    data object StreamingStarted : ResponseRunEventPayload

    @Serializable
    @SerialName("delta")
    data class Delta(
        @SerialName("text")
        val text: String,
    ) : ResponseRunEventPayload

    @Serializable
    @SerialName("completed")
    data class Completed(
        @SerialName("assistantMessageId")
        val assistantMessageId: String? = null,
    ) : ResponseRunEventPayload

    @Serializable
    @SerialName("failed")
    data class Failed(
        @SerialName("retryable")
        val retryable: Boolean,
    ) : ResponseRunEventPayload

    @Serializable
    @SerialName("cancelled")
    data object Cancelled : ResponseRunEventPayload

    @Serializable
    @SerialName("timed_out")
    data object TimedOut : ResponseRunEventPayload

    @Serializable
    @SerialName("snapshot")
    data class Snapshot(
        @SerialName("snapshot")
        val snapshot: ResponseRunSnapshotResponse,
    ) : ResponseRunEventPayload
}

@Serializable
data class ConversationCurrentTaskResponse(
    @SerialName("task")
    val task: TaskResponse,
    @SerialName("requirements")
    val requirements: List<RequirementResponse>,
    @SerialName("plans")
    val plans: List<PlanResponse>,
    @SerialName("planning")
    val planning: PlanningStatusResponse,
)
