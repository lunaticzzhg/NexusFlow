package com.nexusflow.backend.bootstrap

import com.nexusflow.backend.core.health.ReadinessProbe
import com.nexusflow.backend.core.health.configureProductionHealthDependencies
import com.nexusflow.backend.core.health.configureTestHealthDependencies
import com.nexusflow.backend.core.health.healthRoutes
import com.nexusflow.backend.core.persistence.configureDatabaseDependencies
import com.nexusflow.backend.feature.auth.api.authRoutes
import com.nexusflow.backend.feature.auth.application.AuthService
import com.nexusflow.backend.feature.auth.configureAuthDependencies
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
    }
}

private fun Application.bootstrapProduction(): BackendRuntime {
    configureDatabaseDependencies()
    configureProductionHealthDependencies()
    configureAuthDependencies()
    val flyway: Flyway by dependencies
    flyway.migrate()
    val authService: AuthService by dependencies
    val readinessProbe: ReadinessProbe by dependencies
    return BackendRuntime(readinessProbe = readinessProbe, authService = authService)
}

private fun Application.bootstrapTest(): BackendRuntime {
    configureTestHealthDependencies()
    val readinessProbe: ReadinessProbe by dependencies
    return BackendRuntime(
        readinessProbe = readinessProbe,
        authService = null,
    )
}
