package com.nexusflow.backend

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.nexusflow.backend.api.nexusFlowRoutes
import com.nexusflow.backend.api.DevelopmentActorResolver
import com.nexusflow.backend.application.TaskApplicationService
import com.nexusflow.backend.infrastructure.InMemoryTaskRepository
import io.ktor.serialization.jackson.jackson
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

fun Application.module(
    taskService: TaskApplicationService = defaultTaskService(),
    runtimeProfile: String = System.getenv("ORBIT_RUNTIME_PROFILE") ?: "production",
) {
    install(ContentNegotiation) {
        jackson {
            registerModule(JavaTimeModule())
            disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        }
    }
    routing { nexusFlowRoutes(taskService, DevelopmentActorResolver(runtimeProfile)) }
}

private fun defaultTaskService(): TaskApplicationService = TaskApplicationService(
    repository = InMemoryTaskRepository(),
)
