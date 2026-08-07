package com.nexusflow.backend.application

import com.nexusflow.backend.domain.ActorContext
import com.nexusflow.backend.domain.TaskAggregate
import com.nexusflow.contracts.api.CreateTaskRequest
import com.nexusflow.contracts.api.TaskDetailResponse
import com.nexusflow.contracts.api.TaskSummaryResponse
import com.nexusflow.contracts.task.TaskEvent
import com.nexusflow.contracts.task.TaskEventType
import com.nexusflow.contracts.task.TaskStatus
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

data class CreateTaskResult(
    val taskId: String,
    val status: TaskStatus,
    val version: Long,
    val replayed: Boolean,
)

class IdempotencyConflictException(message: String) : IllegalStateException(message)

class TaskApplicationService(
    private val repository: TaskRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    /** Command-side entry point. A database adapter must make insert + outbox append atomic. */
    fun createTask(
        actor: ActorContext,
        request: CreateTaskRequest,
        idempotencyKey: String,
        correlationId: String = UUID.randomUUID().toString(),
    ): CreateTaskResult {
        require(idempotencyKey.isNotBlank()) { "Idempotency-Key is required" }
        val fingerprint = request.fingerprint()
        val now = clock.instant()
        val task = TaskAggregate(
            id = UUID.randomUUID().toString(),
            tenantId = actor.tenantId,
            ownerUserId = actor.userId,
            request = request,
            status = TaskStatus.QUEUED,
            version = 1,
            createdAt = now,
            updatedAt = now,
            idempotencyKey = idempotencyKey,
            requestFingerprint = fingerprint,
        )
        return when (val result = repository.createWithOutbox(
            task,
            TaskEvent(
                eventId = UUID.randomUUID().toString(),
                taskId = task.id,
                tenantId = actor.tenantId,
                type = TaskEventType.TASK_CREATED,
                occurredAt = now,
                correlationId = correlationId,
                payload = mapOf("taskVersion" to task.version.toString()),
            ),
        )) {
            is CreateTaskPersistenceResult.Created -> CreateTaskResult(
                result.task.id, result.task.status, result.task.version, replayed = false,
            )
            is CreateTaskPersistenceResult.Existing -> {
                if (result.task.requestFingerprint != fingerprint) {
                    throw IdempotencyConflictException("Idempotency-Key was already used with a different request")
                }
                CreateTaskResult(result.task.id, result.task.status, result.task.version, replayed = true)
            }
        }
    }

    fun getTask(actor: ActorContext, taskId: String): TaskDetailResponse? = repository.findById(taskId)
        ?.takeIf { it.tenantId == actor.tenantId && it.ownerUserId == actor.userId }
        ?.toDetailResponse()

    private fun CreateTaskRequest.fingerprint(): String {
        val fields = buildList {
            add(requestText.trim())
            add(timezone)
            add(conversationId.orEmpty())
            add(sourceDiscoveryId.orEmpty())
            constraints
                .sortedWith(compareBy({ it.key }, { it.value }, { it.source.name }))
                .forEach { add("${it.key}\u0000${it.value}\u0000${it.source.name}") }
        }
        val normalized = fields.joinToString(separator = "") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun TaskAggregate.toDetailResponse() = TaskDetailResponse(
        taskId = id,
        status = status,
        requestText = request.requestText,
        version = version,
        createdAt = createdAt,
        updatedAt = updatedAt,
        proposal = proposal,
    )
}
