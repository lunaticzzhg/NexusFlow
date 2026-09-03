package com.nexusflow.backend.bootstrap

import com.nexusflow.backend.core.health.ReadinessProbe
import com.nexusflow.backend.core.identity.ActorResolver
import com.nexusflow.backend.feature.auth.application.AuthService
import com.nexusflow.backend.feature.task.application.PlanningService
import com.nexusflow.backend.feature.task.application.TaskService

internal data class BackendRuntime(
    val readinessProbe: ReadinessProbe,
    val authService: AuthService?,
    val actorResolver: ActorResolver?,
    val taskService: TaskService?,
    val planningService: PlanningService?,
)
