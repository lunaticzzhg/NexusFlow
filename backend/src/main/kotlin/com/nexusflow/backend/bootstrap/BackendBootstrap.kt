package com.nexusflow.backend.bootstrap

import com.nexusflow.backend.core.health.ReadinessProbe
import com.nexusflow.backend.core.health.configureProductionHealthDependencies
import com.nexusflow.backend.core.health.configureTestHealthDependencies
import com.nexusflow.backend.core.health.healthRoutes
import com.nexusflow.backend.core.identity.ActorResolver
import com.nexusflow.backend.core.persistence.configureDatabaseDependencies
import com.nexusflow.backend.feature.auth.api.authRoutes
import com.nexusflow.backend.feature.auth.application.AuthService
import com.nexusflow.backend.feature.auth.configureAuthDependencies
import com.nexusflow.backend.feature.task.api.taskRoutes
import com.nexusflow.backend.feature.task.application.PlanningService
import com.nexusflow.backend.feature.task.application.TaskService
import com.nexusflow.backend.feature.task.configureTaskDependencies
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.routing.routing
import org.flywaydb.core.Flyway

internal fun Application.bootstrapBackend(profile: BackendRuntimeProfile): BackendRuntime {
    return when (profile) {
        BackendRuntimeProfile.Production -> bootstrapProduction()
        BackendRuntimeProfile.Test -> bootstrapTest()
    }
}

internal fun Application.configureCoreRoutes(readinessProbe: ReadinessProbe) {
    routing {
        healthRoutes(readinessProbe)
    }
}

internal fun Application.configureFeatureRoutes(runtime: BackendRuntime) {
    routing {
        runtime.authService?.let(::authRoutes)
        val taskService = runtime.taskService
        val planningService = runtime.planningService
        val actorResolver = runtime.actorResolver
        if (taskService != null && planningService != null && actorResolver != null) {
            taskRoutes(taskService, planningService, actorResolver)
        }
    }
}

private fun Application.bootstrapProduction(): BackendRuntime {
    configureDatabaseDependencies()
    configureProductionHealthDependencies()
    configureAuthDependencies()
    configureTaskDependencies()
    val flyway: Flyway by dependencies
    flyway.migrate()
    val authService: AuthService by dependencies
    val actorResolver: ActorResolver by dependencies
    val taskService: TaskService by dependencies
    val planningService: PlanningService by dependencies
    val readinessProbe: ReadinessProbe by dependencies
    return BackendRuntime(
        readinessProbe = readinessProbe,
        authService = authService,
        actorResolver = actorResolver,
        taskService = taskService,
        planningService = planningService,
    )
}

private fun Application.bootstrapTest(): BackendRuntime {
    configureTestHealthDependencies()
    val readinessProbe: ReadinessProbe by dependencies
    return BackendRuntime(
        readinessProbe = readinessProbe,
        authService = null,
        actorResolver = null,
        taskService = null,
        planningService = null,
    )
}
