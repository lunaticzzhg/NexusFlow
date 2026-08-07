package com.nexusflow.backend.infrastructure

import com.nexusflow.backend.application.CreateTaskPersistenceResult
import com.nexusflow.backend.application.TaskRepository
import com.nexusflow.backend.domain.ActorContext
import com.nexusflow.backend.domain.TaskAggregate
import com.nexusflow.contracts.task.TaskEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Development/test adapter. It has the same port shape as PostgreSQL + Outbox, but no durability. */
class InMemoryTaskRepository : TaskRepository {
    private val tasks = ConcurrentHashMap<String, TaskAggregate>()

    override fun findById(taskId: String): TaskAggregate? = tasks[taskId]

    override fun findByIdempotencyKey(actor: ActorContext, key: String): TaskAggregate? = tasks.values.firstOrNull {
        it.tenantId == actor.tenantId && it.ownerUserId == actor.userId && it.idempotencyKey == key
    }

    private val items = CopyOnWriteArrayList<TaskEvent>()

    @Synchronized
    override fun createWithOutbox(task: TaskAggregate, event: TaskEvent): CreateTaskPersistenceResult {
        val existing = findByIdempotencyKey(ActorContext(task.tenantId, task.ownerUserId), task.idempotencyKey)
        if (existing != null) return CreateTaskPersistenceResult.Existing(existing)
        check(tasks.putIfAbsent(task.id, task) == null) { "Task ${task.id} already exists" }
        items += event
        return CreateTaskPersistenceResult.Created(task)
    }

    @Synchronized
    override fun updateWithOutbox(expectedVersion: Long, task: TaskAggregate, event: TaskEvent): TaskAggregate {
        val existing = tasks[task.id] ?: error("Task ${task.id} does not exist")
        check(existing.version == expectedVersion) { "Task ${task.id} version conflict" }
        tasks[task.id] = task
        items += event
        return task
    }

    override fun events(): List<TaskEvent> = items.toList()
}
