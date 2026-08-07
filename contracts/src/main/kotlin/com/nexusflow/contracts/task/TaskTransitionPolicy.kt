package com.nexusflow.contracts.task

import com.nexusflow.contracts.planning.PlanProposal

/**
 * Server-side policy for the only two valid exits from [TaskStatus.VALIDATING].
 *
 * Orchestrators MUST use this policy after validating a [PlanProposal], rather than infer approval
 * requirements from generated text or client input. Any proposed external action is approval-gated;
 * only a purely advisory proposal can finish without an approval step.
 */
object TaskTransitionPolicy {
    fun afterValidation(proposal: PlanProposal): TaskStatus {
        val next = if (proposal.options.any { it.actionRequests.isNotEmpty() }) {
            TaskStatus.AWAITING_APPROVAL
        } else {
            TaskStatus.COMPLETED
        }
        TaskLifecycle.requireTransition(TaskStatus.VALIDATING, next)
        return next
    }
}
