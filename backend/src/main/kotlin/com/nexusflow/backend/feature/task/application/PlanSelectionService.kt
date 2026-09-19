package com.nexusflow.backend.feature.task.application

import com.nexusflow.backend.core.identity.ActorContext
import com.nexusflow.backend.feature.task.domain.SelectPlanCommand
import com.nexusflow.backend.feature.task.domain.SelectPlanResult
import com.nexusflow.backend.feature.task.domain.TaskDetail
import com.nexusflow.backend.feature.task.domain.TaskRepository
import java.time.Clock

internal class PlanSelectionService(
    private val repository: TaskRepository,
    private val clock: Clock,
) {
    suspend fun selectPlan(
        actor: ActorContext,
        taskId: String,
        planId: String,
    ): TaskDetail {
        actor.requirePlanningScope(PLANNING_WRITE_SCOPE)
        return when (
            val result = repository.selectCurrentPlan(
                SelectPlanCommand(
                    owner = actor.toPlanningTaskOwner(),
                    taskId = taskId.toPlanningTaskId(),
                    planId = planId.toPlanningPlanId(),
                    now = clock.instant(),
                ),
            )
        ) {
            is SelectPlanResult.Selected -> result.detail
            SelectPlanResult.Expired,
            SelectPlanResult.RevisionConflict,
            -> throw TaskConflictException()
            SelectPlanResult.PlanNotFound,
            SelectPlanResult.TaskNotFound,
            -> throw TaskNotFoundException()
        }
    }
}
