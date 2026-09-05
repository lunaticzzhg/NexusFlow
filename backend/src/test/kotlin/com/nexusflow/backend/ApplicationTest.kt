package com.nexusflow.backend

import com.nexusflow.backend.bootstrap.BackendRuntimeProfile
import com.nexusflow.backend.core.config.RuntimeEnvironment
import com.nexusflow.backend.core.health.ReadinessProbe
import com.nexusflow.backend.core.health.healthRoutes
import com.nexusflow.backend.core.http.configureHttpPlatform
import com.nexusflow.backend.core.observability.BackendTraceContext
import com.nexusflow.observability.DefaultStructuredLogger
import com.nexusflow.observability.JsonLogFormatter
import com.nexusflow.observability.LogLevel
import com.nexusflow.observability.LogSink
import com.nexusflow.observability.TraceHeaders
import com.nexusflow.observability.TraceId
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.server.plugins.callid.callId
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.slf4j.MDC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ApplicationTest {
    @Test
    fun `trace IDs are returned from test runtime`() = testApplication {
        application {
            module(profile = BackendRuntimeProfile.Test)
        }

        val suppliedTraceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        val health = client.get("/health/live") {
            header(TraceHeaders.TraceId, suppliedTraceId)
        }
        assertEquals(suppliedTraceId, health.headers[TraceHeaders.TraceId])
    }

    @Test
    fun `missing and invalid trace IDs are generated and returned`() = testApplication {
        application {
            module(profile = BackendRuntimeProfile.Test)
        }

        val missing = client.get("/health/live")
        val invalid = client.get("/health/live") {
            header(TraceHeaders.TraceId, "not-a-trace")
        }

        val generatedForMissing = missing.headers[TraceHeaders.TraceId].orEmpty()
        val generatedForInvalid = invalid.headers[TraceHeaders.TraceId].orEmpty()
        assertTrue(TraceId.isValid(generatedForMissing))
        assertTrue(TraceId.isValid(generatedForInvalid))
        assertNotEquals("not-a-trace", generatedForInvalid)
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

    @Test
    fun `concurrent request MDC trace IDs do not cross contaminate`() = testApplication {
        application {
            configureHttpPlatform()
            routing {
                get("/trace") {
                    delay(25)
                    call.respondText("${call.callId}:${MDC.get("traceId")}")
                }
            }
        }

        val traceA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val traceB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

        val responses =
            runBlocking {
                coroutineScope {
                    listOf(
                        async {
                            client.get("/trace") { header(TraceHeaders.TraceId, traceA) }.bodyAsText()
                        },
                        async {
                            client.get("/trace") { header(TraceHeaders.TraceId, traceB) }.bodyAsText()
                        },
                    ).awaitAll()
                }
            }

        assertTrue("$traceA:$traceA" in responses)
        assertTrue("$traceB:$traceB" in responses)
    }

    @Test
    fun `json logging emits parseable backend request lifecycle and error records`() = testApplication {
        val sink = RecordingLogSink()
        val logger =
            DefaultStructuredLogger(
                traceContext = BackendTraceContext,
                formatter = JsonLogFormatter,
                sink = sink,
                serviceName = "nexusflow-backend",
                environment = RuntimeEnvironment.Prod.value,
                minimumLevel = LogLevel.INFO,
            )

        application {
            configureHttpPlatform(logger)
            routing {
                get("/v1/tasks/12345678/messages") {
                    call.respondText("ok")
                }
                get("/boom") {
                    error("secret implementation detail")
                }
            }
        }

        val traceId = "cccccccccccccccccccccccccccccccc"
        client.get("/v1/tasks/12345678/messages?token=secret") {
            header(TraceHeaders.TraceId, traceId)
        }
        client.get("/boom?query=secret") {
            header(TraceHeaders.TraceId, traceId)
        }

        val lines = sink.lines.toList()
        assertEquals(5, lines.size)
        lines.forEach { line ->
            assertTrue(line.startsWith("{") && line.endsWith("}"))
            assertTrue(!line.contains('\n'))
            assertTrue(!line.contains("token=secret"))
            assertTrue(!line.contains("query=secret"))
            assertTrue(!line.contains("secret implementation detail"))
        }

        val records = lines.map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(
            listOf(
                "http_request_started",
                "http_request_finished",
                "http_request_started",
                "http_request_failed",
                "http_request_finished",
            ),
            records.map { it.getValue("event").jsonPrimitive.content },
        )
        assertTrue(records.all { it.getValue("trace_id").jsonPrimitive.content == traceId })
        assertEquals("/v1/tasks/{id}/messages", records[0].getValue("http_path").jsonPrimitive.content)
        assertEquals("500", records[3].getValue("http_status").jsonPrimitive.content)
        assertEquals("IllegalStateException", records[3].getValue("error_type").jsonPrimitive.content)
    }

    private class RecordingLogSink : LogSink {
        val lines = mutableListOf<String>()

        override fun write(
            level: LogLevel,
            component: String,
            formatted: String,
        ) {
            lines += formatted
        }
    }
}
