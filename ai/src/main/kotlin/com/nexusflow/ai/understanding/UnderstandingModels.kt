package com.nexusflow.ai.understanding

import com.nexusflow.ai.context.ModelContextBlockPayload
import com.nexusflow.ai.context.SelectableContextDefinitionPayload
import com.nexusflow.ai.provider.StructuredModelRequestDiagnostics
import com.nexusflow.ai.provider.StructuredModelUsage
import kotlinx.datetime.Instant

data class UnderstandingContext(
    val aiRequestId: String,
    val taskId: String,
    val taskRevision: Long,
    val intent: String,
    val requirements: List<CurrentRequirement>,
    val currentMessage: String,
    val referenceTime: Instant,
    val timeZoneId: String,
    val optionalContext: List<ModelContextBlockPayload> = emptyList(),
    val availableContextDefinitions: List<SelectableContextDefinitionPayload> = emptyList(),
    val diagnostics: StructuredModelRequestDiagnostics = StructuredModelRequestDiagnostics(),
)

data class CurrentRequirement(
    val kind: RequirementKind,
    val value: RequirementValue,
    val strength: RequirementStrength,
)

data class UnderstandingOutcome(
    val userIntent: UserIntent,
    val intentPatch: String?,
    val requirementChanges: List<ProposedRequirementChange>,
    val clarification: ClarificationProposal,
    val contextSelection: ContextSelectionProposal,
    val metadata: UnderstandingMetadata,
) {
    constructor(
        userIntent: UserIntent,
        requirementChanges: List<ProposedRequirementChange>,
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

data class ContextSelectionProposal(
    val selectedKeys: List<String> = emptyList(),
)

data class ClarificationProposal(
    val needed: Boolean,
    val missingInformation: List<String>,
    val reasonCategory: ClarificationReasonCategory,
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

enum class ClarificationReasonCategory {
    None,
    MissingRequiredInformation,
    AmbiguousRequirement,
    UnsupportedRequest,
}

enum class UserIntent {
    PlanRequest,
    RequirementUpdate,
    ClarificationResponse,
}

data class ProposedRequirementChange(
    val kind: RequirementKind,
    val value: RequirementValue,
    val strength: RequirementStrength,
    val evidenceText: String,
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

data class UnderstandingMetadata(
    val provider: String,
    val model: String,
    val promptVersion: String,
    val providerRequestId: String?,
    val attemptCount: Int,
    val usage: StructuredModelUsage? = null,
    val diagnostics: StructuredModelRequestDiagnostics = StructuredModelRequestDiagnostics(),
)

sealed class UserMessageUnderstandingException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

class ProviderUnavailableException(cause: Throwable? = null) :
    UserMessageUnderstandingException("Understanding provider is unavailable", cause)

class ProviderUnauthorizedException(cause: Throwable? = null) :
    UserMessageUnderstandingException("Understanding provider rejected credentials", cause)

class ProviderRateLimitedException(cause: Throwable? = null) :
    UserMessageUnderstandingException("Understanding provider rate limited the request", cause)

class ProviderTimeoutException(cause: Throwable? = null) :
    UserMessageUnderstandingException("Understanding provider timed out", cause)

class ProviderRefusedException :
    UserMessageUnderstandingException("Understanding provider refused the request")

class InvalidStructuredOutputException(message: String, cause: Throwable? = null) :
    UserMessageUnderstandingException(message, cause)
