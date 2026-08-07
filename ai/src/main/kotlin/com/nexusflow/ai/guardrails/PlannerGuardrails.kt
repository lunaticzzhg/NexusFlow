package com.nexusflow.ai.guardrails

import com.nexusflow.ai.planner.PlanningContext

/** Backwards-compatible lightweight request guard. Prefer [PlanningPolicy]. */
object PlannerGuardrails {
    fun accepts(request: String): Boolean = request.isNotBlank() && request.length <= PlanningPolicy.MAX_REQUEST_LENGTH

    fun accepts(context: PlanningContext): Boolean = PlanningPolicy().validateContext(context).isEmpty()
}
