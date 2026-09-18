package com.nexusflow.contracts.backendai.understanding

import com.nexusflow.contracts.backendai.common.ModelContextBlockPayload
import com.nexusflow.contracts.backendai.common.SelectableContextDefinitionPayload
import com.nexusflow.contracts.backendai.common.StructuredModelRequestDiagnostics
import com.nexusflow.contracts.backendai.common.StructuredModelUsage
import kotlinx.datetime.Instant
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

fun interface UserMessageUnderstanding {
    suspend fun understand(request: UnderstandMessageRequest): UnderstandMessageResult
}

const val UNDERSTAND_USER_MESSAGE_PROMPT_VERSION = "understand-user-message-v2"

/**
 * Backend asks the AI layer to interpret one committed user message in a Conversation.
 *
 * Backend may include active Planning context when the Conversation is currently attached to a Task. The AI may only
 * propose turn intent, planning goal, constraint, clarification, and context-selection changes; Backend owns validation
 * and mutation of authoritative Planning state.
 */
@Serializable
data class UnderstandMessageRequest(
    @SerialName("aiRequestId")
    val aiRequestId: String,
    @SerialName("currentMessage")
    val currentMessage: String,
    @SerialName("referenceTime")
    val referenceTime: Instant,
    @SerialName("timeZoneId")
    val timeZoneId: String,
    @SerialName("activePlanning")
    val activePlanning: ActivePlanningContextPayload?,
    @SerialName("optionalContext")
    val optionalContext: List<ModelContextBlockPayload> = emptyList(),
    @SerialName("availableContextDefinitions")
    val availableContextDefinitions: List<SelectableContextDefinitionPayload> = emptyList(),
    @SerialName("diagnostics")
    val diagnostics: StructuredModelRequestDiagnostics = StructuredModelRequestDiagnostics(),
)

@Serializable
data class ActivePlanningContextPayload(
    @SerialName("taskId")
    val taskId: String,
    @SerialName("taskRevision")
    val taskRevision: Long,
    @SerialName("goal")
    val goal: String,
    @SerialName("requirements")
    val requirements: List<CurrentRequirement>,
)

@Serializable
data class CurrentRequirement(
    @SerialName("kind")
    val kind: RequirementKind,
    @SerialName("value")
    val value: RequirementValue,
    @SerialName("strength")
    val strength: RequirementStrength,
)

/**
 * AI returns proposed interpretation results for one message.
 *
 * Backend must validate these proposals before applying any planning or context-selection change.
 */
@Serializable
data class UnderstandMessageResult(
    @SerialName("turnIntent")
    val turnIntent: TurnIntent,
    @SerialName("planningGoalPatch")
    val planningGoalPatch: String?,
    @SerialName("constraintDeltas")
    val constraintDeltas: List<ConstraintDeltaProposal>,
    @SerialName("clarification")
    val clarification: ClarificationProposal,
    @SerialName("contextSelection")
    val contextSelection: ContextSelectionProposal,
    @SerialName("metadata")
    val metadata: UnderstandingMetadata,
) {
    init {
        if (turnIntent == TurnIntent.Conversation) {
            require(planningGoalPatch == null) {
                "planningGoalPatch must be null for conversation turns"
            }
            require(constraintDeltas.isEmpty()) {
                "constraintDeltas must be empty for conversation turns"
            }
        }
    }
}

@Serializable
data class ContextSelectionProposal(
    @SerialName("selectedKeys")
    val selectedKeys: List<String> = emptyList(),
)

@Serializable
data class ClarificationProposal(
    @SerialName("needed")
    val needed: Boolean,
    @SerialName("missingInformation")
    val missingInformation: List<String>,
    @SerialName("reasonCategory")
    val reasonCategory: ClarificationReasonCategory,
    @SerialName("questionDraft")
    val questionDraft: String?,
) {
    init {
        if (needed) {
            require(missingInformation.isNotEmpty()) { "missingInformation must be non-empty when clarification is needed" }
            require(!questionDraft.isNullOrBlank()) { "questionDraft must be nonblank when clarification is needed" }
        } else {
            require(missingInformation.isEmpty()) { "missingInformation must be empty when clarification is not needed" }
        }
    }
}

@Serializable
enum class ClarificationReasonCategory {
    @SerialName("none")
    None,

    @SerialName("missing_required_information")
    MissingRequiredInformation,

    @SerialName("ambiguous_requirement")
    AmbiguousRequirement,
}

@Serializable
enum class TurnIntent {
    @SerialName("conversation")
    Conversation,

    @SerialName("planning")
    Planning,
}

@Serializable
data class ConstraintDeltaProposal(
    @SerialName("operation")
    val operation: ConstraintDeltaOperation,
    @SerialName("kind")
    val kind: RequirementKind,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("value")
    val value: RequirementValue? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("strength")
    val strength: RequirementStrength? = null,
    @SerialName("evidenceText")
    val evidenceText: String,
)

@Serializable
enum class ConstraintDeltaOperation {
    @SerialName("upsert")
    Upsert,

    @SerialName("remove")
    Remove,
}

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
sealed interface RequirementValue {
    @Serializable
    @SerialName("time_window")
    data class TimeWindow(
        @SerialName("startAt")
        val startAt: Instant?,
        @SerialName("endAt")
        val endAt: Instant?,
        @SerialName("timeZoneId")
        val timeZoneId: String,
        @SerialName("originalText")
        val originalText: String,
    ) : RequirementValue

    @Serializable
    @SerialName("budget_limit")
    data class BudgetLimit(
        @SerialName("wholeUnits")
        val wholeUnits: Long,
        @SerialName("currencyCode")
        val currencyCode: String?,
    ) : RequirementValue

    @Serializable
    @SerialName("commute_limit")
    data class CommuteLimit(
        @SerialName("maxMinutes")
        val maxMinutes: Int,
    ) : RequirementValue

    @Serializable
    @SerialName("commute_preference")
    data class CommutePreference(
        @SerialName("value")
        val value: CommutePreferenceValue,
    ) : RequirementValue

    @Serializable
    @SerialName("location")
    data class Location(
        @SerialName("text")
        val text: String,
    ) : RequirementValue

    @Serializable
    @SerialName("activity_domain")
    data class ActivityDomain(
        @SerialName("value")
        val value: String,
    ) : RequirementValue

    @Serializable
    @SerialName("activity_mode")
    data class ActivityMode(
        @SerialName("value")
        val value: ActivityModeValue,
    ) : RequirementValue

    @Serializable
    @SerialName("topic")
    data class Topic(
        @SerialName("text")
        val text: String,
    ) : RequirementValue

    @Serializable
    @SerialName("experience_preference")
    data class ExperiencePreference(
        @SerialName("text")
        val text: String,
    ) : RequirementValue
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

@Serializable
data class UnderstandingMetadata(
    @SerialName("provider")
    val provider: String,
    @SerialName("model")
    val model: String,
    @SerialName("promptVersion")
    val promptVersion: String,
    @SerialName("providerRequestId")
    val providerRequestId: String?,
    @SerialName("attemptCount")
    val attemptCount: Int,
    @SerialName("usage")
    val usage: StructuredModelUsage? = null,
    @SerialName("diagnostics")
    val diagnostics: StructuredModelRequestDiagnostics = StructuredModelRequestDiagnostics(),
)
