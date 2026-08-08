package com.nexusflow.backend.core.health

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

fun Routing.healthRoutes(readinessProbe: ReadinessProbe) {
    get("/health/live") {
        call.respond(HealthResponse(status = "ok"))
    }
    get("/health/ready") {
        if (readinessProbe.isReady()) {
            call.respond(HealthResponse(status = "ready"))
        } else {
            call.respond(HttpStatusCode.ServiceUnavailable, HealthResponse(status = "not_ready"))
        }
    }
}

@Serializable
private data class HealthResponse(
    val status: String,
)
