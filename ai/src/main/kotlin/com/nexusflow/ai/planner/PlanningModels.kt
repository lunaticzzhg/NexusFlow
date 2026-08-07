package com.nexusflow.ai.planner

import java.time.Instant

/**
 * The complete, bounded input passed to a planning model. It intentionally
 * contains no database handles, OAuth credentials, or executable tools.
 */
data class PlanningContext(
    val taskId: String,
    val request: String,
    val locale: String = "zh-CN",
    val timezone: String = "Asia/Shanghai",
    val requestedAt: Instant = Instant.EPOCH,
    val budget: TaskBudget? = null,
    val constraints: List<PlanningConstraint> = emptyList(),
    val preferences: List<UserPreference> = emptyList(),
    val opportunities: List<OpportunitySnapshot> = emptyList(),
)

data class TaskBudget(
    val maximumAmount: Long,
    val currency: String = "CNY",
) {
    init {
        require(maximumAmount >= 0) { "maximumAmount must not be negative" }
        require(currency.matches(Regex("[A-Z]{3}"))) { "currency must be an ISO-4217 code" }
    }
}

data class PlanningConstraint(
    val key: String,
    val value: String,
    val source: ConstraintSource,
    val isHard: Boolean,
)

enum class ConstraintSource { USER_MESSAGE, USER_PROFILE, SYSTEM_POLICY }

data class UserPreference(
    val key: String,
    val value: String,
    val confidence: Double,
) {
    init {
        require(confidence in 0.0..1.0) { "confidence must be between 0 and 1" }
    }
}

/** A read-only snapshot gathered by the orchestrator/tool gateway. */
data class OpportunitySnapshot(
    val id: String,
    val title: String,
    val category: OpportunityCategory,
    val startsAt: Instant? = null,
    val estimatedCost: Long? = null,
    val sourceName: String,
    val sourceUrl: String? = null,
)

enum class OpportunityCategory { SPORT, MOVIE, LOCAL_EVENT, DINING, OTHER }

/** Structured output that can cross the AI boundary. It is still only a proposal. */
data class PlanProposal(
    val title: String,
    val rationale: List<String>,
    val requiresApproval: Boolean,
    val options: List<PlanOption> = emptyList(),
    val followUpQuestions: List<String> = emptyList(),
    val riskLabels: Set<RiskLabel> = emptySet(),
)

data class PlanOption(
    val id: String,
    val title: String,
    val summary: String,
    val estimatedCost: Long? = null,
    val currency: String? = null,
    val referencedOpportunityIds: List<String> = emptyList(),
    val requestedActions: List<RequestedAction> = emptyList(),
)

/**
 * Declarative desired actions only. The AI module cannot execute these; the
 * backend must validate, authorize, and approve them before dispatching.
 */
data class RequestedAction(
    val type: RequestedActionType,
    val displayName: String,
    val parameters: Map<String, String> = emptyMap(),
)

enum class RequestedActionType { CREATE_CALENDAR_EVENT, CREATE_REMINDER, OPEN_EXTERNAL_LINK }

enum class RiskLabel { EXTERNAL_WRITE, BUDGET_ESTIMATE, TIME_SENSITIVE, INCOMPLETE_CONTEXT }

sealed interface PlanningResult {
    data class Accepted(val proposal: PlanProposal) : PlanningResult
    data class Rejected(val violations: List<PolicyViolation>) : PlanningResult
}

data class PolicyViolation(
    val code: PolicyViolationCode,
    val message: String,
)

enum class PolicyViolationCode {
    INVALID_REQUEST,
    INVALID_PROPOSAL,
    UNKNOWN_OPPORTUNITY,
    BUDGET_EXCEEDED,
    APPROVAL_REQUIRED,
}
