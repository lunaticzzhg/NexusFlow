package com.nexusflow.backend

import com.nexusflow.backend.bootstrap.BackendRuntimeProfile
import com.nexusflow.backend.bootstrap.bootstrapBackend
import com.nexusflow.backend.bootstrap.configureCoreRoutes
import com.nexusflow.backend.bootstrap.configureFeatureRoutes
import com.nexusflow.backend.core.http.configureHttpPlatform
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module(
    profile: BackendRuntimeProfile = BackendRuntimeProfile.fromEnvironment(),
) {
    configureHttpPlatform()
    val runtime = bootstrapBackend(profile)
    configureCoreRoutes(runtime.readinessProbe)
    configureFeatureRoutes(runtime)
}
