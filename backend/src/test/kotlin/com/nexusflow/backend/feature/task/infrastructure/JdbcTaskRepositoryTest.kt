package com.nexusflow.backend.feature.task.infrastructure

import com.nexusflow.backend.feature.task.domain.AppendUserMessageCommand
import com.nexusflow.backend.feature.task.domain.AppendUserMessageResult
import com.nexusflow.backend.feature.task.domain.ApplyUnderstandingCommand
import com.nexusflow.backend.feature.task.domain.ApplyUnderstandingResult
import com.nexusflow.backend.feature.task.domain.AssistantMessageWrite
import com.nexusflow.backend.feature.task.domain.AiUnderstandingAuditEventType
import com.nexusflow.backend.feature.task.domain.ConfirmedConstraintWrite
import com.nexusflow.backend.feature.task.domain.ConstraintId
import com.nexusflow.backend.feature.task.domain.ConstraintKind
import com.nexusflow.backend.feature.task.domain.ConstraintSource
import com.nexusflow.backend.feature.task.domain.ConstraintStrength
import com.nexusflow.backend.feature.task.domain.ConstraintValue
import com.nexusflow.backend.feature.task.domain.ConversationId
import com.nexusflow.backend.feature.task.domain.CreateFixturePlanningRunCommand
import com.nexusflow.backend.feature.task.domain.CreateFixturePlanningRunResult
import com.nexusflow.backend.feature.task.domain.CreateTaskPersistenceCommand
import com.nexusflow.backend.feature.task.domain.CreateTaskPersistenceResult
import com.nexusflow.backend.feature.task.domain.MessageId
import com.nexusflow.backend.feature.task.domain.Plan
import com.nexusflow.backend.feature.task.domain.PlanEstimatedCost
import com.nexusflow.backend.feature.task.domain.PlanId
import com.nexusflow.backend.feature.task.domain.PlanSourceRef
import com.nexusflow.backend.feature.task.domain.PlanTimelineItem
import com.nexusflow.backend.feature.task.domain.PlanningRunId
import com.nexusflow.backend.feature.task.domain.RecordAiUnderstandingAuditCommand
import com.nexusflow.backend.feature.task.domain.RecordAiUnderstandingAuditResult
import com.nexusflow.backend.feature.task.domain.SelectPlanCommand
import com.nexusflow.backend.feature.task.domain.SelectPlanResult
import com.nexusflow.backend.feature.task.domain.TaskId
import com.nexusflow.backend.feature.task.domain.TaskOwner
import com.nexusflow.backend.feature.task.domain.TaskState
import com.nexusflow.backend.feature.task.domain.TenantId
import com.nexusflow.backend.feature.task.domain.UserId
import com.nexusflow.backend.test.PostgresTestGate
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class JdbcTaskRepositoryTest {
    private lateinit var dataSource: HikariDataSource
    private lateinit var repository: JdbcTaskRepository

    @BeforeTest
    fun setUp() {
        dataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = postgres().getJdbcUrl()
                    username = postgres().getUsername()
                    password = postgres().getPassword()
                    maximumPoolSize = 2
                },
            )
        Flyway.configure()
            .dataSource(dataSource)
            .cleanDisabled(false)
            .load()
            .clean()
        Flyway.configure()
            .dataSource(dataSource)
            .load()
            .migrate()
        repository = JdbcTaskRepository(dataSource)
    }

    @AfterTest
    fun tearDown() {
        if (::dataSource.isInitialized) {
            dataSource.close()
        }
    }

    @Test
    fun `create task persists task and conversation with idempotent request semantics`() =
        runBlocking {
            val owner = insertOwner()
            val command = createTaskCommand(owner, taskId = taskOne, requestId = "create-1", goal = " Plan Saturday ")

            val created = repository.createTaskWithConversation(command)
            val sameRequest = repository.createTaskWithConversation(command.copy(taskId = taskTwo, conversationId = conversationTwo))
            val conflicting = repository.createTaskWithConversation(command.copy(taskId = taskTwo, goal = "Plan Sunday"))

            val createdDetail = assertIs<CreateTaskPersistenceResult.Created>(created).detail
            val existingDetail = assertIs<CreateTaskPersistenceResult.Existing>(sameRequest).detail
            assertIs<CreateTaskPersistenceResult.ConflictingRequest>(conflicting)
            assertEquals(taskOne, createdDetail.task.id)
            assertEquals(taskOne, existingDetail.task.id)
            assertEquals("Plan Saturday", createdDetail.task.initialGoal)
            assertEquals("Plan Saturday", createdDetail.task.title)
            assertEquals(TaskState.Draft, createdDetail.task.state)
            assertEquals(1, countRows("tasks"))
            assertEquals(1, countRows("conversations"))
            assertEquals(1, countAuditEvents("TaskCreated"))
        }

    @Test
    fun `append user message is idempotent by client message id`() =
        runBlocking {
            val owner = insertOwner()
            repository.createTaskWithConversation(createTaskCommand(owner, taskOne, "create-1", "Plan Saturday"))

            val command = appendMessageCommand(owner, taskOne, messageOne, "message-1", "Budget 300")
            val appended = repository.appendUserMessage(command)
            val sameMessage = repository.appendUserMessage(command.copy(messageId = messageTwo))
            val conflicting = repository.appendUserMessage(command.copy(messageId = messageTwo, text = "Budget 400"))

            assertIs<AppendUserMessageResult.Appended>(appended)
            assertEquals(messageOne, assertIs<AppendUserMessageResult.Existing>(sameMessage).message.id)
            assertIs<AppendUserMessageResult.ConflictingMessage>(conflicting)
            assertEquals(1, countRows("task_messages"))
            assertEquals(1, countAuditEvents("MessageSent"))
        }

    @Test
    fun `apply understanding writes constraints assistant message state version and audit atomically`() =
        runBlocking {
            val owner = insertOwner()
            repository.createTaskWithConversation(createTaskCommand(owner, taskOne, "create-1", "Watch Liverpool"))
            repository.appendUserMessage(appendMessageCommand(owner, taskOne, messageOne, "message-1", "预算 300"))

            val applied = repository.applyUnderstanding(
                ApplyUnderstandingCommand(
                    owner = owner,
                    taskId = taskOne,
                    expectedTaskVersion = 1,
                    messageId = messageOne,
                    aiRequestId = "ai-1",
                    constraints = listOf(
                        ConfirmedConstraintWrite(
                            id = constraintOne,
                            kind = ConstraintKind.BudgetLimit,
                            value = ConstraintValue.BudgetLimit(wholeUnits = 300, currencyCode = null),
                            strength = ConstraintStrength.Hard,
                        ),
                    ),
                    assistantMessage = AssistantMessageWrite(messageTwo, "已记录预算。"),
                    targetState = TaskState.Planning,
                    now = later,
                ),
            )
            val stale = repository.applyUnderstanding(
                ApplyUnderstandingCommand(
                    owner = owner,
                    taskId = taskOne,
                    expectedTaskVersion = 1,
                    messageId = messageOne,
                    aiRequestId = "ai-stale",
                    constraints = emptyList(),
                    assistantMessage = null,
                    targetState = TaskState.CollectingConstraints,
                    now = later,
                ),
            )

            val detail = assertIs<ApplyUnderstandingResult.Applied>(applied).detail
            assertIs<ApplyUnderstandingResult.StaleTaskVersion>(stale)
            assertEquals(TaskState.Planning, detail.task.state)
            assertEquals(2, detail.task.version)
            assertEquals(2, detail.messages.size)
            assertEquals("ai-1", detail.messages.first { it.id == messageOne }.aiRequestId)
            assertEquals(later, detail.messages.first { it.id == messageOne }.understoodAt)
            assertEquals(ConstraintSource.UserExplicit, detail.constraints.single().source)
            assertEquals(ConstraintValue.BudgetLimit(300, null), detail.constraints.single().value)
            assertEquals(1, countRows("task_constraints"))
            assertEquals(1, countAuditEvents("ConstraintConfirmed"))
            assertEquals(1, countAuditEvents("TaskStateChanged"))
        }

    @Test
    fun `AI understanding audit events persist with safe metadata`() =
        runBlocking {
            val owner = insertOwner()
            repository.createTaskWithConversation(createTaskCommand(owner, taskOne, "create-1", "Watch Liverpool"))

            val started = repository.recordAiUnderstandingAudit(
                aiAuditCommand(
                    owner = owner,
                    eventType = AiUnderstandingAuditEventType.Started,
                    outcome = "started",
                ),
            )
            val succeeded = repository.recordAiUnderstandingAudit(
                aiAuditCommand(
                    owner = owner,
                    eventType = AiUnderstandingAuditEventType.Succeeded,
                    outcome = "succeeded",
                    provider = "openai",
                    model = "gpt-test",
                    promptVersion = "understand-user-message-v1",
                    providerRequestId = "resp-1",
                    attemptCount = 2,
                    latencyMs = 125,
                ),
            )
            val failed = repository.recordAiUnderstandingAudit(
                aiAuditCommand(
                    owner = owner,
                    eventType = AiUnderstandingAuditEventType.Failed,
                    outcome = "failed",
                    failureCategory = "InvalidStructuredOutputException",
                    latencyMs = 50,
                ),
            )

            assertIs<RecordAiUnderstandingAuditResult.Recorded>(started)
            assertIs<RecordAiUnderstandingAuditResult.Recorded>(succeeded)
            assertIs<RecordAiUnderstandingAuditResult.Recorded>(failed)
            assertEquals(1, countAuditEvents("AiUnderstandingStarted"))
            assertEquals(1, countAuditEvents("AiUnderstandingSucceeded"))
            assertEquals(1, countAuditEvents("AiUnderstandingFailed"))

            val successMetadata = auditMetadata("AiUnderstandingSucceeded")
            assertEquals("UnderstandUserMessage", successMetadata["capability"]?.jsonPrimitive?.content)
            assertEquals("1", successMetadata["taskVersion"]?.jsonPrimitive?.content)
            assertEquals("openai", successMetadata["provider"]?.jsonPrimitive?.content)
            assertEquals("gpt-test", successMetadata["model"]?.jsonPrimitive?.content)
            assertEquals("understand-user-message-v1", successMetadata["promptVersion"]?.jsonPrimitive?.content)
            assertEquals("resp-1", successMetadata["providerRequestId"]?.jsonPrimitive?.content)
            assertEquals("2", successMetadata["attemptCount"]?.jsonPrimitive?.content)
            assertEquals("succeeded", successMetadata["outcome"]?.jsonPrimitive?.content)
            assertEquals("125", successMetadata["latencyMs"]?.jsonPrimitive?.content)

            val failedMetadata = auditMetadata("AiUnderstandingFailed")
            assertEquals("InvalidStructuredOutputException", failedMetadata["failureCategory"]?.jsonPrimitive?.content)
            assertFalse(successMetadata.toString().contains("Watch Liverpool"))
            assertFalse(failedMetadata.toString().contains("OPENAI_API_KEY"))
        }

    @Test
    fun `actor scoped lookup hides tasks from other actors`() =
        runBlocking {
            val owner = insertOwner()
            val otherOwner = insertOwner()
            repository.createTaskWithConversation(createTaskCommand(owner, taskOne, "create-1", "Plan Saturday"))

            assertNull(repository.findTaskDetail(otherOwner, taskOne))
            assertEquals(emptyList(), repository.listTasks(otherOwner))
        }

    @Test
    fun `fixture planning run is persisted once and plan selection belongs to task`() =
        runBlocking {
            val owner = insertOwner()
            repository.createTaskWithConversation(createTaskCommand(owner, taskOne, "create-1", "Watch Liverpool"))
            repository.appendUserMessage(appendMessageCommand(owner, taskOne, messageOne, "message-1", "预算 300"))
            repository.applyUnderstanding(
                ApplyUnderstandingCommand(
                    owner = owner,
                    taskId = taskOne,
                    expectedTaskVersion = 1,
                    messageId = messageOne,
                    aiRequestId = "ai-1",
                    constraints = emptyList(),
                    assistantMessage = null,
                    targetState = TaskState.Planning,
                    now = later,
                ),
            )

            val plan = fixturePlan(taskOne, planningRunOne, planOne)
            val created = repository.createFixturePlanningRun(
                CreateFixturePlanningRunCommand(
                    owner = owner,
                    taskId = taskOne,
                    planningRunId = planningRunOne,
                    clientRequestId = "planning-1",
                    plans = listOf(plan),
                    now = later,
                ),
            )
            val sameRequest = repository.createFixturePlanningRun(
                CreateFixturePlanningRunCommand(
                    owner = owner,
                    taskId = taskOne,
                    planningRunId = planningRunTwo,
                    clientRequestId = "planning-1",
                    plans = listOf(fixturePlan(taskOne, planningRunTwo, planTwo)),
                    now = later,
                ),
            )
            val selected = repository.selectPlan(SelectPlanCommand(owner, taskOne, planOne, later))
            val otherPlanSelection = repository.selectPlan(SelectPlanCommand(owner, taskOne, planTwo, later))

            assertEquals(planningRunOne, assertIs<CreateFixturePlanningRunResult.Created>(created).planningRun.id)
            assertEquals(planningRunOne, assertIs<CreateFixturePlanningRunResult.Existing>(sameRequest).planningRun.id)
            assertEquals(planOne, assertIs<SelectPlanResult.Selected>(selected).detail.task.selectedPlanId)
            assertIs<SelectPlanResult.PlanNotFound>(otherPlanSelection)
            assertEquals(1, countRows("planning_runs"))
            assertEquals(1, countRows("plans"))
            assertEquals(1, countAuditEvents("PlanningRequested"))
            assertEquals(1, countAuditEvents("PlanGenerated"))
            assertEquals(1, countAuditEvents("PlanSelected"))
        }

    private fun createTaskCommand(
        owner: TaskOwner,
        taskId: TaskId,
        requestId: String,
        goal: String,
    ): CreateTaskPersistenceCommand =
        CreateTaskPersistenceCommand(
            owner = owner,
            taskId = taskId,
            conversationId = if (taskId == taskOne) conversationOne else conversationTwo,
            creationRequestId = requestId,
            goal = goal,
            now = now,
        )

    private fun appendMessageCommand(
        owner: TaskOwner,
        taskId: TaskId,
        messageId: MessageId,
        clientMessageId: String,
        text: String,
    ): AppendUserMessageCommand =
        AppendUserMessageCommand(
            owner = owner,
            taskId = taskId,
            messageId = messageId,
            clientMessageId = clientMessageId,
            text = text,
            aiRequestId = "ai-1",
            now = now,
        )

    private fun aiAuditCommand(
        owner: TaskOwner,
        eventType: AiUnderstandingAuditEventType,
        outcome: String,
        provider: String? = null,
        model: String? = null,
        promptVersion: String? = null,
        providerRequestId: String? = null,
        attemptCount: Int? = null,
        latencyMs: Long? = null,
        failureCategory: String? = null,
    ): RecordAiUnderstandingAuditCommand =
        RecordAiUnderstandingAuditCommand(
            owner = owner,
            taskId = taskOne,
            taskVersion = 1,
            aiRequestId = "ai-1",
            eventType = eventType,
            provider = provider,
            model = model,
            promptVersion = promptVersion,
            providerRequestId = providerRequestId,
            attemptCount = attemptCount,
            outcome = outcome,
            latencyMs = latencyMs,
            failureCategory = failureCategory,
            now = now,
        )

    private fun fixturePlan(
        taskId: TaskId,
        planningRunId: PlanningRunId,
        planId: PlanId,
    ): Plan =
        Plan(
            id = planId,
            taskId = taskId,
            planningRunId = planningRunId,
            direction = "fixture",
            title = "Watch Liverpool",
            summary = "Fixture plan",
            timeline = listOf(PlanTimelineItem("Match", later, null, "Home")),
            estimatedCost = PlanEstimatedCost(wholeUnits = 300, currencyCode = null),
            commuteMinutes = 0,
            satisfiedConstraintIds = listOf(constraintOne),
            tradeoffs = listOf("Fixture only"),
            reasons = listOf("M0 proof"),
            sourceRefs = listOf(PlanSourceRef("Fixture", null)),
            validUntil = later,
            createdAt = later,
        )

    private fun insertOwner(): TaskOwner {
        val owner = TaskOwner(TenantId(UUID.randomUUID()), UserId(UUID.randomUUID()))
        dataSource.connection.use { connection ->
            connection.prepareStatement("INSERT INTO users (id, created_at) VALUES (?, ?)").use { statement ->
                statement.setObject(1, owner.userId.value)
                statement.setTimestamp(2, Timestamp.from(now))
                statement.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO tenants (id, name, created_at) VALUES (?, ?, ?)").use { statement ->
                statement.setObject(1, owner.tenantId.value)
                statement.setString(2, "Personal")
                statement.setTimestamp(3, Timestamp.from(now))
                statement.executeUpdate()
            }
        }
        return owner
    }

    private fun countRows(table: String): Int =
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM $table").use { result ->
                    result.next()
                    result.getInt(1)
                }
            }
        }

    private fun countAuditEvents(eventType: String): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM task_audit_events WHERE event_type = ?").use { statement ->
                statement.setString(1, eventType)
                statement.executeQuery().use { result ->
                    result.next()
                    result.getInt(1)
                }
            }
        }

    private fun auditMetadata(eventType: String) =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT metadata_json::text FROM task_audit_events WHERE event_type = ?").use { statement ->
                statement.setString(1, eventType)
                statement.executeQuery().use { result ->
                    result.next()
                    JsonFormat.parseToJsonElement(result.getString(1)).jsonObject
                }
            }
        }

    private companion object {
        val JsonFormat = Json { ignoreUnknownKeys = true }
        private var postgresContainer: PostgreSQLContainer? = null

        fun postgres(): PostgreSQLContainer =
            postgresContainer ?: try {
                PostgreSQLContainer("postgres:16-alpine").apply { start() }
                    .also { postgresContainer = it }
            } catch (error: IllegalStateException) {
                PostgresTestGate.unavailable("Task repository", error)
            }

        val now: Instant = Instant.parse("2026-08-28T10:15:00Z")
        val later: Instant = Instant.parse("2026-08-28T10:16:00Z")
        val taskOne = TaskId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
        val taskTwo = TaskId(UUID.fromString("00000000-0000-0000-0000-000000000002"))
        val conversationOne = ConversationId(UUID.fromString("10000000-0000-0000-0000-000000000001"))
        val conversationTwo = ConversationId(UUID.fromString("10000000-0000-0000-0000-000000000002"))
        val messageOne = MessageId(UUID.fromString("20000000-0000-0000-0000-000000000001"))
        val messageTwo = MessageId(UUID.fromString("20000000-0000-0000-0000-000000000002"))
        val constraintOne = ConstraintId(UUID.fromString("30000000-0000-0000-0000-000000000001"))
        val planningRunOne = PlanningRunId(UUID.fromString("40000000-0000-0000-0000-000000000001"))
        val planningRunTwo = PlanningRunId(UUID.fromString("40000000-0000-0000-0000-000000000002"))
        val planOne = PlanId(UUID.fromString("50000000-0000-0000-0000-000000000001"))
        val planTwo = PlanId(UUID.fromString("50000000-0000-0000-0000-000000000002"))
    }
}
