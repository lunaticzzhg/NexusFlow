package com.nexusflow.ai.planner

fun interface PlanExplainer {
    suspend fun explain(context: PlanExplanationContext): PlanExplanation
}
