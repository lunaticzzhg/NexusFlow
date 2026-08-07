package com.nexusflow.backend

import com.nexusflow.backend.application.TaskApplicationService
import com.nexusflow.backend.infrastructure.InMemoryTaskRepository
import com.nexusflow.backend.orchestrator.InProcessPlanningEngine
import com.nexusflow.backend.orchestrator.OpportunityProvider
import com.nexusflow.backend.orchestrator.TaskPlanningWorker
import com.nexusflow.backend.domain.ActorContext
import com.nexusflow.contracts.api.CreateTaskRequest
import com.nexusflow.contracts.task.TaskStatus
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.time.Instant
import com.nexusflow.ai.planner.OpportunityCategory
import com.nexusflow.ai.planner.OpportunitySnapshot

class ApplicationTest {
    @Test
    fun `health and idempotent task creation are available`() = testApplication {
        val repository = InMemoryTaskRepository()
        application { module(TaskApplicationService(repository), runtimeProfile = "test") }

        assertEquals(HttpStatusCode.OK, client.get("/health/live").status)

        val first = client.post("/v1/tasks") {
            contentType(ContentType.Application.Json)
            header("Idempotency-Key", "task-create-1")
            header("X-Orbit-Tenant", "tenant-a")
            header("X-Orbit-User", "user-a")
            setBody("""{"requestText":"周末想看利物浦比赛，预算 300","timezone":"Asia/Shanghai"}""")
        }
        assertEquals(HttpStatusCode.Accepted, first.status)
        val body = first.bodyAsText()
        assertTrue(body.contains("\"replayed\":false"))
        assertEquals(1, repository.events().size)

        val replay = client.post("/v1/tasks") {
            contentType(ContentType.Application.Json)
            header("Idempotency-Key", "task-create-1")
            header("X-Orbit-Tenant", "tenant-a")
            header("X-Orbit-User", "user-a")
            setBody("""{"requestText":"周末想看利物浦比赛，预算 300","timezone":"Asia/Shanghai"}""")
        }
        assertEquals(HttpStatusCode.OK, replay.status)
        assertTrue(replay.bodyAsText().contains("\"replayed\":true"))
        assertEquals(1, repository.events().size)
    }
}

class TaskPlanningWorkerTest {
    @Test
    fun `worker turns a queued task into an approval gated proposal`() {
        val repository = InMemoryTaskRepository()
        val service = TaskApplicationService(repository)
        val actor = ActorContext("tenant-a", "user-a")
        val created = service.createTask(
            actor,
            CreateTaskRequest("安排周末比赛", "Asia/Shanghai"),
            idempotencyKey = "worker-task-1",
            correlationId = "request-1",
        )
        val worker = TaskPlanningWorker(
            repository = repository,
            opportunities = OpportunityProvider {
                listOf(
                    OpportunitySnapshot(
                        id = "match-1",
                        title = "利物浦 vs 阿森纳",
                        category = OpportunityCategory.SPORT,
                        startsAt = Instant.parse("2026-08-08T12:00:00Z"),
                        estimatedCost = 200,
                        sourceName = "fixture",
                        sourceUrl = "https://example.com/fixture",
                    ),
                )
            },
            planningEngine = InProcessPlanningEngine(),
        )

        assertEquals(TaskStatus.AWAITING_APPROVAL, worker.process(created.taskId, "request-1"))
        val task = service.getTask(actor, created.taskId)
        assertEquals(TaskStatus.AWAITING_APPROVAL, task?.status)
        assertEquals("利物浦 vs 阿森纳", task?.proposal?.options?.single()?.items?.single()?.title)
        assertEquals(5, repository.events().size)
    }
}
