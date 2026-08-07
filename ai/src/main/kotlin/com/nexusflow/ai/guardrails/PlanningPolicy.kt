package com.nexusflow.ai.guardrails

import com.nexusflow.ai.planner.PlanProposal
import com.nexusflow.ai.planner.PlanningContext
import com.nexusflow.ai.planner.PolicyViolation
import com.nexusflow.ai.planner.PolicyViolationCode
import com.nexusflow.ai.planner.RequestedActionType
import com.nexusflow.ai.planner.RiskLabel

/** Deterministic validation for every model provider, including real ones. */
class PlanningPolicy {
    fun validateContext(context: PlanningContext): List<PolicyViolation> = buildList {
        if (context.taskId.isBlank() || context.request.isBlank() || context.request.length > MAX_REQUEST_LENGTH) {
            add(PolicyViolation(PolicyViolationCode.INVALID_REQUEST, "taskId and a bounded request are required"))
        }
        if (context.opportunities.size > MAX_OPPORTUNITIES) {
            add(PolicyViolation(PolicyViolationCode.INVALID_REQUEST, "too many opportunities in context"))
        }
    }

    fun validateProposal(context: PlanningContext, proposal: PlanProposal): List<PolicyViolation> = buildList {
        if (proposal.title.isBlank() || proposal.rationale.isEmpty() || proposal.options.size > MAX_OPTIONS) {
            add(PolicyViolation(PolicyViolationCode.INVALID_PROPOSAL, "proposal must have a title, rationale and at most $MAX_OPTIONS options"))
        }
        val knownIds = context.opportunities.mapTo(mutableSetOf()) { it.id }
        proposal.options.forEach { option ->
            if (option.id.isBlank() || option.title.isBlank() || option.summary.isBlank()) {
                add(PolicyViolation(PolicyViolationCode.INVALID_PROPOSAL, "option fields must not be blank"))
            }
            if (!knownIds.containsAll(option.referencedOpportunityIds)) {
                add(PolicyViolation(PolicyViolationCode.UNKNOWN_OPPORTUNITY, "option ${option.id} references an unknown opportunity"))
            }
            context.budget?.let { budget ->
                if (option.estimatedCost != null && option.estimatedCost > budget.maximumAmount) {
                    add(PolicyViolation(PolicyViolationCode.BUDGET_EXCEEDED, "option ${option.id} exceeds task budget"))
                }
            }
            if (option.requestedActions.isNotEmpty() && !proposal.requiresApproval) {
                add(PolicyViolation(PolicyViolationCode.APPROVAL_REQUIRED, "requested actions require explicit approval"))
            }
            if (option.requestedActions.any { it.type != RequestedActionType.OPEN_EXTERNAL_LINK } &&
                RiskLabel.EXTERNAL_WRITE !in proposal.riskLabels
            ) {
                add(PolicyViolation(PolicyViolationCode.INVALID_PROPOSAL, "external writes require an EXTERNAL_WRITE risk label"))
            }
        }
    }

    companion object {
        const val MAX_REQUEST_LENGTH = 4_000
        const val MAX_OPPORTUNITIES = 100
        const val MAX_OPTIONS = 3
    }
}
