package com.nexusflow.backend.feature.conversation.infrastructure

import com.nexusflow.backend.feature.conversation.domain.AppendConversationUserMessageCommand
import com.nexusflow.backend.feature.conversation.domain.AppendConversationUserMessageResult
import com.nexusflow.backend.feature.responserun.domain.ClaimNextResponseRunCommand
import com.nexusflow.backend.feature.responserun.domain.CompleteResponseRunAttemptCommand
import com.nexusflow.backend.feature.conversation.domain.ConversationId
import com.nexusflow.backend.feature.conversation.domain.CreateConversationCommand
import com.nexusflow.backend.feature.conversation.domain.CreateConversationResult
import com.nexusflow.backend.feature.responserun.domain.HeartbeatResponseRunLeaseCommand
import com.nexusflow.backend.feature.responserun.domain.MarkResponseRunRetryableCommand
import com.nexusflow.backend.feature.responserun.domain.ResponseRunFailureCategory
import com.nexusflow.backend.feature.responserun.domain.ResponseRunId
import com.nexusflow.backend.feature.responserun.domain.ResponseRunStatus
import com.nexusflow.backend.feature.responserun.infrastructure.JdbcResponseRunRepository
import com.nexusflow.backend.feature.task.TaskFlowIds
import com.nexusflow.backend.feature.task.cleanMigrateAndSeed
import com.nexusflow.backend.feature.conversation.domain.MessageId
import com.nexusflow.backend.feature.task.domain.TaskOwner
import com.nexusflow.backend.feature.task.domain.TenantId
import com.nexusflow.backend.feature.task.domain.UserId
import com.nexusflow.backend.feature.task.postgresDataSource
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class JdbcResponseRunLifecycleTest {
    private lateinit var dataSource: HikariDataSource
    private lateinit var conversationTurnStartCommitter: JdbcConversationTurnStartCommitter
    private lateinit var repository: JdbcResponseRunRepository

    @BeforeTest
    fun setUp() {
        dataSource = postgresDataSource("Response run lifecycle")
        cleanMigrateAndSeed(dataSource)
        conversationTurnStartCommitter = JdbcConversationTurnStartCommitter(dataSource)
        repository = JdbcResponseRunRepository(dataSource)
    }

    @AfterTest
    fun tearDown() {
        if (::dataSource.isInitialized) {
            dataSource.close()
        }
    }

    @Test
    fun `two workers do not claim the same run`() =
        runBlocking {
            val runId = createConversation("00000000-0000-0000-0000-000000002001").runId

            val claims = listOf("worker-one", "worker-two")
                .map { workerId -> async { claim(workerId) } }
                .awaitAll()
                .filterNotNull()

            assertEquals(1, claims.size)
            assertEquals(runId, claims.single().run.id)
        }

    @Test
    fun `different conversations can be claimed independently`() =
        runBlocking {
            val first = createConversation("00000000-0000-0000-0000-000000002011")
            val second = createConversation("00000000-0000-0000-0000-000000002021")

            val firstClaim = assertNotNull(claim("worker-one"))
            val secondClaim = assertNotNull(claim("worker-two"))

            assertEquals(
                setOf(first.runId, second.runId),
                setOf(firstClaim.run.id, secondClaim.run.id),
            )
        }

    @Test
    fun `same conversation later turn is blocked until earlier turn terminal`() =
        runBlocking {
            val created = createConversation("00000000-0000-0000-0000-000000002031")
            val secondRunId = appendMessage(
                conversationId = created.conversationId,
                idSeed = "00000000-0000-0000-0000-000000002041",
            )

            val firstClaim = assertNotNull(claim("worker-one"))
            assertEquals(created.runId, firstClaim.run.id)
            assertNull(claim("worker-two"))

            assertEquals(
                true,
                repository.completeResponseRunAttempt(
                    CompleteResponseRunAttemptCommand(
                        responseRunId = firstClaim.run.id,
                        attempt = firstClaim.run.attempt,
                        now = TaskFlowIds.Now.plusSeconds(2),
                    ),
                ),
            )
            val secondClaim = assertNotNull(claim("worker-two", now = TaskFlowIds.Now.plusSeconds(3)))
            assertEquals(secondRunId, secondClaim.run.id)
        }

    @Test
    fun `expired lease is reclaimed with incremented attempt`() =
        runBlocking {
            val created = createConversation("00000000-0000-0000-0000-000000002051")
            val firstClaim = assertNotNull(claim("worker-one", leaseDuration = Duration.ofSeconds(5)))

            val reclaimed = assertNotNull(
                claim(
                    workerId = "worker-two",
                    now = TaskFlowIds.Now.plusSeconds(6),
                    leaseDuration = Duration.ofSeconds(5),
                ),
            )

            assertEquals(created.runId, reclaimed.run.id)
            assertEquals(firstClaim.run.attempt + 1, reclaimed.run.attempt)
            assertEquals("worker-two", reclaimed.run.leaseOwner)
        }

    @Test
    fun `heartbeat extends current attempt lease`() =
        runBlocking {
            val created = createConversation("00000000-0000-0000-0000-000000002056")
            val claim = assertNotNull(claim("worker-one", leaseDuration = Duration.ofSeconds(5)))

            assertEquals(
                true,
                repository.heartbeatResponseRunLease(
                    HeartbeatResponseRunLeaseCommand(
                        responseRunId = claim.run.id,
                        attempt = claim.run.attempt,
                        workerId = "worker-one",
                        now = TaskFlowIds.Now.plusSeconds(3),
                        leaseDuration = Duration.ofSeconds(5),
                    ),
                ),
            )

            val run = repository.findResponseRun(created.runId)
            assertEquals(ResponseRunStatus.Processing, run?.status)
            assertEquals(TaskFlowIds.Now.plusSeconds(8), run?.leaseExpiresAt)
        }

    @Test
    fun `stale worker cannot heartbeat or finalize after reclaim`() =
        runBlocking {
            createConversation("00000000-0000-0000-0000-000000002061")
            val firstClaim = assertNotNull(claim("worker-one", leaseDuration = Duration.ofSeconds(5)))
            val reclaimed = assertNotNull(
                claim(
                    workerId = "worker-two",
                    now = TaskFlowIds.Now.plusSeconds(6),
                    leaseDuration = Duration.ofSeconds(5),
                ),
            )

            assertEquals(
                false,
                repository.heartbeatResponseRunLease(
                    HeartbeatResponseRunLeaseCommand(
                        responseRunId = firstClaim.run.id,
                        attempt = firstClaim.run.attempt,
                        workerId = "worker-one",
                        now = TaskFlowIds.Now.plusSeconds(7),
                        leaseDuration = Duration.ofSeconds(5),
                    ),
                ),
            )
            assertEquals(
                false,
                repository.completeResponseRunAttempt(
                    CompleteResponseRunAttemptCommand(
                        responseRunId = firstClaim.run.id,
                        attempt = firstClaim.run.attempt,
                        now = TaskFlowIds.Now.plusSeconds(7),
                    ),
                ),
            )
            assertEquals(ResponseRunStatus.Processing, repository.findResponseRun(reclaimed.run.id)!!.status)
            assertEquals(reclaimed.run.attempt, repository.findResponseRun(reclaimed.run.id)!!.attempt)
        }

    @Test
    fun `deadline moves run to timed out and prevents claim`() =
        runBlocking {
            val created = createConversation(
                idSeed = "00000000-0000-0000-0000-000000002071",
                deadlineAt = TaskFlowIds.Now.plusSeconds(1),
            )

            assertNull(claim("worker-one", now = TaskFlowIds.Now.plusSeconds(2)))

            val run = repository.findResponseRun(created.runId)
            assertEquals(ResponseRunStatus.TimedOut, run?.status)
            assertEquals(ResponseRunFailureCategory.RunTimeout, run?.failureCategory)
        }

    @Test
    fun `max attempts moves retryable run to failed terminal instead of infinite retry`() =
        runBlocking {
            val created = createConversation("00000000-0000-0000-0000-000000002081")
            val firstClaim = assertNotNull(claim("worker-one", maxAttempts = 2))
            assertEquals(
                true,
                repository.markResponseRunRetryable(
                    MarkResponseRunRetryableCommand(
                        responseRunId = firstClaim.run.id,
                        attempt = firstClaim.run.attempt,
                        now = TaskFlowIds.Now.plusSeconds(1),
                        retryAt = TaskFlowIds.Now.plusSeconds(1),
                        failureCategory = ResponseRunFailureCategory.ProviderTemporary,
                    ),
                ),
            )
            val secondClaim = assertNotNull(claim("worker-two", now = TaskFlowIds.Now.plusSeconds(2), maxAttempts = 2))
            assertEquals(2, secondClaim.run.attempt)
            assertEquals(
                true,
                repository.markResponseRunRetryable(
                    MarkResponseRunRetryableCommand(
                        responseRunId = secondClaim.run.id,
                        attempt = secondClaim.run.attempt,
                        now = TaskFlowIds.Now.plusSeconds(3),
                        retryAt = TaskFlowIds.Now.plusSeconds(3),
                        failureCategory = ResponseRunFailureCategory.ProviderTemporary,
                    ),
                ),
            )

            assertNull(claim("worker-three", now = TaskFlowIds.Now.plusSeconds(4), maxAttempts = 2))

            val run = repository.findResponseRun(created.runId)
            assertEquals(ResponseRunStatus.Failed, run?.status)
            assertEquals(ResponseRunFailureCategory.WorkerLost, run?.failureCategory)
        }

    private suspend fun claim(
        workerId: String,
        now: Instant = TaskFlowIds.Now,
        leaseDuration: Duration = Duration.ofSeconds(30),
        maxAttempts: Int = 3,
    ) = repository.claimNextResponseRun(
        ClaimNextResponseRunCommand(
            workerId = workerId,
            now = now,
            leaseDuration = leaseDuration,
            maxAttempts = maxAttempts,
        ),
    )

    private suspend fun createConversation(
        idSeed: String,
        deadlineAt: Instant = TaskFlowIds.Now.plusSeconds(30 * 60),
    ): CreatedRun {
        val conversationId = ConversationId(uuid(idSeed))
        val runId = ResponseRunId(UUID.randomUUID())
        val created = conversationTurnStartCommitter.createConversation(
            CreateConversationCommand(
                owner = owner(),
                conversationId = conversationId,
                firstMessageId = MessageId(UUID.randomUUID()),
                creationRequestId = "create-$idSeed",
                clientMessageId = "client-$idSeed",
                text = "Hello",
                aiRequestId = "ai-$idSeed",
                responseRunId = runId,
                responseDeadlineAt = deadlineAt,
                now = TaskFlowIds.Now,
            ),
        )
        assertEquals(true, created is CreateConversationResult.Created)
        return CreatedRun(conversationId, runId)
    }

    private suspend fun appendMessage(
        conversationId: ConversationId,
        idSeed: String,
    ): ResponseRunId {
        val runId = ResponseRunId(UUID.randomUUID())
        val appended = conversationTurnStartCommitter.appendUserMessage(
            AppendConversationUserMessageCommand(
                owner = owner(),
                conversationId = conversationId,
                messageId = MessageId(UUID.randomUUID()),
                clientMessageId = "client-$idSeed",
                text = "Next",
                aiRequestId = "ai-$idSeed",
                responseRunId = runId,
                now = TaskFlowIds.Now.plusSeconds(1),
            ),
        )
        assertEquals(true, appended is AppendConversationUserMessageResult.Appended)
        return runId
    }

    private fun owner(): TaskOwner = TaskOwner(TenantId(TaskFlowIds.TenantOne), UserId(TaskFlowIds.UserOne))

    private fun uuid(value: String): UUID = UUID.fromString(value)

    private data class CreatedRun(
        val conversationId: ConversationId,
        val runId: ResponseRunId,
    )
}
