package com.nexusflow.backend

import com.nexusflow.backend.bootstrap.BackendRuntimeProfile
import com.nexusflow.backend.core.health.ReadinessProbe
import com.nexusflow.backend.core.health.healthRoutes
import com.nexusflow.backend.core.http.configureHttpPlatform
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApplicationTest {
    @Test
    fun `request IDs are returned from test runtime`() = testApplication {
        application {
            module(profile = BackendRuntimeProfile.Test)
        }

        val suppliedRequestId = "request-id-1234"
        val health = client.get("/health/live") {
            header("X-Request-Id", suppliedRequestId)
        }
        assertEquals(suppliedRequestId, health.headers["X-Request-Id"])
    }

    @Test
    fun `unexpected failures produce a safe unified response`() = testApplication {
        application {
            configureHttpPlatform()
            routing {
                get("/boom") { error("secret implementation detail") }
            }
        }

        val response = client.get("/boom")
        val body = response.bodyAsText()
        assertEquals(HttpStatusCode.InternalServerError, response.status)
        assertTrue(body.contains("\"code\":500"))
        assertTrue(body.contains("\"message\":\"An unexpected error occurred\""))
        assertTrue(!body.contains("secret implementation detail"))
    }

    @Test
    fun `readiness reflects the probe without exposing dependency details`() = testApplication {
        application {
            configureHttpPlatform()
            routing { healthRoutes(ReadinessProbe { false }) }
        }

        val response = client.get("/health/ready")
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertEquals("{\"code\":503,\"message\":\"Service is not ready\"}", response.bodyAsText())
    }
}
