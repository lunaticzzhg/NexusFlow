package com.nexusflow.contracts.backendai.understanding

import com.nexusflow.contracts.backendai.common.ModelContextBlockPayload
import com.nexusflow.contracts.backendai.common.SelectableContextDefinitionPayload
import com.nexusflow.contracts.backendai.common.StructuredModelRequestDiagnostics
import com.nexusflow.contracts.backendai.common.StructuredModelUsage
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

fun interface UserMessageUnderstanding {
    suspend fun understand(request: UnderstandMessageRequest): UnderstandMessageResult
}

const val UNDERSTAND_USER_MESSAGE_PROMPT_VERSION = "understand-user-message-v1"

/**
 * Backend asks the AI layer to interpret one committed user message in the current Task state.
 *
 * The AI may only propose intent, requirement, clarification, and context-selection changes; Backend owns validation
 * and mutation of authoritative Task state.
 */
@Serializable
data class UnderstandMessageRequest(
    @SerialName("aiRequestId")
    val aiRequestId: String,
    @SerialName("taskId")
    val taskId: String,
    @SerialName("taskRevision")
    val taskRevision: Long,
    @SerialName("intent")
    val intent: String,
    @SerialName("requirements")
    val requirements: List<CurrentRequirement>,
    @SerialName("currentMessage")
    val currentMessage: String,
    @SerialName("referenceTime")
    val referenceTime: Instant,
    @SerialName("timeZoneId")
    val timeZoneId: String,
    @SerialName("optionalContext")
    val optionalContext: List<ModelContextBlockPayload> = emptyList(),
    @SerialName("availableContextDefinitions")
    val availableContextDefinitions: List<SelectableContextDefinitionPayload> = emptyList(),
    @SerialName("diagnostics")
    val diagnostics: StructuredModelRequestDiagnostics = StructuredModelRequestDiagnostics(),
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
 * Backend must validate these proposals before applying any intent, requirement, or context-selection change.
 */
@Serializable
data class UnderstandMessageResult(
    @SerialName("userIntent")
    val userIntent: UserIntent,
    @SerialName("intentPatch")
    val intentPatch: String?,
    @SerialName("requirementChanges")
    val requirementChanges: List<RequirementChangeProposal>,
    @SerialName("clarification")
    val clarification: ClarificationProposal,
    @SerialName("contextSelection")
    val contextSelection: ContextSelectionProposal,
    @SerialName("metadata")
    val metadata: UnderstandingMetadata,
) {
    constructor(
        userIntent: UserIntent,
        requirementChanges: List<RequirementChangeProposal>,
        missingInformation: List<String>,
        clarificationNeeded: Boolean,
        assistantMessageDraft: String?,
        metadata: UnderstandingMetadata,
        contextSelection: ContextSelectionProposal = ContextSelectionProposal(),
    ) : this(
        userIntent = userIntent,
        intentPatch = null,
        requirementChanges = requirementChanges,
        clarification = ClarificationProposal(
            needed = clarificationNeeded,
            missingInformation = missingInformation,
            reasonCategory = if (clarificationNeeded) {
                ClarificationReasonCategory.MissingRequiredInformation
            } else {
                ClarificationReasonCategory.None
            },
            questionDraft = assistantMessageDraft,
        ),
        contextSelection = contextSelection,
        metadata = metadata,
    )

    val missingInformation: List<String>
        get() = clarification.missingInformation

    val clarificationNeeded: Boolean
        get() = clarification.needed

    val assistantMessageDraft: String?
        get() = clarification.questionDraft
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

    @SerialName("unsupported_request")
    UnsupportedRequest,
}

@Serializable
enum class UserIntent {
    @SerialName("plan_request")
    PlanRequest,

    @SerialName("requirement_update")
    RequirementUpdate,

    @SerialName("clarification_response")
    ClarificationResponse,
}

@Serializable
data class RequirementChangeProposal(
    @SerialName("kind")
    val kind: RequirementKind,
    @SerialName("value")
    val value: RequirementValue,
    @SerialName("strength")
    val strength: RequirementStrength,
    @SerialName("evidenceText")
    val evidenceText: String,
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
