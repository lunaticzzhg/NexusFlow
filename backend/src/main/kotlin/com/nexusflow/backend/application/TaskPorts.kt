package com.nexusflow.backend.application

import com.nexusflow.backend.domain.ActorContext
import com.nexusflow.backend.domain.TaskAggregate
import com.nexusflow.contracts.task.TaskEvent

interface TaskRepository {
    fun findById(taskId: String): TaskAggregate?
    fun findByIdempotencyKey(actor: ActorContext, key: String): TaskAggregate?
    /**
     * One transactional command boundary. Production performs aggregate insert,
     * uniqueness check and outbox insert in a single PostgreSQL transaction.
     */
    fun createWithOutbox(task: TaskAggregate, event: TaskEvent): CreateTaskPersistenceResult

    /** Expected-version update plus durable event publication in one transaction. */
    fun updateWithOutbox(expectedVersion: Long, task: TaskAggregate, event: TaskEvent): TaskAggregate

    fun events(): List<TaskEvent>
}

sealed interface CreateTaskPersistenceResult {
    data class Created(val task: TaskAggregate) : CreateTaskPersistenceResult
    data class Existing(val task: TaskAggregate) : CreateTaskPersistenceResult
}
