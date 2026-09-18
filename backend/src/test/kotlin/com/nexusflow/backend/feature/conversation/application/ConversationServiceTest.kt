package com.nexusflow.backend.feature.conversation.application

import com.nexusflow.backend.core.readtool.ReadToolCatalog
import com.nexusflow.backend.feature.conversation.domain.ResponseRunStatus
import com.nexusflow.backend.feature.task.ControlledPlanningReadTool
import com.nexusflow.backend.feature.task.RecordingConversationDecision
import com.nexusflow.backend.feature.task.ScriptedUnderstanding
import com.nexusflow.backend.feature.task.cleanMigrateAndSeed
import com.nexusflow.backend.feature.task.conversationAnswerService
import com.nexusflow.backend.feature.task.createConversationServices
import com.nexusflow.backend.feature.task.directConversationDecision
import com.nexusflow.backend.feature.task.postgresDataSource
import com.nexusflow.backend.feature.task.taskActor
import com.nexusflow.backend.feature.task.understandingOutcome
import com.nexusflow.backend.feature.task.application.PlanningOutcome
import com.nexusflow.backend.feature.task.domain.MessageRole
import com.nexusflow.contracts.backendai.understanding.TurnIntent
import com.nexusflow.contracts.backendai.understanding.UserMessageUnderstanding
import com.nexusflow.observability.TraceContextElement
import com.nexusflow.observability.TraceId
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ConversationServiceTest {
    private lateinit var dataSource: HikariDataSource

    @BeforeTest
    fun setUp() {
        dataSource = postgresDataSource("Conversation service")
        cleanMigrateAndSeed(dataSource)
    }

    @AfterTest
    fun tearDown() {
        if (::dataSource.isInitialized) {
            dataSource.close()
        }
    }

    @Test
    fun `create conversation fast acks durable user message and queued run without invoking AI`() =
        runBlocking {
            val understanding = ScriptedUnderstanding({
                error("Understanding must not run on POST accept")
            })
            val decision = RecordingConversationDecision({ directConversationDecision("你好！") })
            val readTool = ControlledPlanningReadTool()
            val services = createConversationServices(
                dataSource = dataSource,
                readToolCatalog = ReadToolCatalog(listOf(readTool)),
                conversationAnswerService = conversationAnswerService(decision),
                understanding = understanding,
            )

            val result = services.conversationService.createConversation(taskActor(), "conversation-hello", "你好", "Asia/Shanghai")

            assertNull(result.currentTask)
            assertEquals(PlanningOutcome.NotAttempted, result.planningOutcome)
            assertEquals(listOf(MessageRole.User), result.detail.messages.map { it.role })
            assertEquals("你好", result.detail.messages.single().content)
            assertEquals("conversation-hello", result.detail.messages.single().clientMessageId)
            assertEquals(1L, result.detail.messages.single().turnIndex)
            assertNull(result.detail.messages.single().understoodAt)
            assertEquals(1, result.detail.responseRuns.size)
            assertEquals(result.detail.messages.single().id, result.detail.responseRuns.single().userMessageId)
            assertEquals(1L, result.detail.responseRuns.single().turnIndex)
            assertEquals(ResponseRunStatus.Queued, result.detail.responseRuns.single().status)
            assertEquals(emptyList(), understanding.calls)
            assertEquals(emptyList(), decision.requests)
            assertEquals(emptyList(), readTool.requests)
            assertEquals(0, countRows("tasks"))
        }

    @Test
    fun `create conversation stores current coroutine trace as response run origin`() =
        runBlocking {
            val traceId = TraceId.requireValid("abcdefabcdefabcdefabcdefabcdefab")
            val services = createConversationServices(
                dataSource = dataSource,
                understanding = null,
            )

            val result = withContext(TraceContextElement(traceId)) {
                services.conversationService.createConversation(
                    taskActor(),
                    "conversation-trace-origin",
                    "带 trace 的消息",
                    "Asia/Shanghai",
                )
            }

            assertEquals(traceId.value, result.detail.responseRuns.single().originTraceId)
        }

    @Test
    fun `null AI capability still accepts conversation create`() =
        runBlocking {
            val services = createConversationServices(
                dataSource = dataSource,
                understanding = null,
            )

            val result = services.conversationService.createConversation(taskActor(), "conversation-null-ai", "你好", "Asia/Shanghai")

            assertEquals(listOf(MessageRole.User), result.detail.messages.map { it.role })
            assertEquals(ResponseRunStatus.Queued, result.detail.responseRuns.single().status)
            assertNull(result.currentTask)
        }

    @Test
    fun `hanging understanding does not block message accept`() =
        runBlocking {
            val hanging = UserMessageUnderstanding {
                awaitCancellation()
            }
            val services = createConversationServices(
                dataSource = dataSource,
                understanding = hanging,
            )

            val result = withTimeout(500) {
                services.conversationService.createConversation(taskActor(), "conversation-hanging-ai", "你好", "Asia/Shanghai")
            }

            assertEquals(listOf(MessageRole.User), result.detail.messages.map { it.role })
            assertEquals(ResponseRunStatus.Queued, result.detail.responseRuns.single().status)
        }

    @Test
    fun `send message fast acks second durable turn without invoking AI or tools`() =
        runBlocking {
            val understanding = ScriptedUnderstanding({
                understandingOutcome(turnIntent = TurnIntent.Conversation, changes = emptyList())
            })
            val decision = RecordingConversationDecision({ directConversationDecision("不应同步回答。") })
            val readTool = ControlledPlanningReadTool()
            val services = createConversationServices(
                dataSource = dataSource,
                readToolCatalog = ReadToolCatalog(listOf(readTool)),
                conversationAnswerService = conversationAnswerService(decision),
                understanding = understanding,
            )
            val created = services.conversationService.createConversation(
                taskActor(),
                "conversation-send-create",
                "你好",
                "Asia/Shanghai",
            )

            val sent = services.conversationService.sendMessage(
                taskActor(),
                created.detail.conversation.id.value.toString(),
                "conversation-send-message",
                "再补充一句",
                "Asia/Shanghai",
            )

            assertEquals(listOf(MessageRole.User, MessageRole.User), sent.detail.messages.map { it.role })
            assertEquals(listOf(1L, 2L), sent.detail.messages.map { it.turnIndex })
            assertEquals(listOf(1L, 2L), sent.detail.responseRuns.map { it.turnIndex })
            assertEquals(listOf(ResponseRunStatus.Queued, ResponseRunStatus.Queued), sent.detail.responseRuns.map { it.status })
            assertEquals(emptyList(), understanding.calls)
            assertEquals(emptyList(), decision.requests)
            assertEquals(emptyList(), readTool.requests)
            assertEquals(0, countRows("tasks"))
        }

    @Test
    fun `duplicate create and send replay same durable messages and runs without rerunning AI`() =
        runBlocking {
            val understanding = ScriptedUnderstanding({
                error("Understanding must not run for duplicate replay")
            })
            val services = createConversationServices(
                dataSource = dataSource,
                understanding = understanding,
            )

            val firstCreate = services.conversationService.createConversation(taskActor(), "conversation-replay", "你好", "Asia/Shanghai")
            val secondCreate = services.conversationService.createConversation(taskActor(), "conversation-replay", "你好", "Asia/Shanghai")
            val firstMessage = services.conversationService.sendMessage(
                taskActor(),
                firstCreate.detail.conversation.id.value.toString(),
                "conversation-message-replay",
                "再说一次",
                "Asia/Shanghai",
            )
            val secondMessage = services.conversationService.sendMessage(
                taskActor(),
                firstCreate.detail.conversation.id.value.toString(),
                "conversation-message-replay",
                "再说一次",
                "Asia/Shanghai",
            )

            assertEquals(firstCreate.detail.conversation.id, secondCreate.detail.conversation.id)
            assertEquals(firstCreate.detail.messages, secondCreate.detail.messages)
            assertEquals(firstCreate.detail.responseRuns, secondCreate.detail.responseRuns)
            assertEquals(firstMessage.detail.messages, secondMessage.detail.messages)
            assertEquals(firstMessage.detail.responseRuns, secondMessage.detail.responseRuns)
            assertEquals(2, secondMessage.detail.messages.size)
            assertEquals(2, secondMessage.detail.responseRuns.size)
            assertEquals(emptyList(), understanding.calls)
            assertEquals(0, countRows("tasks"))
        }

    @Test
    fun `get conversation returns current durable snapshot`() =
        runBlocking {
            val services = createConversationServices(
                dataSource = dataSource,
                understanding = null,
            )
            val created = services.conversationService.createConversation(
                taskActor(),
                "conversation-snapshot-create",
                "你好",
                "Asia/Shanghai",
            )
            val sent = services.conversationService.sendMessage(
                taskActor(),
                created.detail.conversation.id.value.toString(),
                "conversation-snapshot-send",
                "继续",
                "Asia/Shanghai",
            )

            val loaded = services.conversationService.getConversation(
                taskActor(),
                created.detail.conversation.id.value.toString(),
            )

            assertEquals(sent.detail.messages, loaded.detail.messages)
            assertEquals(sent.detail.responseRuns, loaded.detail.responseRuns)
            assertEquals(listOf(ResponseRunStatus.Queued, ResponseRunStatus.Queued), loaded.detail.responseRuns.map { it.status })
            assertNull(loaded.currentTask)
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
}
