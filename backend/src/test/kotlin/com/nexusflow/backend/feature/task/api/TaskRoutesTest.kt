package com.nexusflow.backend.feature.task.api

import com.nexusflow.ai.understanding.ConstraintCandidate
import com.nexusflow.ai.understanding.ConstraintKind
import com.nexusflow.ai.understanding.ConstraintStrength
import com.nexusflow.ai.understanding.ConstraintValue
import com.nexusflow.ai.understanding.InvalidStructuredOutputException
import com.nexusflow.ai.understanding.UnderstandingMetadata
import com.nexusflow.ai.understanding.UnderstandingOutcome
import com.nexusflow.ai.understanding.UserIntent
import com.nexusflow.ai.understanding.UserMessageUnderstanding
import com.nexusflow.backend.core.http.configureHttpPlatform
import com.nexusflow.backend.core.identity.ActorContext
import com.nexusflow.backend.core.identity.ActorResolver
import com.nexusflow.backend.core.identity.UnauthenticatedException
import com.nexusflow.backend.feature.task.application.TaskService
import com.nexusflow.backend.feature.task.infrastructure.JdbcTaskRepository
import com.nexusflow.backend.test.PostgresTestGate
import com.nexusflow.contracts.api.ConstraintKind as ConstraintKindResponse
import com.nexusflow.contracts.api.CreateTaskRequest
import com.nexusflow.contracts.api.GeneratePlansRequest
import com.nexusflow.contracts.api.GeneratePlansResponse
import com.nexusflow.contracts.api.KResponse
import com.nexusflow.contracts.api.SelectPlanRequest
import com.nexusflow.contracts.api.SendTaskMessageRequest
import com.nexusflow.contracts.api.TaskDetailResponse
import com.nexusflow.contracts.api.TaskState
import com.nexusflow.contracts.api.TaskSummaryResponse
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.sql.Timestamp
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TaskRoutesTest {
    @Test
    fun `task APIs use resolver actor and persist create list get against Postgres`() = taskApi {
        val create = client.post("/v1/tasks") {
            actor(UserOne)
            jsonBody(
                """
                {
                  "clientRequestId": "create-1",
                  "goal": "Plan Saturday dinner",
                  "tenantId": "${TenantTwo}",
                  "userId": "${UserTwo}",
                  "ownerId": "${UserTwo}",
                  "scope": "orbit.tasks.write"
                }
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.OK, create.status)
        val created = create.decode<TaskDetailResponse>().data!!
        assertEquals("Plan Saturday dinner", created.currentGoal)
        assertEquals(TaskState.Draft, created.state)

        val listForOwner = client.get("/v1/tasks") { actor(UserOne) }
        val summaries = listForOwner.decode<List<TaskSummaryResponse>>().data!!
        assertEquals(listOf(created.id), summaries.map { it.id })

        val listForOtherUser = client.get("/v1/tasks") { actor(UserTwo) }
        assertEquals(emptyList(), listForOtherUser.decode<List<TaskSummaryResponse>>().data)

        val hiddenFromOtherUser = client.get("/v1/tasks/${created.id}") { actor(UserTwo) }
        assertEquals(HttpStatusCode.NotFound, hiddenFromOtherUser.status)

        val detail = client.get("/v1/tasks/${created.id}") { actor(UserOne) }
        assertEquals(created.id, detail.decode<TaskDetailResponse>().data!!.id)
    }

    @Test
    fun `message fixture planning and plan selection routes persist authoritative task snapshots`() = taskApi(
        understanding = UserMessageUnderstanding {
            UnderstandingOutcome(
                userIntent = UserIntent.PlanRequest,
                extractedConstraints = listOf(
                    ConstraintCandidate(
                        kind = ConstraintKind.Location,
                        value = ConstraintValue.Location("Futian"),
                        strength = ConstraintStrength.Hard,
                        evidenceText = "Futian",
                    ),
                ),
                missingInformation = emptyList(),
                clarificationNeeded = false,
                assistantMessageDraft = null,
                metadata = aiMetadata(),
            )
        },
        fixturePlanningEnabled = true,
    ) {
        val task = createTask("create-flow", "Plan a short coffee meetup")

        val afterMessage = client.post("/v1/tasks/${task.id}/messages") {
            actor(UserOne)
            jsonBody(SendTaskMessageRequest("message-1", "Near Futian after 3pm", "Asia/Shanghai"))
        }.decode<TaskDetailResponse>().data!!
        assertEquals(TaskState.Planning, afterMessage.state)
        assertEquals("Near Futian after 3pm", afterMessage.messages.single().content)
        assertNotNull(afterMessage.messages.single().understoodAt)
        assertEquals(ConstraintKindResponse.Location, afterMessage.constraints.single().kind)

        val planning = client.post("/v1/tasks/${task.id}/planning-runs") {
            actor(UserOne)
            jsonBody(GeneratePlansRequest("planning-1"))
        }.decode<GeneratePlansResponse>().data!!
        assertEquals(1, planning.plans.size)

        val selected = client.put("/v1/tasks/${task.id}/selected-plan") {
            actor(UserOne)
            jsonBody(SelectPlanRequest(planning.plans.single().id))
        }.decode<TaskDetailResponse>().data!!
        assertEquals(TaskState.WaitingForApproval, selected.state)
        assertEquals(planning.plans.single().id, selected.selectedPlanId)
    }

    @Test
    fun `task routes map authentication authorization validation conflict and unavailable failures`() = taskApi {
        val unauthenticated = client.get("/v1/tasks")
        assertEquals(HttpStatusCode.Unauthorized, unauthenticated.status)

        val forbidden = client.post("/v1/tasks") {
            actor(UserOne, scopes = "orbit.tasks.read")
            jsonBody(CreateTaskRequest("create-forbidden", "Forbidden write"))
        }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)

        val invalid = client.post("/v1/tasks") {
            actor(UserOne)
            jsonBody(CreateTaskRequest("create-invalid", "   "))
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, invalid.status)

        val created = createTask("create-conflict", "Original goal")
        val conflict = client.post("/v1/tasks") {
            actor(UserOne)
            jsonBody(CreateTaskRequest("create-conflict", "Different goal"))
        }
        assertEquals(HttpStatusCode.Conflict, conflict.status)

        val notFound = client.get("/v1/tasks/${UnknownTask}") { actor(UserOne) }
        assertEquals(HttpStatusCode.NotFound, notFound.status)

        val unavailable = client.post("/v1/tasks/${created.id}/messages") {
            actor(UserOne)
            jsonBody(SendTaskMessageRequest("message-unavailable", "Needs understanding", "Asia/Shanghai"))
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status)
        assertTrue(unavailable.bodyAsText().contains("Task understanding is temporarily unavailable"))
        val pendingDetail = client.get("/v1/tasks/${created.id}") { actor(UserOne) }.decode<TaskDetailResponse>().data!!
        assertEquals(TaskState.Draft, pendingDetail.state)
        assertEquals(1, pendingDetail.messages.size)
        assertEquals(null, pendingDetail.messages.single().understoodAt)

        val invalidTimeZone = client.post("/v1/tasks/${created.id}/messages") {
            actor(UserOne)
            jsonBody(SendTaskMessageRequest("message-invalid-zone", "Needs understanding", "Not/A-Zone"))
        }
        assertEquals(HttpStatusCode.UnprocessableEntity, invalidTimeZone.status)

        val fixturePlanning = client.post("/v1/tasks/${created.id}/planning-runs") {
            actor(UserOne)
            jsonBody(GeneratePlansRequest("planning-disabled"))
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, fixturePlanning.status)
        assertTrue(fixturePlanning.bodyAsText().contains("Fixture planning is not available"))
    }

    @Test
    fun `invalid AI proposal leaves user message persisted without changing task state`() = taskApi(
        understanding = UserMessageUnderstanding { throw InvalidStructuredOutputException("bad structured output") },
    ) {
        val created = createTask("create-invalid-ai", "Plan dinner")

        val unavailable = client.post("/v1/tasks/${created.id}/messages") {
            actor(UserOne)
            jsonBody(SendTaskMessageRequest("message-invalid-ai", "Futian tonight", "Asia/Shanghai"))
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status)

        val detail = client.get("/v1/tasks/${created.id}") { actor(UserOne) }.decode<TaskDetailResponse>().data!!
        assertEquals(TaskState.Draft, detail.state)
        assertEquals("Futian tonight", detail.messages.single().content)
        assertEquals(null, detail.messages.single().understoodAt)
        assertEquals(emptyList(), detail.constraints)
    }

    private fun taskApi(
        understanding: UserMessageUnderstanding? = null,
        fixturePlanningEnabled: Boolean = false,
        block: suspend ApplicationTestBuilder.() -> Unit,
    ) {
        val dataSource = postgresDataSource()
        try {
            cleanAndMigrate(dataSource)
            seedIdentityFixtures(dataSource)
            val ids = UuidSequence()
            val service = TaskService(
                repository = JdbcTaskRepository(dataSource),
                understanding = understanding,
                fixturePlanningEnabled = fixturePlanningEnabled,
                clock = FixedClock,
                uuidFactory = ids::next,
            )
            testApplication {
                application {
                    configureHttpPlatform()
                    routing { taskRoutes(service, HeaderActorResolver) }
                }
                block()
            }
        } finally {
            dataSource.close()
        }
    }

    private suspend fun ApplicationTestBuilder.createTask(
        requestId: String,
        goal: String,
    ): TaskDetailResponse =
        client.post("/v1/tasks") {
            actor(UserOne)
            jsonBody(CreateTaskRequest(requestId, goal))
        }.decode<TaskDetailResponse>().data!!

    private fun HttpRequestBuilder.actor(
        userId: UUID,
        tenantId: UUID = TenantOne,
        scopes: String = "orbit.tasks.read orbit.tasks.write",
    ) {
        header("X-Orbit-Tenant", tenantId.toString())
        header("X-Orbit-User", userId.toString())
        header("X-Orbit-Scopes", scopes)
    }

    private inline fun <reified T> HttpRequestBuilder.jsonBody(body: T) {
        contentType(ContentType.Application.Json)
        setBody(JsonFormat.encodeToString(body))
    }

    private fun HttpRequestBuilder.jsonBody(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend inline fun <reified T> HttpResponse.decode(): KResponse<T> =
        JsonFormat.decodeFromString(bodyAsText())

    private fun postgresDataSource(): HikariDataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres().getJdbcUrl()
                username = postgres().getUsername()
                password = postgres().getPassword()
                maximumPoolSize = 2
            },
        )

    private fun cleanAndMigrate(dataSource: HikariDataSource) {
        Flyway.configure()
            .dataSource(dataSource)
            .cleanDisabled(false)
            .load()
            .clean()
        Flyway.configure()
            .dataSource(dataSource)
            .load()
            .migrate()
    }

    private fun aiMetadata(): UnderstandingMetadata =
        UnderstandingMetadata(
            provider = "test",
            model = "test-model",
            promptVersion = "test-prompt",
            providerRequestId = "test-provider-request",
            attemptCount = 1,
        )

    private fun seedIdentityFixtures(dataSource: HikariDataSource) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, ?)").use { statement ->
                listOf(TenantOne to "Tenant One", TenantTwo to "Tenant Two").forEach { (id, name) ->
                    statement.setObject(1, id)
                    statement.setString(2, name)
                    statement.setTimestamp(3, Timestamp.from(FixedClock.instant()))
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            connection.prepareStatement("INSERT INTO users (id, created_at) VALUES (?, ?)").use { statement ->
                listOf(UserOne, UserTwo).forEach { id ->
                    statement.setObject(1, id)
                    statement.setTimestamp(2, Timestamp.from(FixedClock.instant()))
                    statement.addBatch()
                }
                statement.executeBatch()
            }
            connection.prepareStatement("INSERT INTO tenant_memberships (tenant_id, user_id, created_at) VALUES (?, ?, ?)").use { statement ->
                listOf(TenantOne to UserOne, TenantOne to UserTwo, TenantTwo to UserTwo).forEach { (tenantId, userId) ->
                    statement.setObject(1, tenantId)
                    statement.setObject(2, userId)
                    statement.setTimestamp(3, Timestamp.from(FixedClock.instant()))
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }
    }

    private object HeaderActorResolver : ActorResolver {
        override fun resolve(call: ApplicationCall): ActorContext =
            ActorContext(
                tenantId = call.request.headers["X-Orbit-Tenant"] ?: throw UnauthenticatedException(),
                userId = call.request.headers["X-Orbit-User"] ?: throw UnauthenticatedException(),
                scopes = call.request.headers["X-Orbit-Scopes"]
                    ?.split(" ")
                    ?.filter(String::isNotBlank)
                    ?.toSet()
                    ?: emptySet(),
            )
    }

    private class UuidSequence {
        private var nextValue = 1

        fun next(): UUID =
            UUID.fromString("00000000-0000-0000-0000-${nextValue++.toString().padStart(12, '0')}")
    }

    private companion object {
        val JsonFormat = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = false
        }
        val FixedClock: Clock = Clock.fixed(Instant.parse("2026-08-28T10:35:00Z"), ZoneOffset.UTC)
        val TenantOne: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")
        val TenantTwo: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002")
        val UserOne: UUID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001")
        val UserTwo: UUID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002")
        val UnknownTask: UUID = UUID.fromString("cccccccc-0000-0000-0000-000000000001")
        private var postgresContainer: PostgreSQLContainer? = null

        fun postgres(): PostgreSQLContainer =
            postgresContainer ?: try {
                PostgreSQLContainer("postgres:16-alpine").apply { start() }
                    .also { postgresContainer = it }
            } catch (error: IllegalStateException) {
                PostgresTestGate.unavailable("Task API", error)
            }
    }
}
