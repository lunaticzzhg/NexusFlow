package com.nexusflow.backend.api

import com.nexusflow.backend.application.IdempotencyConflictException
import com.nexusflow.backend.application.TaskApplicationService
import com.nexusflow.contracts.api.ApiErrorCode
import com.nexusflow.contracts.api.ApiErrorResponse
import com.nexusflow.contracts.api.CreateTaskRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.util.AttributeKey
import java.util.UUID

data class AcceptedTaskResponse(
    val taskId: String,
    val status: String,
    val version: Long,
    val replayed: Boolean,
    val streamUrl: String,
)

fun Routing.nexusFlowRoutes(
    taskService: TaskApplicationService,
    actorResolver: DevelopmentActorResolver,
) {
    get("/health/live") { call.respond(mapOf("status" to "ok", "service" to "api-service")) }
    get("/health/ready") { call.respond(mapOf("status" to "ready", "service" to "api-service")) }

    post("/v1/tasks") {
        val actor = actorResolver.resolve(call)
        if (!actor.hasScope("orbit.tasks.write")) {
            return@post call.respondError(HttpStatusCode.Forbidden, ApiErrorCode.FORBIDDEN, "Missing orbit.tasks.write scope")
        }
        val key = call.request.headers["Idempotency-Key"]
            ?: return@post call.respondError(HttpStatusCode.BadRequest, ApiErrorCode.VALIDATION_FAILED, "Idempotency-Key is required")
        try {
            val result = taskService.createTask(
                actor = actor,
                request = call.receive<CreateTaskRequest>(),
                idempotencyKey = key,
                correlationId = call.traceId(),
            )
            call.respond(
                if (result.replayed) HttpStatusCode.OK else HttpStatusCode.Accepted,
                AcceptedTaskResponse(
                    taskId = result.taskId,
                    status = result.status.name,
                    version = result.version,
                    replayed = result.replayed,
                    streamUrl = "/v1/tasks/${result.taskId}/stream",
                ),
            )
        } catch (exception: IdempotencyConflictException) {
            call.respondError(HttpStatusCode.Conflict, ApiErrorCode.IDEMPOTENCY_CONFLICT, exception.message ?: "Idempotency conflict")
        } catch (exception: IllegalArgumentException) {
            call.respondError(HttpStatusCode.UnprocessableEntity, ApiErrorCode.VALIDATION_FAILED, exception.message ?: "Invalid request")
        }
    }

    get("/v1/tasks/{taskId}") {
        val actor = actorResolver.resolve(call)
        if (!actor.hasScope("orbit.tasks.read")) {
            return@get call.respondError(HttpStatusCode.Forbidden, ApiErrorCode.FORBIDDEN, "Missing orbit.tasks.read scope")
        }
        val taskId = call.parameters["taskId"]
            ?: return@get call.respondError(HttpStatusCode.BadRequest, ApiErrorCode.VALIDATION_FAILED, "taskId is required")
        val task = taskService.getTask(actor, taskId)
            ?: return@get call.respondError(HttpStatusCode.NotFound, ApiErrorCode.NOT_FOUND, "Task not found")
        call.respond(task)
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondError(
    status: HttpStatusCode,
    code: ApiErrorCode,
    message: String,
) {
    respond(status, ApiErrorResponse(code, message, traceId()))
}

private val TraceIdKey = AttributeKey<String>("orbit.trace-id")

private fun ApplicationCall.traceId(): String {
    attributes.getOrNull(TraceIdKey)?.let { return it }
    val inbound = request.headers["X-Request-Id"]
    val traceId = inbound?.takeIf { it.matches(Regex("[A-Za-z0-9-]{8,128}")) } ?: UUID.randomUUID().toString()
    attributes.put(TraceIdKey, traceId)
    response.headers.append("X-Request-Id", traceId)
    return traceId
}
