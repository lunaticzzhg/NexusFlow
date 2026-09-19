package com.nexusflow.backend.feature.conversation.infrastructure

import com.nexusflow.backend.feature.conversation.domain.AppendConversationUserMessageCommand
import com.nexusflow.backend.feature.conversation.domain.AppendConversationUserMessageResult
import com.nexusflow.backend.feature.responserun.domain.ClaimNextResponseRunCommand
import com.nexusflow.backend.feature.conversation.domain.ConversationId
import com.nexusflow.backend.feature.conversation.domain.CreateConversationCommand
import com.nexusflow.backend.feature.conversation.domain.CreateConversationResult
import com.nexusflow.backend.feature.responserun.domain.ResponseRunId
import com.nexusflow.backend.feature.responserun.domain.ResponseRunStage
import com.nexusflow.backend.feature.responserun.domain.ResponseRunStatus
import com.nexusflow.backend.feature.responserun.infrastructure.JdbcResponseRunRepository
import com.nexusflow.backend.feature.task.TaskFlowIds
import com.nexusflow.backend.feature.task.cleanMigrateAndSeed
import com.nexusflow.backend.feature.conversation.domain.MessageId
import com.nexusflow.backend.feature.conversation.domain.MessageRole
import com.nexusflow.backend.feature.task.domain.TaskOwner
import com.nexusflow.backend.feature.task.domain.TenantId
import com.nexusflow.backend.feature.task.domain.UserId
import com.nexusflow.backend.feature.task.infrastructure.JdbcTaskRepository
import com.nexusflow.backend.feature.task.postgresDataSource
import com.nexusflow.backend.feature.task.seedIdentityFixtures
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import java.sql.SQLException
import java.sql.Timestamp
import java.sql.Types
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdbcConversationRepositoryTest {
    private lateinit var dataSource: HikariDataSource
    private lateinit var repository: JdbcConversationRepository
    private lateinit var turnStartCommitter: JdbcConversationTurnStartCommitter
    private lateinit var responseRunRepository: JdbcResponseRunRepository

    @BeforeTest
    fun setUp() {
        dataSource = postgresDataSource("Conversation repository")
        cleanMigrateAndSeed(dataSource)
        repository = JdbcConversationRepository(dataSource)
        turnStartCommitter = JdbcConversationTurnStartCommitter(dataSource)
        responseRunRepository = JdbcResponseRunRepository(dataSource)
    }

    @AfterTest
    fun tearDown() {
        if (::dataSource.isInitialized) {
            dataSource.close()
        }
    }

    @Test
    fun `fresh migration creates turn ordering and response run schema`() {
        assertTrue(columnExists("conversations", "next_turn_index"))
        assertTrue(columnExists("conversation_messages", "turn_index"))
        assertTrue(tableExists("response_runs"))
        assertTrue(columnExists("response_runs", "origin_trace_id"))
        assertEquals(0, countRows("response_runs"))
    }

    @Test
    fun `create conversation with first message round trips`() =
        runBlocking {
            val owner = owner()
            val conversationId = conversationId("00000000-0000-0000-0000-000000001001")
            val firstMessageId = messageId("00000000-0000-0000-0000-000000001002")

            val created = turnStartCommitter.createConversation(
                CreateConversationCommand(
                    owner = owner,
                    conversationId = conversationId,
                    firstMessageId = firstMessageId,
                    creationRequestId = "create-conversation",
                    clientMessageId = "client-create",
                    text = "Hello Orbit",
                    aiRequestId = "ai-create",
                    now = TaskFlowIds.Now,
                ),
            )

            val detail = assertIs<CreateConversationResult.Created>(created).detail
            assertEquals(conversationId, detail.conversation.id)
            assertEquals(owner, detail.conversation.owner)
            assertEquals("create-conversation", detail.conversation.creationRequestId)
            assertEquals(TaskFlowIds.Now, detail.conversation.createdAt)
            assertEquals(
                listOf("Hello Orbit"),
                detail.messages.map { it.content },
            )
            assertEquals(firstMessageId, detail.messages.single().id)
            assertEquals(MessageRole.User, detail.messages.single().role)
            assertEquals("client-create", detail.messages.single().clientMessageId)
            assertEquals("ai-create", detail.messages.single().aiRequestId)
            assertEquals(1L, detail.messages.single().turnIndex)
            assertEquals(2L, detail.conversation.nextTurnIndex)
            assertEquals(1, detail.responseRuns.size)
            assertEquals(firstMessageId, detail.responseRuns.single().userMessageId)
            assertEquals(1L, detail.responseRuns.single().turnIndex)
            assertEquals(ResponseRunStatus.Queued, detail.responseRuns.single().status)
            assertEquals(ResponseRunStage.Turn, detail.responseRuns.single().stage)

            val loaded = repository.findConversationDetail(owner, conversationId)
            assertEquals(detail, loaded)
        }

    @Test
    fun `response run origin trace is saved and preserved after claim`() =
        runBlocking {
            val originTraceId = "1234567890abcdef1234567890abcdef"
            val created = turnStartCommitter.createConversation(
                CreateConversationCommand(
                    owner = owner(),
                    conversationId = conversationId("00000000-0000-0000-0000-0000000010a1"),
                    firstMessageId = messageId("00000000-0000-0000-0000-0000000010a2"),
                    creationRequestId = "create-traced",
                    clientMessageId = "client-traced",
                    text = "Trace this run",
                    aiRequestId = "ai-traced",
                    now = TaskFlowIds.Now,
                    responseRunId = ResponseRunId(UUID.fromString("00000000-0000-0000-0000-0000000010a3")),
                    originTraceId = originTraceId,
                ),
            )
            val detail = assertIs<CreateConversationResult.Created>(created).detail
            val runId = detail.responseRuns.single().id

            assertEquals(originTraceId, detail.responseRuns.single().originTraceId)
            assertEquals(originTraceId, responseRunRepository.findResponseRun(runId)?.originTraceId)

            val claimed = assertNotNull(
                responseRunRepository.claimNextResponseRun(
                    ClaimNextResponseRunCommand(
                        workerId = "trace-test-worker",
                        now = TaskFlowIds.Now.plusSeconds(1),
                        leaseDuration = Duration.ofSeconds(30),
                        maxAttempts = 3,
                    ),
                ),
            )

            assertEquals(runId, claimed.run.id)
            assertEquals(originTraceId, claimed.run.originTraceId)
            assertEquals(originTraceId, responseRunRepository.findResponseRun(runId)?.originTraceId)
        }

    @Test
    fun `duplicate creation request returns existing for same owner even when generated ids differ`() =
        runBlocking {
            val owner = owner()
            val conversationId = conversationId("00000000-0000-0000-0000-000000001061")

            val created = turnStartCommitter.createConversation(
                CreateConversationCommand(
                    owner = owner,
                    conversationId = conversationId,
                    firstMessageId = messageId("00000000-0000-0000-0000-000000001062"),
                    creationRequestId = "create-replay",
                    clientMessageId = "client-first-message",
                    text = "Start a conversation",
                    aiRequestId = "ai-create",
                    now = TaskFlowIds.Now,
                ),
            )
            assertIs<CreateConversationResult.Created>(created)

            val replay = turnStartCommitter.createConversation(
                CreateConversationCommand(
                    owner = owner,
                    conversationId = conversationId("00000000-0000-0000-0000-000000001063"),
                    firstMessageId = messageId("00000000-0000-0000-0000-000000001064"),
                    creationRequestId = "create-replay",
                    clientMessageId = "client-first-message",
                    text = "Start a conversation",
                    aiRequestId = "ai-create-retry",
                    now = TaskFlowIds.Now.plusSeconds(1),
                ),
            )

            val existing = assertIs<CreateConversationResult.Existing>(replay).detail
            assertEquals(conversationId, existing.conversation.id)
            assertEquals(1, countRows("conversations"))
            assertEquals(1, countRows("conversation_messages"))
            assertEquals(1, countRows("response_runs"))
        }

    @Test
    fun `duplicate creation request conflicts for same owner when first message text differs`() =
        runBlocking {
            val owner = owner()
            assertIs<CreateConversationResult.Created>(
                turnStartCommitter.createConversation(
                    CreateConversationCommand(
                        owner = owner,
                        conversationId = conversationId("00000000-0000-0000-0000-000000001071"),
                        firstMessageId = messageId("00000000-0000-0000-0000-000000001072"),
                        creationRequestId = "create-conflict",
                        clientMessageId = "client-first-message",
                        text = "Original text",
                        aiRequestId = "ai-create",
                        now = TaskFlowIds.Now,
                    ),
                ),
            )

            val conflict = turnStartCommitter.createConversation(
                CreateConversationCommand(
                    owner = owner,
                    conversationId = conversationId("00000000-0000-0000-0000-000000001073"),
                    firstMessageId = messageId("00000000-0000-0000-0000-000000001074"),
                    creationRequestId = "create-conflict",
                    clientMessageId = "client-first-message",
                    text = "Different text",
                    aiRequestId = "ai-create-retry",
                    now = TaskFlowIds.Now.plusSeconds(1),
                ),
            )

            assertEquals(CreateConversationResult.ConflictingMessage, conflict)
            assertEquals(1, countRows("conversations"))
            assertEquals(1, countRows("conversation_messages"))
            assertEquals(1, countRows("response_runs"))
        }

    @Test
    fun `duplicate creation request conflicts for same owner when first client message id differs`() =
        runBlocking {
            val owner = owner()
            assertIs<CreateConversationResult.Created>(
                turnStartCommitter.createConversation(
                    CreateConversationCommand(
                        owner = owner,
                        conversationId = conversationId("00000000-0000-0000-0000-000000001081"),
                        firstMessageId = messageId("00000000-0000-0000-0000-000000001082"),
                        creationRequestId = "create-client-conflict",
                        clientMessageId = "client-first-message",
                        text = "Original text",
                        aiRequestId = "ai-create",
                        now = TaskFlowIds.Now,
                    ),
                ),
            )

            val conflict = turnStartCommitter.createConversation(
                CreateConversationCommand(
                    owner = owner,
                    conversationId = conversationId("00000000-0000-0000-0000-000000001083"),
                    firstMessageId = messageId("00000000-0000-0000-0000-000000001084"),
                    creationRequestId = "create-client-conflict",
                    clientMessageId = "different-client-message",
                    text = "Original text",
                    aiRequestId = "ai-create-retry",
                    now = TaskFlowIds.Now.plusSeconds(1),
                ),
            )

            assertEquals(CreateConversationResult.ConflictingMessage, conflict)
            assertEquals(1, countRows("conversations"))
            assertEquals(1, countRows("conversation_messages"))
            assertEquals(1, countRows("response_runs"))
        }

    @Test
    fun `different owners can use the same creation request id`() =
        runBlocking {
            val owner = owner()
            val otherOwner = TaskOwner(TenantId(TaskFlowIds.TenantOne), UserId(TaskFlowIds.UserTwo))

            assertIs<CreateConversationResult.Created>(
                turnStartCommitter.createConversation(
                    CreateConversationCommand(
                        owner = owner,
                        conversationId = conversationId("00000000-0000-0000-0000-000000001091"),
                        firstMessageId = messageId("00000000-0000-0000-0000-000000001092"),
                        creationRequestId = "shared-create",
                        clientMessageId = "client-first-message",
                        text = "Owner one text",
                        aiRequestId = "ai-create-one",
                        now = TaskFlowIds.Now,
                    ),
                ),
            )
            assertIs<CreateConversationResult.Created>(
                turnStartCommitter.createConversation(
                    CreateConversationCommand(
                        owner = otherOwner,
                        conversationId = conversationId("00000000-0000-0000-0000-000000001093"),
                        firstMessageId = messageId("00000000-0000-0000-0000-000000001094"),
                        creationRequestId = "shared-create",
                        clientMessageId = "client-first-message",
                        text = "Owner two text",
                        aiRequestId = "ai-create-two",
                        now = TaskFlowIds.Now.plusSeconds(1),
                    ),
                ),
            )

            assertEquals(2, countRows("conversations"))
            assertEquals(2, countRows("conversation_messages"))
        }

    @Test
    fun `duplicate client message id is idempotent for same content and conflict for different content`() =
        runBlocking {
            val owner = owner()
            val conversationId = createConversation(owner, "00000000-0000-0000-0000-000000001011")
            val firstAppend = turnStartCommitter.appendUserMessage(
                AppendConversationUserMessageCommand(
                    owner = owner,
                    conversationId = conversationId,
                    messageId = messageId("00000000-0000-0000-0000-000000001012"),
                    clientMessageId = "client-replay",
                    text = "What changed today?",
                    aiRequestId = "ai-replay",
                    now = TaskFlowIds.Now.plusSeconds(1),
                ),
            )
            assertIs<AppendConversationUserMessageResult.Appended>(firstAppend)

            val replay = turnStartCommitter.appendUserMessage(
                AppendConversationUserMessageCommand(
                    owner = owner,
                    conversationId = conversationId,
                    messageId = messageId("00000000-0000-0000-0000-000000001013"),
                    clientMessageId = "client-replay",
                    text = "What changed today?",
                    aiRequestId = "ai-replay-new",
                    now = TaskFlowIds.Now.plusSeconds(2),
                ),
            )
            val replayDetail = assertIs<AppendConversationUserMessageResult.Existing>(replay).detail
            val appended = assertIs<AppendConversationUserMessageResult.Appended>(firstAppend)
            assertEquals(1, replayDetail.messages.count { it.clientMessageId == "client-replay" })
            assertEquals(1, replayDetail.responseRuns.count { it.userMessageId == appended.message.id })

            val conflict = turnStartCommitter.appendUserMessage(
                AppendConversationUserMessageCommand(
                    owner = owner,
                    conversationId = conversationId,
                    messageId = messageId("00000000-0000-0000-0000-000000001014"),
                    clientMessageId = "client-replay",
                    text = "Different text",
                    aiRequestId = "ai-replay-conflict",
                    now = TaskFlowIds.Now.plusSeconds(3),
                ),
            )
            assertEquals(AppendConversationUserMessageResult.ConflictingMessage, conflict)
            assertEquals(2, repository.findConversationDetail(owner, conversationId)!!.messages.size)
            assertEquals(2, countRows("response_runs"))
        }

    @Test
    fun `concurrent appends allocate unique conversation local turn indexes`() =
        runBlocking {
            val owner = owner()
            val conversationId = createConversation(owner, "00000000-0000-0000-0000-000000001015")

            listOf(1, 2).map { index ->
                async {
                    turnStartCommitter.appendUserMessage(
                        AppendConversationUserMessageCommand(
                            owner = owner,
                            conversationId = conversationId,
                            messageId = messageId("00000000-0000-0000-0000-00000000109$index"),
                            clientMessageId = "client-concurrent-$index",
                            text = "Concurrent message $index",
                            aiRequestId = "ai-concurrent-$index",
                            now = TaskFlowIds.Now.plusSeconds(index.toLong()),
                        ),
                    )
                }
            }.awaitAll().forEach { result ->
                assertIs<AppendConversationUserMessageResult.Appended>(result)
            }

            val detail = repository.findConversationDetail(owner, conversationId)!!
            assertEquals(listOf(1L, 2L, 3L), detail.messages.map { it.turnIndex }.sorted())
            assertEquals(listOf(1L, 2L, 3L), detail.responseRuns.map { it.turnIndex }.sorted())
            assertEquals(4L, detail.conversation.nextTurnIndex)
        }

    @Test
    fun `message insert failure rolls back conversation and response run`() =
        runBlocking {
            val owner = owner()
            val firstMessageId = messageId("00000000-0000-0000-0000-000000001016")
            assertIs<CreateConversationResult.Created>(
                turnStartCommitter.createConversation(
                    CreateConversationCommand(
                        owner = owner,
                        conversationId = conversationId("00000000-0000-0000-0000-000000001017"),
                        firstMessageId = firstMessageId,
                        creationRequestId = "create-message-failure-seed",
                        clientMessageId = "client-message-failure-seed",
                        text = "Seed",
                        aiRequestId = "ai-message-failure-seed",
                        now = TaskFlowIds.Now,
                    ),
                ),
            )

            val failedConversationId = conversationId("00000000-0000-0000-0000-000000001018")
            val failedRunId = ResponseRunId(uuid("00000000-0000-0000-0000-000000001019"))
            assertFailsWith<SQLException> {
                turnStartCommitter.createConversation(
                    CreateConversationCommand(
                        owner = owner,
                        conversationId = failedConversationId,
                        firstMessageId = firstMessageId,
                        creationRequestId = "create-message-failure",
                        clientMessageId = "client-message-failure",
                        text = "Duplicate message id",
                        aiRequestId = "ai-message-failure",
                        responseRunId = failedRunId,
                        now = TaskFlowIds.Now.plusSeconds(1),
                    ),
                )
            }

            assertNull(repository.findConversationDetail(owner, failedConversationId))
            assertEquals(0, countResponseRuns(failedRunId))
        }

    @Test
    fun `response run insert failure rolls back appended message and turn allocation`() =
        runBlocking {
            val owner = owner()
            val conversationId = createConversation(owner, "00000000-0000-0000-0000-000000001020")
            val failedMessageId = messageId("00000000-0000-0000-0000-000000001029")
            val failedRunId = ResponseRunId(uuid("00000000-0000-0000-0000-000000001030"))

            assertFailsWith<SQLException> {
                turnStartCommitter.appendUserMessage(
                    AppendConversationUserMessageCommand(
                        owner = owner,
                        conversationId = conversationId,
                        messageId = failedMessageId,
                        clientMessageId = "client-run-failure",
                        text = "This append should roll back",
                        aiRequestId = "ai-run-failure",
                        responseRunId = failedRunId,
                        responseDeadlineAt = TaskFlowIds.Now.minusSeconds(1),
                        now = TaskFlowIds.Now,
                    ),
                )
            }

            val detail = repository.findConversationDetail(owner, conversationId)!!
            assertEquals(1, detail.messages.size)
            assertEquals(2L, detail.conversation.nextTurnIndex)
            assertEquals(0, detail.messages.count { it.id == failedMessageId })
            assertEquals(0, countResponseRuns(failedRunId))
        }

    @Test
    fun `response run foreign key check and one active partial unique constraints are enforced`() =
        runBlocking {
            val owner = owner()
            val conversationId = createConversation(owner, "00000000-0000-0000-0000-000000001032")
            val detail = repository.findConversationDetail(owner, conversationId)!!
            val userMessage = detail.messages.single()

            assertFailsWith<SQLException> {
                insertRawResponseRun(
                    runId = uuid("00000000-0000-0000-0000-000000001033"),
                    conversationId = conversationId.value,
                    userMessageId = userMessage.id.value,
                    turnIndex = userMessage.turnIndex,
                    status = "NOT_A_STATUS",
                )
            }
            assertFailsWith<SQLException> {
                insertRawResponseRun(
                    runId = uuid("00000000-0000-0000-0000-000000001034"),
                    conversationId = conversationId.value,
                    userMessageId = uuid("00000000-0000-0000-0000-000000001035"),
                    turnIndex = userMessage.turnIndex,
                    status = "QUEUED",
                )
            }
            assertFailsWith<SQLException> {
                insertRawResponseRun(
                    runId = uuid("00000000-0000-0000-0000-000000001036"),
                    conversationId = conversationId.value,
                    userMessageId = userMessage.id.value,
                    turnIndex = userMessage.turnIndex,
                    status = "QUEUED",
                )
            }
            Unit
        }

    @Test
    fun `pure conversation has no task and does not appear in task list`() =
        runBlocking {
            val owner = owner()
            val conversationId = createConversation(owner, "00000000-0000-0000-0000-000000001031")

            assertEquals(0, countRows("tasks"))
            assertEquals(0, countTasksLinkedTo(conversationId))
            assertEquals(emptyList(), JdbcTaskRepository(dataSource).listTaskSummaries(owner))
        }

    @Test
    fun `conversation and task ownership are isolated`() =
        runBlocking {
            val owner = owner()
            val otherOwner = TaskOwner(TenantId(TaskFlowIds.TenantOne), UserId(TaskFlowIds.UserTwo))
            val conversationId = createConversation(owner, "00000000-0000-0000-0000-000000001041")

            assertNull(repository.findConversationDetail(otherOwner, conversationId))
            val crossOwnerAppend = turnStartCommitter.appendUserMessage(
                AppendConversationUserMessageCommand(
                    owner = otherOwner,
                    conversationId = conversationId,
                    messageId = messageId("00000000-0000-0000-0000-000000001042"),
                    clientMessageId = "client-cross-owner",
                    text = "Should not append",
                    aiRequestId = "ai-cross-owner",
                    now = TaskFlowIds.Now.plusSeconds(1),
                ),
            )
            assertEquals(AppendConversationUserMessageResult.ConversationNotFound, crossOwnerAppend)

            assertFailsWith<SQLException> {
                insertTaskForOwnerWithConversation(otherOwner, conversationId)
            }
            Unit
        }

    @Test
    fun `one conversation can link to at most one task`() =
        runBlocking {
            val owner = owner()
            val conversationId = createConversation(owner, "00000000-0000-0000-0000-000000001051")

            insertTaskForOwnerWithConversation(
                owner = owner,
                conversationId = conversationId,
                taskId = uuid("00000000-0000-0000-0000-000000001052"),
                creationRequestId = "linked-task-one",
            )
            assertFailsWith<SQLException> {
                insertTaskForOwnerWithConversation(
                    owner = owner,
                    conversationId = conversationId,
                    taskId = uuid("00000000-0000-0000-0000-000000001053"),
                    creationRequestId = "linked-task-two",
                )
            }
            assertEquals(1, countTasksLinkedTo(conversationId))
            Unit
        }

    @Test
    fun `migrates representative V006 task data into conversation foundation`() {
        migrateToV006AndSeed()
        val taskId = uuid("00000000-0000-0000-0000-000000001101")
        val userMessageId = uuid("00000000-0000-0000-0000-000000001102")
        val assistantMessageId = uuid("00000000-0000-0000-0000-000000001103")
        val requirementId = uuid("00000000-0000-0000-0000-000000001104")

        insertRepresentativeV006Task(taskId, userMessageId, assistantMessageId, requirementId)

        Flyway.configure()
            .dataSource(dataSource)
            .load()
            .migrate()

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT tenant_id, owner_user_id, creation_request_id, created_at, updated_at, archived_at
                FROM conversations
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, taskId)
                statement.executeQuery().use { result ->
                    assertEquals(true, result.next())
                    assertEquals(TaskFlowIds.TenantOne, result.getObject("tenant_id", UUID::class.java))
                    assertEquals(TaskFlowIds.UserOne, result.getObject("owner_user_id", UUID::class.java))
                    assertEquals("client-v006", result.getString("creation_request_id"))
                    assertNotNull(result.getTimestamp("created_at"))
                    assertNotNull(result.getTimestamp("updated_at"))
                    assertNull(result.getTimestamp("archived_at"))
                }
            }
            connection.prepareStatement("SELECT conversation_id FROM tasks WHERE id = ?").use { statement ->
                statement.setObject(1, taskId)
                statement.executeQuery().use { result ->
                    assertEquals(true, result.next())
                    assertEquals(taskId, result.getObject("conversation_id", UUID::class.java))
                }
            }
            connection.prepareStatement(
                """
                SELECT id, role, content, client_message_id, ai_request_id, understood_at
                FROM conversation_messages
                WHERE conversation_id = ?
                ORDER BY created_at ASC, id ASC
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, taskId)
                statement.executeQuery().use { result ->
                    assertEquals(true, result.next())
                    assertEquals(userMessageId, result.getObject("id", UUID::class.java))
                    assertEquals("User", result.getString("role"))
                    assertEquals("Plan a relaxed weekend", result.getString("content"))
                    assertEquals("client-v006", result.getString("client_message_id"))
                    assertEquals("ai-v006", result.getString("ai_request_id"))
                    assertEquals(true, result.next())
                    assertEquals(assistantMessageId, result.getObject("id", UUID::class.java))
                    assertEquals("Assistant", result.getString("role"))
                    assertEquals("I captured your weekend planning goal.", result.getString("content"))
                    assertEquals(false, result.next())
                }
            }
            connection.prepareStatement(
                """
                SELECT conversation_evidence_message_id
                FROM task_requirements
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, requirementId)
                statement.executeQuery().use { result ->
                    assertEquals(true, result.next())
                    assertEquals(userMessageId, result.getObject("conversation_evidence_message_id", UUID::class.java))
                }
            }
            assertEquals(true, columnExists("task_requirements", "conversation_evidence_message_id"))
            assertEquals(false, columnExists("task_requirements", "evidence_message_id"))
            assertEquals(true, columnExists("conversations", "creation_request_id"))
            assertEquals(false, tableExists("task_messages"))
            assertFailsWith<SQLException> {
                insertRawConversation(
                    id = uuid("00000000-0000-0000-0000-000000001105"),
                    owner = owner(),
                    creationRequestId = "client-v006",
                )
            }
            assertEquals(1, countRows("conversations"))
            assertEquals(2, countRows("conversation_messages"))
        }
    }

    @Test
    fun `migrates representative V008 conversation data into turn ordering and response runs`() {
        migrateToV008AndSeed()
        val conversationId = uuid("00000000-0000-0000-0000-000000001201")
        val completedUserMessageId = uuid("00000000-0000-0000-0000-000000001202")
        val assistantMessageId = uuid("00000000-0000-0000-0000-000000001203")
        val pendingUserMessageId = uuid("00000000-0000-0000-0000-000000001204")
        val orphanAssistantMessageId = uuid("00000000-0000-0000-0000-000000001205")

        insertRepresentativeV008Conversation(
            conversationId = conversationId,
            completedUserMessageId = completedUserMessageId,
            assistantMessageId = assistantMessageId,
            pendingUserMessageId = pendingUserMessageId,
            orphanAssistantMessageId = orphanAssistantMessageId,
        )

        Flyway.configure()
            .dataSource(dataSource)
            .load()
            .migrate()

        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT next_turn_index
                FROM conversations
                WHERE id = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, conversationId)
                statement.executeQuery().use { result ->
                    assertEquals(true, result.next())
                    assertEquals(4L, result.getLong("next_turn_index"))
                }
            }
            connection.prepareStatement(
                """
                SELECT id, role, turn_index
                FROM conversation_messages
                WHERE conversation_id = ?
                ORDER BY turn_index ASC, CASE role WHEN 'User' THEN 0 ELSE 1 END ASC, created_at ASC, id ASC
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, conversationId)
                statement.executeQuery().use { result ->
                    assertEquals(true, result.next())
                    assertEquals(completedUserMessageId, result.getObject("id", UUID::class.java))
                    assertEquals("User", result.getString("role"))
                    assertEquals(1L, result.getLong("turn_index"))
                    assertEquals(true, result.next())
                    assertEquals(assistantMessageId, result.getObject("id", UUID::class.java))
                    assertEquals("Assistant", result.getString("role"))
                    assertEquals(1L, result.getLong("turn_index"))
                    assertEquals(true, result.next())
                    assertEquals(pendingUserMessageId, result.getObject("id", UUID::class.java))
                    assertEquals("User", result.getString("role"))
                    assertEquals(2L, result.getLong("turn_index"))
                    assertEquals(true, result.next())
                    assertEquals(orphanAssistantMessageId, result.getObject("id", UUID::class.java))
                    assertEquals("Assistant", result.getString("role"))
                    assertEquals(3L, result.getLong("turn_index"))
                    assertEquals(false, result.next())
                }
            }
            connection.prepareStatement(
                """
                SELECT id, user_message_id, turn_index, status, stage, assistant_message_id, completed_at
                FROM response_runs
                WHERE conversation_id = ?
                ORDER BY turn_index ASC, id ASC
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, conversationId)
                statement.executeQuery().use { result ->
                    assertEquals(true, result.next())
                    assertEquals(completedUserMessageId, result.getObject("id", UUID::class.java))
                    assertEquals(completedUserMessageId, result.getObject("user_message_id", UUID::class.java))
                    assertEquals(1L, result.getLong("turn_index"))
                    assertEquals("COMPLETED", result.getString("status"))
                    assertEquals("TURN", result.getString("stage"))
                    assertEquals(assistantMessageId, result.getObject("assistant_message_id", UUID::class.java))
                    assertNotNull(result.getTimestamp("completed_at"))
                    assertEquals(true, result.next())
                    assertEquals(pendingUserMessageId, result.getObject("id", UUID::class.java))
                    assertEquals(pendingUserMessageId, result.getObject("user_message_id", UUID::class.java))
                    assertEquals(2L, result.getLong("turn_index"))
                    assertEquals("QUEUED", result.getString("status"))
                    assertEquals("TURN", result.getString("stage"))
                    assertNull(result.getObject("assistant_message_id", UUID::class.java))
                    assertNull(result.getTimestamp("completed_at"))
                    assertEquals(false, result.next())
                }
            }
        }
    }

    private suspend fun createConversation(
        owner: TaskOwner,
        id: String,
    ): ConversationId {
        val conversationId = conversationId(id)
        val created = turnStartCommitter.createConversation(
            CreateConversationCommand(
                owner = owner,
                conversationId = conversationId,
                firstMessageId = MessageId(UUID.randomUUID()),
                creationRequestId = "create-$id",
                clientMessageId = "client-$id",
                text = "Hello",
                aiRequestId = "ai-$id",
                now = TaskFlowIds.Now,
            ),
        )
        assertIs<CreateConversationResult.Created>(created)
        return conversationId
    }

    private fun migrateToV006AndSeed() {
        Flyway.configure()
            .dataSource(dataSource)
            .cleanDisabled(false)
            .load()
            .clean()
        Flyway.configure()
            .dataSource(dataSource)
            .target("6")
            .load()
            .migrate()
        seedIdentityFixtures(dataSource)
    }

    private fun migrateToV008AndSeed() {
        Flyway.configure()
            .dataSource(dataSource)
            .cleanDisabled(false)
            .load()
            .clean()
        Flyway.configure()
            .dataSource(dataSource)
            .target("8")
            .load()
            .migrate()
        seedIdentityFixtures(dataSource)
    }

    private fun insertRepresentativeV006Task(
        taskId: UUID,
        userMessageId: UUID,
        assistantMessageId: UUID,
        requirementId: UUID,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO tasks (
                    id, tenant_id, owner_user_id, creation_request_id, intent, revision,
                    selected_plan_id, created_at, updated_at, archived_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, taskId)
                statement.setObject(2, TaskFlowIds.TenantOne)
                statement.setObject(3, TaskFlowIds.UserOne)
                statement.setString(4, "client-v006")
                statement.setString(5, "Plan a relaxed weekend")
                statement.setLong(6, 2)
                statement.setNull(7, Types.OTHER)
                statement.setTimestamp(8, timestamp(TaskFlowIds.Now))
                statement.setTimestamp(9, timestamp(TaskFlowIds.Now.plusSeconds(60)))
                statement.setNull(10, Types.TIMESTAMP_WITH_TIMEZONE)
                statement.executeUpdate()
            }
            insertV006TaskMessage(
                taskId = taskId,
                messageId = userMessageId,
                role = "User",
                content = "Plan a relaxed weekend",
                clientMessageId = "client-v006",
                aiRequestId = "ai-v006",
                understoodAt = TaskFlowIds.Now.plusSeconds(30),
                createdAt = TaskFlowIds.Now,
            )
            insertV006TaskMessage(
                taskId = taskId,
                messageId = assistantMessageId,
                role = "Assistant",
                content = "I captured your weekend planning goal.",
                clientMessageId = null,
                aiRequestId = "ai-v006",
                understoodAt = TaskFlowIds.Now.plusSeconds(30),
                createdAt = TaskFlowIds.Now.plusSeconds(31),
            )
            connection.prepareStatement(
                """
                INSERT INTO task_requirements (
                    id, task_id, kind, value_json, strength, source, evidence_message_id, created_at, updated_at
                ) VALUES (?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, requirementId)
                statement.setObject(2, taskId)
                statement.setString(3, "ActivityDomain")
                statement.setString(4, """{"type":"activity_domain","value":"movie"}""")
                statement.setString(5, "Must")
                statement.setString(6, "UserExplicit")
                statement.setObject(7, userMessageId)
                statement.setTimestamp(8, timestamp(TaskFlowIds.Now.plusSeconds(30)))
                statement.setTimestamp(9, timestamp(TaskFlowIds.Now.plusSeconds(30)))
                statement.executeUpdate()
            }
        }
    }

    private fun insertRawConversation(
        id: UUID,
        owner: TaskOwner,
        creationRequestId: String,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO conversations (
                    id, tenant_id, owner_user_id, creation_request_id, next_turn_index, created_at, updated_at, archived_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, id)
                statement.setObject(2, owner.tenantId.value)
                statement.setObject(3, owner.userId.value)
                statement.setString(4, creationRequestId)
                statement.setLong(5, 1L)
                statement.setTimestamp(6, timestamp(TaskFlowIds.Now))
                statement.setTimestamp(7, timestamp(TaskFlowIds.Now))
                statement.setNull(8, Types.TIMESTAMP_WITH_TIMEZONE)
                statement.executeUpdate()
            }
        }
    }

    private fun insertRepresentativeV008Conversation(
        conversationId: UUID,
        completedUserMessageId: UUID,
        assistantMessageId: UUID,
        pendingUserMessageId: UUID,
        orphanAssistantMessageId: UUID,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO conversations (
                    id, tenant_id, owner_user_id, creation_request_id, created_at, updated_at, archived_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, conversationId)
                statement.setObject(2, TaskFlowIds.TenantOne)
                statement.setObject(3, TaskFlowIds.UserOne)
                statement.setString(4, "client-v008")
                statement.setTimestamp(5, timestamp(TaskFlowIds.Now))
                statement.setTimestamp(6, timestamp(TaskFlowIds.Now.plusSeconds(60)))
                statement.setNull(7, Types.TIMESTAMP_WITH_TIMEZONE)
                statement.executeUpdate()
            }
            insertV008ConversationMessage(
                conversationId = conversationId,
                messageId = completedUserMessageId,
                role = "User",
                content = "Plan dinner",
                clientMessageId = "client-v008-one",
                aiRequestId = "ai-v008-one",
                understoodAt = TaskFlowIds.Now.plusSeconds(15),
                createdAt = TaskFlowIds.Now,
            )
            insertV008ConversationMessage(
                conversationId = conversationId,
                messageId = assistantMessageId,
                role = "Assistant",
                content = "Dinner planning is captured.",
                clientMessageId = null,
                aiRequestId = "ai-v008-one",
                understoodAt = TaskFlowIds.Now.plusSeconds(15),
                createdAt = TaskFlowIds.Now.plusSeconds(16),
            )
            insertV008ConversationMessage(
                conversationId = conversationId,
                messageId = pendingUserMessageId,
                role = "User",
                content = "Add a quiet place",
                clientMessageId = "client-v008-two",
                aiRequestId = "ai-v008-two",
                understoodAt = null,
                createdAt = TaskFlowIds.Now.plusSeconds(30),
            )
            insertV008ConversationMessage(
                conversationId = conversationId,
                messageId = orphanAssistantMessageId,
                role = "Assistant",
                content = "Legacy assistant without a matching user request.",
                clientMessageId = null,
                aiRequestId = "ai-v008-orphan",
                understoodAt = TaskFlowIds.Now.plusSeconds(45),
                createdAt = TaskFlowIds.Now.plusSeconds(45),
            )
        }
    }

    private fun insertV006TaskMessage(
        taskId: UUID,
        messageId: UUID,
        role: String,
        content: String,
        clientMessageId: String?,
        aiRequestId: String?,
        understoodAt: Instant?,
        createdAt: Instant,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO task_messages (
                    id, task_id, client_message_id, role, content, ai_request_id, understood_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, messageId)
                statement.setObject(2, taskId)
                statement.setString(3, clientMessageId)
                statement.setString(4, role)
                statement.setString(5, content)
                statement.setString(6, aiRequestId)
                statement.setTimestamp(7, understoodAt?.let(::timestamp))
                statement.setTimestamp(8, timestamp(createdAt))
                statement.executeUpdate()
            }
        }
    }

    private fun insertV008ConversationMessage(
        conversationId: UUID,
        messageId: UUID,
        role: String,
        content: String,
        clientMessageId: String?,
        aiRequestId: String?,
        understoodAt: Instant?,
        createdAt: Instant,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO conversation_messages (
                    id, conversation_id, role, content, client_message_id, ai_request_id, understood_at, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, messageId)
                statement.setObject(2, conversationId)
                statement.setString(3, role)
                statement.setString(4, content)
                statement.setString(5, clientMessageId)
                statement.setString(6, aiRequestId)
                statement.setTimestamp(7, understoodAt?.let(::timestamp))
                statement.setTimestamp(8, timestamp(createdAt))
                statement.executeUpdate()
            }
        }
    }

    private fun insertTaskForOwnerWithConversation(
        owner: TaskOwner,
        conversationId: ConversationId,
        taskId: UUID = uuid("00000000-0000-0000-0000-000000001043"),
        creationRequestId: String = "cross-owner-task",
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO tasks (
                    id, tenant_id, owner_user_id, creation_request_id, intent, revision,
                    selected_plan_id, created_at, updated_at, archived_at, conversation_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, taskId)
                statement.setObject(2, owner.tenantId.value)
                statement.setObject(3, owner.userId.value)
                statement.setString(4, creationRequestId)
                statement.setString(5, "Cross owner task")
                statement.setLong(6, 1)
                statement.setNull(7, Types.OTHER)
                statement.setTimestamp(8, timestamp(TaskFlowIds.Now))
                statement.setTimestamp(9, timestamp(TaskFlowIds.Now))
                statement.setNull(10, Types.TIMESTAMP_WITH_TIMEZONE)
                statement.setObject(11, conversationId.value)
                statement.executeUpdate()
            }
        }
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

    private fun countTasksLinkedTo(conversationId: ConversationId): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM tasks WHERE conversation_id = ?").use { statement ->
                statement.setObject(1, conversationId.value)
                statement.executeQuery().use { result ->
                    result.next()
                    result.getInt(1)
                }
            }
        }

    private fun countResponseRuns(runId: ResponseRunId): Int =
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM response_runs WHERE id = ?").use { statement ->
                statement.setObject(1, runId.value)
                statement.executeQuery().use { result ->
                    result.next()
                    result.getInt(1)
                }
            }
        }

    private fun insertRawResponseRun(
        runId: UUID,
        conversationId: UUID,
        userMessageId: UUID,
        turnIndex: Long,
        status: String,
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                INSERT INTO response_runs (
                    id, conversation_id, user_message_id, turn_index, status, stage, attempt,
                    available_at, deadline_at, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, 'TURN', 0, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setObject(1, runId)
                statement.setObject(2, conversationId)
                statement.setObject(3, userMessageId)
                statement.setLong(4, turnIndex)
                statement.setString(5, status)
                statement.setTimestamp(6, timestamp(TaskFlowIds.Now))
                statement.setTimestamp(7, timestamp(TaskFlowIds.Now.plusSeconds(30 * 60)))
                statement.setTimestamp(8, timestamp(TaskFlowIds.Now))
                statement.setTimestamp(9, timestamp(TaskFlowIds.Now))
                statement.executeUpdate()
            }
        }
    }

    private fun columnExists(
        tableName: String,
        columnName: String,
    ): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT 1
                FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = ?
                  AND column_name = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, tableName)
                statement.setString(2, columnName)
                statement.executeQuery().use { result -> result.next() }
            }
        }

    private fun tableExists(tableName: String): Boolean =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                SELECT 1
                FROM information_schema.tables
                WHERE table_schema = current_schema()
                  AND table_name = ?
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, tableName)
                statement.executeQuery().use { result -> result.next() }
            }
        }

    private fun owner(): TaskOwner = TaskOwner(TenantId(TaskFlowIds.TenantOne), UserId(TaskFlowIds.UserOne))

    private fun conversationId(value: String): ConversationId = ConversationId(uuid(value))

    private fun messageId(value: String): MessageId = MessageId(uuid(value))

    private fun uuid(value: String): UUID = UUID.fromString(value)

    private fun timestamp(value: Instant): Timestamp = Timestamp.from(value)
}
