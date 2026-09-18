package com.nexusflow.backend.bootstrap

import com.nexusflow.backend.core.config.LoggingRuntimeConfig
import com.nexusflow.backend.core.health.ReadinessProbe
import com.nexusflow.backend.core.identity.ActorResolver
import com.nexusflow.backend.feature.auth.application.AuthService
import com.nexusflow.backend.feature.conversation.application.ConversationService
import com.nexusflow.backend.feature.conversation.application.ResponseRunService
import com.nexusflow.backend.feature.conversation.application.ResponseRunWorker
import com.nexusflow.backend.feature.task.application.PlanningService
import com.nexusflow.backend.feature.task.application.TaskService
import com.nexusflow.observability.StructuredLogger

internal data class BackendRuntime(
    val logging: LoggingRuntimeConfig,
    val structuredLogger: StructuredLogger?,
    val readinessProbe: ReadinessProbe,
    val authService: AuthService?,
    val actorResolver: ActorResolver?,
    val conversationService: ConversationService?,
    val responseRunService: ResponseRunService?,
    val responseRunWorker: ResponseRunWorker?,
    val taskService: TaskService?,
    val planningService: PlanningService?,
)
