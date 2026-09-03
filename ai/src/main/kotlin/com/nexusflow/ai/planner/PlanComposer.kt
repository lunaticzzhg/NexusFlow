package com.nexusflow.ai.planner

fun interface PlanComposer {
    suspend fun compose(context: PlanningContext): PlanComposition
}
