package com.nexusflow.backend.core.health

import com.nexusflow.backend.core.http.respondError
import com.nexusflow.backend.core.http.respondSuccess
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

fun Routing.healthRoutes(readinessProbe: ReadinessProbe) {
    get("/health/live") {
        call.respondSuccess(HealthResponse(status = "ok"))
    }
    get("/health/ready") {
        if (readinessProbe.isReady()) {
            call.respondSuccess(HealthResponse(status = "ready"))
        } else {
            call.respondError(HttpStatusCode.ServiceUnavailable, "Service is not ready")
        }
    }
}

@Serializable
private data class HealthResponse(
    val status: String,
)
