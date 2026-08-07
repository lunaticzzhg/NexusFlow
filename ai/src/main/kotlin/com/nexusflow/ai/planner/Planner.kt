package com.nexusflow.ai.planner

import com.nexusflow.ai.guardrails.PlanningPolicy

/**
 * Pure planning coordinator: validate bounded input, ask a model provider for
 * a structured proposal, then validate that proposal before returning it.
 */
class Planner(
    private val modelProvider: ModelProvider = DeterministicStubModelProvider(),
    private val policy: PlanningPolicy = PlanningPolicy(),
) {
    fun plan(context: PlanningContext): PlanningResult {
        policy.validateContext(context).takeIf { it.isNotEmpty() }?.let { return PlanningResult.Rejected(it) }
        val proposal = modelProvider.propose(context)
        val violations = policy.validateProposal(context, proposal)
        return if (violations.isEmpty()) PlanningResult.Accepted(proposal) else PlanningResult.Rejected(violations)
    }

    /** Compatibility entry point for the initial backend preview endpoint. */
    fun createPreview(request: String): PlanProposal {
        val context = PlanningContext(taskId = "preview", request = request)
        return when (val result = plan(context)) {
            is PlanningResult.Accepted -> result.proposal
            is PlanningResult.Rejected -> error("Preview rejected: ${result.violations.joinToString { it.code.name }}")
        }
    }
}
