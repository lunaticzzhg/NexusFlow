package com.nexusflow.backend.feature.task.domain

import com.nexusflow.backend.feature.responserun.domain.ConsumeResponseRunResult
import com.nexusflow.backend.feature.responserun.domain.ResponseRunFailureCategory
import com.nexusflow.backend.feature.responserun.domain.ResponseRunResult
import com.nexusflow.backend.feature.responserun.domain.ResponseRunResultPayload
import java.time.Instant

interface PlanningResultCommitter {
    suspend fun consumePlanningUnderstanding(command: ConsumePlanningUnderstandingCommand): ConsumeResponseRunResult

    suspend fun consumePlanningResult(command: ConsumePlanningResultCommand): ConsumeResponseRunResult
}

data class ConsumePlanningUnderstandingCommand(
    val result: ResponseRunResult,
    val payload: ResponseRunResultPayload.PlanningUnderstanding,
    val newTaskId: TaskId,
    val requirements: List<RequirementWrite>,
    val removedRequirementKinds: List<RequirementKind>,
    val now: Instant,
)

data class ConsumePlanningResultCommand(
    val result: ResponseRunResult,
    val payload: ResponseRunResultPayload.PlanningResult,
    val opportunities: List<Opportunity>,
    val plans: List<Plan>,
    val failureCategory: ResponseRunFailureCategory?,
    val now: Instant,
)
