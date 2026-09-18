package com.nexusflow.backend.feature.conversation.infrastructure

import com.nexusflow.backend.feature.conversation.domain.AppendConversationUserMessageCommand
import com.nexusflow.backend.feature.conversation.domain.AppendConversationUserMessageResult
import com.nexusflow.backend.feature.conversation.domain.CancelResponseRunCommand
import com.nexusflow.backend.feature.conversation.domain.CancelResponseRunResult
import com.nexusflow.backend.feature.conversation.domain.ClaimNextResponseRunCommand
import com.nexusflow.backend.feature.conversation.domain.ClaimedResponseRun
import com.nexusflow.backend.feature.conversation.domain.CompletePlanningResultCommand
import com.nexusflow.backend.feature.conversation.domain.CompleteResponseRunAttemptCommand
import com.nexusflow.backend.feature.conversation.domain.ConsumeConversationAnswerResultCommand
import com.nexusflow.backend.feature.conversation.domain.ConsumeResponseRunIgnoreReason
import com.nexusflow.backend.feature.conversation.domain.ConsumeResponseRunResult
import com.nexusflow.backend.feature.conversation.domain.Conversation
import com.nexusflow.backend.feature.conversation.domain.ConversationDetail
import com.nexusflow.backend.feature.conversation.domain.ConversationId
import com.nexusflow.backend.feature.conversation.domain.ConversationMessage
import com.nexusflow.backend.feature.conversation.domain.ConversationRepository
import com.nexusflow.backend.feature.conversation.domain.CreateConversationCommand
import com.nexusflow.backend.feature.conversation.domain.CreateConversationResult
import com.nexusflow.backend.feature.conversation.domain.FailResponseRunAttemptCommand
import com.nexusflow.backend.feature.conversation.domain.HeartbeatResponseRunLeaseCommand
import com.nexusflow.backend.feature.conversation.domain.MarkResponseRunRetryableCommand
import com.nexusflow.backend.feature.conversation.domain.QueuePlanningStageFromResultCommand
import com.nexusflow.backend.feature.conversation.domain.ResponseRun
import com.nexusflow.backend.feature.conversation.domain.ResponseRunFailureCategory
import com.nexusflow.backend.feature.conversation.domain.ResponseRunId
import com.nexusflow.backend.feature.conversation.domain.ResponseRunResult
import com.nexusflow.backend.feature.conversation.domain.ResponseRunResultPayload
import com.nexusflow.backend.feature.conversation.domain.ResponseRunResultType
import com.nexusflow.backend.feature.conversation.domain.ResponseRunStage
import com.nexusflow.backend.feature.conversation.domain.ResponseRunStatus
import com.nexusflow.backend.feature.conversation.domain.RetryResponseRunCommand
import com.nexusflow.backend.feature.conversation.domain.RetryResponseRunResult
import com.nexusflow.backend.feature.conversation.domain.StoreResponseRunResult
import com.nexusflow.backend.feature.conversation.domain.StoreResponseRunResultCommand
import com.nexusflow.backend.feature.task.domain.MessageId
import com.nexusflow.backend.feature.task.domain.MessageRole
import com.nexusflow.backend.feature.task.domain.TaskId
import com.nexusflow.backend.feature.task.domain.TaskOwner
import com.nexusflow.backend.feature.task.domain.TenantId
import com.nexusflow.backend.feature.task.domain.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcConversationRepository(
    private val dataSource: DataSource,
) : ConversationRepository {
    override suspend fun createConversation(command: CreateConversationCommand): CreateConversationResult =
        blocking {
            inTransaction { connection ->
                val insertedConversation = connection.insertConversation(command)
                if (!insertedConversation) {
                    val existingConversation = connection.findConversationByCreationRequest(
                        command.owner,
                        command.creationRequestId,
                    ) ?: return@inTransaction CreateConversationResult.ConflictingConversation
                    val existing = connection.loadConversationDetail(command.owner, existingConversation.id)
                        ?: return@inTransaction CreateConversationResult.ConflictingConversation
                    val existingMessage = existing.messages.singleOrNull { it.clientMessageId == command.clientMessageId }
                    return@inTransaction when {
                        existingMessage == null -> CreateConversationResult.ConflictingMessage
                        existingMessage.content == command.text -> CreateConversationResult.Existing(existing)
                        else -> CreateConversationResult.ConflictingMessage
                    }
                }

                val message = ConversationMessage(
                    id = command.firstMessageId,
                    conversationId = command.conversationId,
                    role = MessageRole.User,
                    content = command.text,
                    clientMessageId = command.clientMessageId,
                    aiRequestId = command.aiRequestId,
                    turnIndex = FIRST_TURN_INDEX,
                    understoodAt = null,
                    createdAt = command.now,
                )
                connection.insertConversationMessage(message)
                connection.insertResponseRun(
                    id = command.responseRunId,
                    conversationId = command.conversationId,
                    userMessageId = command.firstMessageId,
                    turnIndex = FIRST_TURN_INDEX,
                    availableAt = command.now,
                    deadlineAt = command.responseDeadlineAt,
                    createdAt = command.now,
                    originTraceId = command.originTraceId,
                )
                CreateConversationResult.Created(connection.loadConversationDetail(command.owner, command.conversationId)!!, message)
            }
        }

    override suspend fun findConversationDetail(
        owner: TaskOwner,
        conversationId: ConversationId,
    ): ConversationDetail? =
        blocking {
            dataSource.connection.use { connection ->
                connection.loadConversationDetail(owner, conversationId)
            }
        }

    override suspend fun findConversationDetailForResponseRun(responseRunId: ResponseRunId): ConversationDetail? =
        blocking {
            dataSource.connection.use { connection ->
                val run = connection.findResponseRun(responseRunId) ?: return@use null
                val conversation = connection.findConversationById(run.conversationId) ?: return@use null
                connection.loadConversationDetail(conversation.owner, run.conversationId)
            }
        }

    override suspend fun appendUserMessage(command: AppendConversationUserMessageCommand): AppendConversationUserMessageResult =
        blocking {
            inTransaction { connection ->
                val conversation = connection.lockConversation(command.owner, command.conversationId)
                    ?: return@inTransaction AppendConversationUserMessageResult.ConversationNotFound
                connection.findMessageByClientId(command.conversationId, command.clientMessageId)?.let { existing ->
                    return@inTransaction if (existing.content == command.text) {
                        AppendConversationUserMessageResult.Existing(
                            connection.loadConversationDetail(command.owner, command.conversationId)!!,
                        )
                    } else {
                        AppendConversationUserMessageResult.ConflictingMessage
                    }
                }

                val turnIndex = conversation.nextTurnIndex
                connection.incrementNextTurnIndex(command.conversationId, turnIndex + 1)
                val message = ConversationMessage(
                    id = command.messageId,
                    conversationId = command.conversationId,
                    role = MessageRole.User,
                    content = command.text,
                    clientMessageId = command.clientMessageId,
                    aiRequestId = command.aiRequestId,
                    turnIndex = turnIndex,
                    understoodAt = null,
                    createdAt = command.now,
                )
                connection.insertConversationMessage(message)
                connection.insertResponseRun(
                    id = command.responseRunId,
                    conversationId = command.conversationId,
                    userMessageId = command.messageId,
                    turnIndex = turnIndex,
                    availableAt = command.now,
                    deadlineAt = command.responseDeadlineAt,
                    createdAt = command.now,
                    originTraceId = command.originTraceId,
                )
                connection.touchConversation(command.conversationId, command.now)
                AppendConversationUserMessageResult.Appended(
                    detail = connection.loadConversationDetail(command.owner, command.conversationId)!!,
                    message = message,
                )
            }
        }

    override suspend fun claimNextResponseRun(command: ClaimNextResponseRunCommand): ClaimedResponseRun? =
        blocking {
            inTransaction { connection ->
                connection.claimNextResponseRun(command)?.let(::ClaimedResponseRun)
            }
        }

    override suspend fun heartbeatResponseRunLease(command: HeartbeatResponseRunLeaseCommand): Boolean =
        blocking {
            dataSource.connection.use { connection ->
                connection.heartbeatResponseRunLease(command)
            }
        }

    override suspend fun completeResponseRunAttempt(command: CompleteResponseRunAttemptCommand): Boolean =
        blocking {
            dataSource.connection.use { connection ->
                connection.completeResponseRunAttempt(command)
            }
        }

    override suspend fun failResponseRunAttempt(command: FailResponseRunAttemptCommand): Boolean =
        blocking {
            dataSource.connection.use { connection ->
                connection.failResponseRunAttempt(command)
            }
        }

    override suspend fun markResponseRunRetryable(command: MarkResponseRunRetryableCommand): Boolean =
        blocking {
            dataSource.connection.use { connection ->
                connection.markResponseRunRetryable(command)
            }
        }

    override suspend fun findResponseRun(responseRunId: ResponseRunId): ResponseRun? =
        blocking {
            dataSource.connection.use { connection ->
                connection.findResponseRun(responseRunId)
            }
        }

    override suspend fun cancelResponseRun(command: CancelResponseRunCommand): CancelResponseRunResult =
        blocking {
            inTransaction { connection ->
                val existing = connection.lockResponseRun(command.responseRunId)
                    ?: return@inTransaction CancelResponseRunResult.NotFound
                if (existing.status == ResponseRunStatus.Cancelled) {
                    return@inTransaction CancelResponseRunResult.Existing(existing)
                }
                if (!existing.status.isCancellable()) {
                    return@inTransaction CancelResponseRunResult.Existing(existing)
                }
                connection.cancelResponseRun(command)
                    ?: error("response run was not readable after cancellation")
            }
        }

    override suspend fun retryResponseRun(command: RetryResponseRunCommand): RetryResponseRunResult =
        blocking {
            inTransaction { connection ->
                val existing = connection.lockResponseRun(command.responseRunId)
                    ?: return@inTransaction RetryResponseRunResult.NotFound
                if (!existing.status.isManuallyRetryable()) {
                    return@inTransaction RetryResponseRunResult.NotRetryable
                }
                connection.retryResponseRun(command)
                    ?: error("response run was not readable after retry queueing")
            }
        }

    override suspend fun storeResponseRunResult(command: StoreResponseRunResultCommand): StoreResponseRunResult =
        blocking {
            inTransaction { connection ->
                val run = connection.findResponseRun(command.responseRunId)
                    ?: return@inTransaction StoreResponseRunResult.StaleAttempt
                if (run.attempt != command.attempt) {
                    return@inTransaction StoreResponseRunResult.StaleAttempt
                }
                val resultType = command.payload.resultType()
                val inserted = connection.insertResponseRunResult(command, resultType)
                val result = if (inserted) {
                    connection.findResponseRunResult(command.responseRunId, command.attempt)
                } else {
                    connection.replaceConsumedPlanningUnderstandingResult(command, resultType)
                        ?: connection.findResponseRunResult(command.responseRunId, command.attempt)
                }
                    ?: error("response run result was not readable after insert")
                if (inserted) StoreResponseRunResult.Stored(result) else StoreResponseRunResult.Existing(result)
            }
        }

    override suspend fun findResponseRunResult(
        responseRunId: ResponseRunId,
        attempt: Int,
    ): ResponseRunResult? =
        blocking {
            dataSource.connection.use { connection ->
                connection.findResponseRunResult(responseRunId, attempt)
            }
        }

    override suspend fun findConsumableResponseRunForResult(result: ResponseRunResult): ResponseRun? =
        blocking {
            inTransaction { connection ->
                val stored = connection.lockResponseRunResult(result.runId, result.attempt)
                    ?: return@inTransaction null
                if (stored.consumedAt != null) return@inTransaction null
                val run = connection.lockResponseRun(result.runId) ?: return@inTransaction null
                if (run.attempt == stored.attempt && run.status.isConsumable()) run else null
            }
        }

    override suspend fun consumeConversationAnswerResult(
        command: ConsumeConversationAnswerResultCommand,
    ): ConsumeResponseRunResult =
        blocking {
            inTransaction { connection ->
                connection.consumeConversationAnswerResult(command)
            }
        }

    override suspend fun queuePlanningStageFromResult(command: QueuePlanningStageFromResultCommand): ConsumeResponseRunResult =
        blocking {
            inTransaction { connection ->
                connection.queuePlanningStageFromResult(command)
            }
        }

    override suspend fun completePlanningResult(command: CompletePlanningResultCommand): ConsumeResponseRunResult =
        blocking {
            inTransaction { connection ->
                connection.completePlanningResult(command)
            }
        }

    private suspend fun <T> blocking(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun <T> inTransaction(block: (Connection) -> T): T = dataSource.connection.use { connection ->
        connection.autoCommit = false
        try {
            block(connection).also { connection.commit() }
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        }
    }

    private fun Connection.insertConversation(command: CreateConversationCommand): Boolean =
        prepareStatement(
            """
            INSERT INTO conversations (
                id, tenant_id, owner_user_id, creation_request_id, next_turn_index, created_at, updated_at, archived_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, NULL)
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, command.conversationId.value)
            statement.setObject(2, command.owner.tenantId.value)
            statement.setObject(3, command.owner.userId.value)
            statement.setString(4, command.creationRequestId)
            statement.setLong(5, FIRST_TURN_INDEX + 1)
            statement.setInstant(6, command.now)
            statement.setInstant(7, command.now)
            statement.executeUpdate() == 1
        }

    private fun Connection.insertConversationMessage(message: ConversationMessage) {
        prepareStatement(
            """
            INSERT INTO conversation_messages (
                id, conversation_id, role, content, client_message_id, ai_request_id, turn_index, understood_at, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, message.id.value)
            statement.setObject(2, message.conversationId.value)
            statement.setString(3, message.role.name)
            statement.setString(4, message.content)
            statement.setString(5, message.clientMessageId)
            statement.setString(6, message.aiRequestId)
            statement.setLong(7, message.turnIndex)
            statement.setInstant(8, message.understoodAt)
            statement.setInstant(9, message.createdAt)
            statement.executeUpdate()
        }
    }

    private fun Connection.insertResponseRun(
        id: ResponseRunId,
        conversationId: ConversationId,
        userMessageId: MessageId,
        turnIndex: Long,
        availableAt: Instant,
        deadlineAt: Instant,
        createdAt: Instant,
        originTraceId: String?,
    ) {
        prepareStatement(
            """
            INSERT INTO response_runs (
                id, conversation_id, user_message_id, turn_index, status, stage, attempt,
                available_at, lease_owner, lease_expires_at, deadline_at, expected_task_id,
                expected_task_revision, assistant_message_id, failure_category, created_at,
                started_at, updated_at, completed_at, origin_trace_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, ?, NULL, NULL, NULL, NULL, ?, NULL, ?, NULL, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, id.value)
            statement.setObject(2, conversationId.value)
            statement.setObject(3, userMessageId.value)
            statement.setLong(4, turnIndex)
            statement.setString(5, ResponseRunStatus.Queued.toDatabaseValue())
            statement.setString(6, ResponseRunStage.Turn.toDatabaseValue())
            statement.setInt(7, INITIAL_RESPONSE_RUN_ATTEMPT)
            statement.setInstant(8, availableAt)
            statement.setInstant(9, deadlineAt)
            statement.setInstant(10, createdAt)
            statement.setInstant(11, createdAt)
            statement.setString(12, originTraceId)
            statement.executeUpdate()
        }
    }

    private fun Connection.incrementNextTurnIndex(
        conversationId: ConversationId,
        nextTurnIndex: Long,
    ) {
        prepareStatement("UPDATE conversations SET next_turn_index = ? WHERE id = ?").use { statement ->
            statement.setLong(1, nextTurnIndex)
            statement.setObject(2, conversationId.value)
            statement.executeUpdate()
        }
    }

    private fun Connection.touchConversation(
        conversationId: ConversationId,
        now: Instant,
    ) {
        prepareStatement("UPDATE conversations SET updated_at = ? WHERE id = ?").use { statement ->
            statement.setInstant(1, now)
            statement.setObject(2, conversationId.value)
            statement.executeUpdate()
        }
    }

    private fun Connection.markMessageUnderstood(
        messageId: MessageId,
        aiRequestId: String,
        now: Instant,
    ) {
        prepareStatement("UPDATE conversation_messages SET ai_request_id = ?, understood_at = ? WHERE id = ?").use { statement ->
            statement.setString(1, aiRequestId)
            statement.setInstant(2, now)
            statement.setObject(3, messageId.value)
            statement.executeUpdate()
        }
    }

    private fun Connection.claimNextResponseRun(command: ClaimNextResponseRunCommand): ResponseRun? {
        require(command.maxAttempts > 0) { "maxAttempts must be positive" }
        val leaseExpiresAt = command.now.plus(command.leaseDuration)
        return prepareStatement(
            """
            WITH timed_out AS (
                UPDATE response_runs
                SET status = ?,
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    updated_at = ?,
                    completed_at = ?,
                    failure_category = COALESCE(failure_category, ?)
                WHERE status IN (?, ?, ?, ?)
                  AND deadline_at <= ?
                RETURNING id
            ),
            exhausted AS (
                UPDATE response_runs
                SET status = ?,
                    lease_owner = NULL,
                    lease_expires_at = NULL,
                    updated_at = ?,
                    completed_at = ?,
                    failure_category = ?
                WHERE status IN (?, ?, ?)
                  AND deadline_at > ?
                  AND attempt >= ?
                  AND (
                      (status = ? AND available_at <= ?) OR
                      (status IN (?, ?) AND lease_expires_at < ?)
                  )
                RETURNING id
            ),
            candidate AS (
                SELECT id
                FROM response_runs AS run
                WHERE run.deadline_at > ?
                  AND run.attempt < ?
                  AND (
                      (run.status IN (?, ?) AND run.available_at <= ?) OR
                      (run.status IN (?, ?) AND run.lease_expires_at < ?)
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM response_runs AS earlier
                      WHERE earlier.conversation_id = run.conversation_id
                        AND earlier.turn_index < run.turn_index
                        AND earlier.status NOT IN (?, ?, ?, ?)
                  )
                ORDER BY run.created_at ASC, run.id ASC
                FOR UPDATE SKIP LOCKED
                LIMIT 1
            )
            UPDATE response_runs AS run
            SET status = ?,
                attempt = run.attempt + 1,
                lease_owner = ?,
                lease_expires_at = ?,
                started_at = COALESCE(run.started_at, ?),
                updated_at = ?,
                failure_category = NULL
            FROM candidate
            WHERE run.id = candidate.id
            RETURNING
                run.id,
                run.conversation_id,
                run.user_message_id,
                run.turn_index,
                run.status,
                run.stage,
                run.attempt,
                run.available_at,
                run.lease_owner,
                run.lease_expires_at,
                run.deadline_at,
                run.expected_task_id,
                run.expected_task_revision,
                run.assistant_message_id,
                run.failure_category,
                run.origin_trace_id,
                run.created_at,
                run.started_at,
                run.updated_at,
                run.completed_at
            """.trimIndent(),
        ).use { statement ->
            var index = 1
            statement.setString(index++, ResponseRunStatus.TimedOut.toDatabaseValue())
            statement.setInstant(index++, command.now)
            statement.setInstant(index++, command.now)
            statement.setString(index++, ResponseRunFailureCategory.RunTimeout.toDatabaseValue())
            statement.setString(index++, ResponseRunStatus.Queued.toDatabaseValue())
            statement.setString(index++, ResponseRunStatus.Processing.toDatabaseValue())
            statement.setString(index++, ResponseRunStatus.Streaming.toDatabaseValue())
            statement.setString(index++, ResponseRunStatus.FailedRetryable.toDatabaseValue())
            statement.setInstant(index++, command.now)

            statement.setString(index++, ResponseRunStatus.Failed.toDatabaseValue())
            statement.setInstant(index++, command.now)
            statement.setInstant(index++, command.now)
            statement.setString(index++, ResponseRunFailureCategory.WorkerLost.toDatabaseValue())
            statement.setString(index++, ResponseRunStatus.FailedRetryable.toDatabaseValue())
            statement.setString(index++, ResponseRunStatus.Processing.toDatabaseValue())
            statement.setString(index++, ResponseRunStatus.Streaming.toDatabaseValue())
            statement.setInstant(index++, command.now)
            statement.setInt(index++, command.maxAttempts)
            statement.setString(index++, ResponseRunStatus.FailedRetryable.toDatabaseValue())
            statement.setInstant(index++, command.now)
            statement.setString(index++, ResponseRunStatus.Processing.toDatabaseValue())
            statement.setString(index++, ResponseRunStatus.Streaming.toDatabaseValue())
            statement.setInstant(index++, command.now)

            statement.setInstant(index++, command.now)
            statement.setInt(index++, command.maxAttempts)
            statement.setString(index++, ResponseRunStatus.Queued.toDatabaseValue())
            statement.setString(index++, ResponseRunStatus.FailedRetryable.toDatabaseValue())
            statement.setInstant(index++, command.now)
            statement.setString(index++, ResponseRunStatus.Processing.toDatabaseValue())
            statement.setString(index++, ResponseRunStatus.Streaming.toDatabaseValue())
            statement.setInstant(index++, command.now)
            statement.setString(index++, ResponseRunStatus.Completed.toDatabaseValue())
            statement.setString(index++, ResponseRunStatus.Failed.toDatabaseValue())
            statement.setString(index++, ResponseRunStatus.TimedOut.toDatabaseValue())
            statement.setString(index++, ResponseRunStatus.Cancelled.toDatabaseValue())

            statement.setString(index++, ResponseRunStatus.Processing.toDatabaseValue())
            statement.setString(index++, command.workerId)
            statement.setInstant(index++, leaseExpiresAt)
            statement.setInstant(index++, command.now)
            statement.setInstant(index, command.now)
            statement.executeQuery().use { result ->
                if (result.next()) result.responseRun() else null
            }
        }
    }

    private fun Connection.heartbeatResponseRunLease(command: HeartbeatResponseRunLeaseCommand): Boolean =
        prepareStatement(
            """
            UPDATE response_runs
            SET lease_expires_at = ?,
                updated_at = ?
            WHERE id = ?
              AND attempt = ?
              AND lease_owner = ?
              AND status IN (?, ?)
              AND deadline_at > ?
            """.trimIndent(),
        ).use { statement ->
            statement.setInstant(1, command.now.plus(command.leaseDuration))
            statement.setInstant(2, command.now)
            statement.setObject(3, command.responseRunId.value)
            statement.setInt(4, command.attempt)
            statement.setString(5, command.workerId)
            statement.setString(6, ResponseRunStatus.Processing.toDatabaseValue())
            statement.setString(7, ResponseRunStatus.Streaming.toDatabaseValue())
            statement.setInstant(8, command.now)
            statement.executeUpdate() == 1
        }

    private fun Connection.completeResponseRunAttempt(command: CompleteResponseRunAttemptCommand): Boolean =
        prepareStatement(
            """
            UPDATE response_runs
            SET status = ?,
                lease_owner = NULL,
                lease_expires_at = NULL,
                updated_at = ?,
                completed_at = ?
            WHERE id = ?
              AND attempt = ?
              AND status IN (?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, ResponseRunStatus.Completed.toDatabaseValue())
            statement.setInstant(2, command.now)
            statement.setInstant(3, command.now)
            statement.setObject(4, command.responseRunId.value)
            statement.setInt(5, command.attempt)
            statement.setString(6, ResponseRunStatus.Processing.toDatabaseValue())
            statement.setString(7, ResponseRunStatus.Streaming.toDatabaseValue())
            statement.executeUpdate() == 1
        }

    private fun Connection.failResponseRunAttempt(command: FailResponseRunAttemptCommand): Boolean =
        prepareStatement(
            """
            UPDATE response_runs
            SET status = ?,
                lease_owner = NULL,
                lease_expires_at = NULL,
                failure_category = ?,
                updated_at = ?,
                completed_at = ?
            WHERE id = ?
              AND attempt = ?
              AND status IN (?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, ResponseRunStatus.Failed.toDatabaseValue())
            statement.setString(2, command.failureCategory.toDatabaseValue())
            statement.setInstant(3, command.now)
            statement.setInstant(4, command.now)
            statement.setObject(5, command.responseRunId.value)
            statement.setInt(6, command.attempt)
            statement.setString(7, ResponseRunStatus.Processing.toDatabaseValue())
            statement.setString(8, ResponseRunStatus.Streaming.toDatabaseValue())
            statement.executeUpdate() == 1
        }

    private fun Connection.markResponseRunRetryable(command: MarkResponseRunRetryableCommand): Boolean =
        prepareStatement(
            """
            UPDATE response_runs
            SET status = ?,
                available_at = ?,
                lease_owner = NULL,
                lease_expires_at = NULL,
                failure_category = ?,
                updated_at = ?
            WHERE id = ?
              AND attempt = ?
              AND status IN (?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, ResponseRunStatus.FailedRetryable.toDatabaseValue())
            statement.setInstant(2, command.retryAt)
            statement.setString(3, command.failureCategory.toDatabaseValue())
            statement.setInstant(4, command.now)
            statement.setObject(5, command.responseRunId.value)
            statement.setInt(6, command.attempt)
            statement.setString(7, ResponseRunStatus.Processing.toDatabaseValue())
            statement.setString(8, ResponseRunStatus.Streaming.toDatabaseValue())
            statement.executeUpdate() == 1
        }

    private fun Connection.cancelResponseRun(command: CancelResponseRunCommand): CancelResponseRunResult.Cancelled? =
        prepareStatement(
            """
            UPDATE response_runs
            SET status = ?,
                lease_owner = NULL,
                lease_expires_at = NULL,
                updated_at = ?,
                completed_at = ?
            WHERE id = ?
              AND status IN (?, ?, ?, ?)
            RETURNING
                id, conversation_id, user_message_id, turn_index, status, stage, attempt,
                available_at, lease_owner, lease_expires_at, deadline_at, expected_task_id,
                expected_task_revision, assistant_message_id, failure_category, origin_trace_id, created_at,
                started_at, updated_at, completed_at
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, ResponseRunStatus.Cancelled.toDatabaseValue())
            statement.setInstant(2, command.now)
            statement.setInstant(3, command.now)
            statement.setObject(4, command.responseRunId.value)
            statement.setString(5, ResponseRunStatus.Queued.toDatabaseValue())
            statement.setString(6, ResponseRunStatus.Processing.toDatabaseValue())
            statement.setString(7, ResponseRunStatus.Streaming.toDatabaseValue())
            statement.setString(8, ResponseRunStatus.FailedRetryable.toDatabaseValue())
            statement.executeQuery().use { result ->
                if (result.next()) CancelResponseRunResult.Cancelled(result.responseRun()) else null
            }
        }

    private fun Connection.retryResponseRun(command: RetryResponseRunCommand): RetryResponseRunResult.Queued? =
        prepareStatement(
            """
            UPDATE response_runs
            SET status = ?,
                available_at = ?,
                lease_owner = NULL,
                lease_expires_at = NULL,
                failure_category = NULL,
                updated_at = ?,
                completed_at = NULL
            WHERE id = ?
              AND status IN (?, ?, ?)
            RETURNING
                id, conversation_id, user_message_id, turn_index, status, stage, attempt,
                available_at, lease_owner, lease_expires_at, deadline_at, expected_task_id,
                expected_task_revision, assistant_message_id, failure_category, origin_trace_id, created_at,
                started_at, updated_at, completed_at
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, ResponseRunStatus.Queued.toDatabaseValue())
            statement.setInstant(2, command.now)
            statement.setInstant(3, command.now)
            statement.setObject(4, command.responseRunId.value)
            statement.setString(5, ResponseRunStatus.FailedRetryable.toDatabaseValue())
            statement.setString(6, ResponseRunStatus.Failed.toDatabaseValue())
            statement.setString(7, ResponseRunStatus.TimedOut.toDatabaseValue())
            statement.executeQuery().use { result ->
                if (result.next()) RetryResponseRunResult.Queued(result.responseRun()) else null
            }
        }

    private fun Connection.insertResponseRunResult(
        command: StoreResponseRunResultCommand,
        resultType: ResponseRunResultType,
    ): Boolean =
        prepareStatement(
            """
            INSERT INTO response_run_results (
                run_id, attempt, result_type, payload, created_at, consumed_at
            ) VALUES (?, ?, ?, ?::jsonb, ?, NULL)
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, command.responseRunId.value)
            statement.setInt(2, command.attempt)
            statement.setString(3, resultType.toDatabaseValue())
            statement.setString(4, JsonFormat.encodeToString(command.payload))
            statement.setInstant(5, command.now)
            statement.executeUpdate() == 1
        }

    private fun Connection.replaceConsumedPlanningUnderstandingResult(
        command: StoreResponseRunResultCommand,
        resultType: ResponseRunResultType,
    ): ResponseRunResult? {
        if (command.payload !is ResponseRunResultPayload.PlanningResult) return null
        if (resultType != ResponseRunResultType.PlanningResult) return null
        val existing = findResponseRunResult(command.responseRunId, command.attempt) ?: return null
        if (existing.resultType != ResponseRunResultType.PlanningUnderstanding || existing.consumedAt == null) return null
        val run = findResponseRun(command.responseRunId) ?: return null
        if (run.stage != ResponseRunStage.Planning || run.status.isTerminal() || run.attempt != command.attempt) return null
        val updated = prepareStatement(
            """
            UPDATE response_run_results
            SET result_type = ?,
                payload = ?::jsonb,
                created_at = ?,
                consumed_at = NULL
            WHERE run_id = ?
              AND attempt = ?
              AND result_type = ?
              AND consumed_at IS NOT NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, resultType.toDatabaseValue())
            statement.setString(2, JsonFormat.encodeToString(command.payload))
            statement.setInstant(3, command.now)
            statement.setObject(4, command.responseRunId.value)
            statement.setInt(5, command.attempt)
            statement.setString(6, ResponseRunResultType.PlanningUnderstanding.toDatabaseValue())
            statement.executeUpdate() == 1
        }
        return if (updated) findResponseRunResult(command.responseRunId, command.attempt) else null
    }

    private fun Connection.consumeConversationAnswerResult(
        command: ConsumeConversationAnswerResultCommand,
    ): ConsumeResponseRunResult {
        val result = lockResponseRunResult(command.result.runId, command.result.attempt)
            ?: return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.ResultMissing)
        if (result.consumedAt != null) return ConsumeResponseRunResult.AlreadyConsumed()
        if (result.resultType != ResponseRunResultType.ConversationAnswer) {
            return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.ResultTypeMismatch)
        }
        val run = lockResponseRun(command.result.runId)
            ?: return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.RunMissing)
        if (run.attempt != result.attempt) {
            return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.AttemptMismatch)
        }
        if (!run.status.isConsumable()) {
            return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.RunNotConsumable)
        }
        val payload = command.payload
        if (payload.conversationId != run.conversationId.value.toString()) {
            return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.PayloadConversationMismatch)
        }
        if (payload.userMessageId != run.userMessageId.value.toString()) {
            return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.PayloadUserMessageMismatch)
        }
        val conversation = findConversationById(run.conversationId)
            ?: error("conversation missing after planning stage queue")
        val userMessage = findMessage(run.conversationId, run.userMessageId)
            ?: return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.UserMessageMissing)
        if (userMessage.role != MessageRole.User) {
            return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.UserMessageRoleMismatch)
        }
        if (userMessage.aiRequestId != payload.aiRequestId) {
            return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.AiRequestMismatch)
        }
        if (userMessage.turnIndex != run.turnIndex) {
            return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.TurnIndexMismatch)
        }
        val existingAssistant = findAssistantMessage(run.conversationId, payload.aiRequestId)
        if (existingAssistant != null) {
            if (existingAssistant.turnIndex != run.turnIndex) {
                return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.AssistantMessageConflict)
            }
            val completed = completeResponseRunFromResult(
                runId = run.id,
                attempt = run.attempt,
                assistantMessageId = existingAssistant.id,
                now = command.now,
            )
            if (!completed) {
                val latestRun = findResponseRun(run.id)
                if (
                    latestRun?.status == ResponseRunStatus.Completed &&
                    latestRun.assistantMessageId == existingAssistant.id
                ) {
                    markResponseRunResultConsumed(result.runId, result.attempt, command.now)
                    return ConsumeResponseRunResult.AlreadyConsumed()
                }
                error("existing assistant message could not reconcile response run")
            }
            markResponseRunResultConsumed(result.runId, result.attempt, command.now)
            touchConversation(run.conversationId, command.now)
            val detail = loadConversationDetail(conversation.owner, run.conversationId)
                ?: error("conversation detail missing after response run reconciliation")
            return ConsumeResponseRunResult.Consumed(detail)
        }
        val assistantMessageId = MessageId(UUID.fromString(payload.assistantMessageId))
        val assistant = ConversationMessage(
            id = assistantMessageId,
            conversationId = run.conversationId,
            role = MessageRole.Assistant,
            content = payload.text,
            clientMessageId = null,
            aiRequestId = payload.aiRequestId,
            turnIndex = run.turnIndex,
            understoodAt = command.now,
            createdAt = command.now,
        )
        insertConversationMessage(assistant)
        markMessageUnderstood(run.userMessageId, payload.aiRequestId, command.now)
        val completed = completeResponseRunFromResult(
            runId = run.id,
            attempt = run.attempt,
            assistantMessageId = assistantMessageId,
            now = command.now,
        )
        if (!completed) error("response run was not completable after consumer precondition passed")
        markResponseRunResultConsumed(result.runId, result.attempt, command.now)
        touchConversation(run.conversationId, command.now)
        val detail = loadConversationDetail(conversation.owner, run.conversationId)
            ?: error("conversation detail missing after result consumption")
        return ConsumeResponseRunResult.Consumed(detail)
    }

    private fun Connection.queuePlanningStageFromResult(
        command: QueuePlanningStageFromResultCommand,
    ): ConsumeResponseRunResult {
        val result = lockResponseRunResult(command.result.runId, command.result.attempt)
            ?: return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.ResultMissing)
        if (result.consumedAt != null) return ConsumeResponseRunResult.AlreadyConsumed()
        if (result.resultType != ResponseRunResultType.PlanningUnderstanding) {
            return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.ResultTypeMismatch)
        }
        val run = lockResponseRun(command.result.runId)
            ?: return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.RunMissing)
        if (run.attempt != result.attempt) {
            return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.AttemptMismatch)
        }
        if (!run.status.isConsumable()) {
            return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.RunNotConsumable)
        }
        val userMessage = findMessage(run.conversationId, run.userMessageId)
            ?: return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.UserMessageMissing)
        val aiRequestId = userMessage.aiRequestId
            ?: return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.AiRequestMismatch)
        val queued = preparePlanningStageFromResult(
            runId = run.id,
            attempt = run.attempt,
            expectedTaskId = command.expectedTaskId,
            expectedTaskRevision = command.expectedTaskRevision,
            now = command.now,
        )
        if (!queued) return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.PlanningPreconditionMismatch)
        markMessageUnderstood(run.userMessageId, aiRequestId, command.now)
        markResponseRunResultConsumed(result.runId, result.attempt, command.now)
        val conversation = findConversationById(run.conversationId)
            ?: error("conversation missing after planning result completion")
        val detail = loadConversationDetail(conversation.owner, run.conversationId)
            ?: error("conversation detail missing after planning stage queue")
        return ConsumeResponseRunResult.Consumed(detail)
    }

    private fun Connection.completePlanningResult(
        command: CompletePlanningResultCommand,
    ): ConsumeResponseRunResult {
        val result = lockResponseRunResult(command.result.runId, command.result.attempt)
            ?: return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.ResultMissing)
        if (result.consumedAt != null) return ConsumeResponseRunResult.AlreadyConsumed()
        if (result.resultType != ResponseRunResultType.PlanningResult) {
            return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.ResultTypeMismatch)
        }
        val run = lockResponseRun(command.result.runId)
            ?: return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.RunMissing)
        if (run.attempt != result.attempt) {
            return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.AttemptMismatch)
        }
        if (!run.status.isConsumable()) {
            return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.RunNotConsumable)
        }
        val completed = completePlanningRunFromResult(
            runId = run.id,
            attempt = run.attempt,
            now = command.now,
            failureCategory = command.failureCategory,
        )
        if (!completed) return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.PlanningPreconditionMismatch)
        markResponseRunResultConsumed(result.runId, result.attempt, command.now)
        val conversation = findConversationById(run.conversationId)
            ?: return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.ConversationMissing)
        val detail = loadConversationDetail(conversation.owner, run.conversationId)
            ?: error("conversation detail missing after planning result completion")
        return ConsumeResponseRunResult.Consumed(detail)
    }

    private fun Connection.findAssistantMessage(
        conversationId: ConversationId,
        aiRequestId: String,
    ): ConversationMessage? =
        prepareStatement(
            """
            SELECT id, conversation_id, role, content, client_message_id, ai_request_id, turn_index, understood_at, created_at
            FROM conversation_messages
            WHERE conversation_id = ? AND role = ? AND ai_request_id = ?
            LIMIT 1
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, conversationId.value)
            statement.setString(2, MessageRole.Assistant.name)
            statement.setString(3, aiRequestId)
            statement.executeQuery().use { result -> if (result.next()) result.message() else null }
        }

    private fun Connection.loadConversationDetail(
        owner: TaskOwner,
        conversationId: ConversationId,
    ): ConversationDetail? {
        val conversation = findConversation(owner, conversationId) ?: return null
        return ConversationDetail(
            conversation = conversation,
            messages = loadMessages(conversationId),
            responseRuns = loadResponseRuns(conversationId),
        )
    }

    private fun Connection.lockConversation(
        owner: TaskOwner,
        conversationId: ConversationId,
    ): Conversation? =
        prepareStatement(
            """
            SELECT id, tenant_id, owner_user_id, creation_request_id, next_turn_index, created_at, updated_at, archived_at
            FROM conversations
            WHERE tenant_id = ? AND owner_user_id = ? AND id = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setOwner(owner)
            statement.setObject(3, conversationId.value)
            statement.executeQuery().use { result -> if (result.next()) result.conversation() else null }
        }

    private fun Connection.findConversation(
        owner: TaskOwner,
        conversationId: ConversationId,
    ): Conversation? =
        prepareStatement(
            """
            SELECT id, tenant_id, owner_user_id, creation_request_id, next_turn_index, created_at, updated_at, archived_at
            FROM conversations
            WHERE tenant_id = ? AND owner_user_id = ? AND id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setOwner(owner)
            statement.setObject(3, conversationId.value)
            statement.executeQuery().use { result -> if (result.next()) result.conversation() else null }
        }

    private fun Connection.findConversationById(conversationId: ConversationId): Conversation? =
        prepareStatement(
            """
            SELECT id, tenant_id, owner_user_id, creation_request_id, next_turn_index, created_at, updated_at, archived_at
            FROM conversations
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, conversationId.value)
            statement.executeQuery().use { result -> if (result.next()) result.conversation() else null }
        }

    private fun Connection.findConversationByCreationRequest(
        owner: TaskOwner,
        creationRequestId: String,
    ): Conversation? =
        prepareStatement(
            """
            SELECT id, tenant_id, owner_user_id, creation_request_id, next_turn_index, created_at, updated_at, archived_at
            FROM conversations
            WHERE tenant_id = ? AND owner_user_id = ? AND creation_request_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setOwner(owner)
            statement.setString(3, creationRequestId)
            statement.executeQuery().use { result -> if (result.next()) result.conversation() else null }
        }

    private fun Connection.findMessageByClientId(
        conversationId: ConversationId,
        clientMessageId: String,
    ): ConversationMessage? =
        prepareStatement(
            """
            SELECT id, conversation_id, role, content, client_message_id, ai_request_id, turn_index, understood_at, created_at
            FROM conversation_messages
            WHERE conversation_id = ? AND client_message_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, conversationId.value)
            statement.setString(2, clientMessageId)
            statement.executeQuery().use { result -> if (result.next()) result.message() else null }
        }

    private fun Connection.findMessage(
        conversationId: ConversationId,
        messageId: MessageId,
    ): ConversationMessage? =
        prepareStatement(
            """
            SELECT id, conversation_id, role, content, client_message_id, ai_request_id, turn_index, understood_at, created_at
            FROM conversation_messages
            WHERE conversation_id = ? AND id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, conversationId.value)
            statement.setObject(2, messageId.value)
            statement.executeQuery().use { result -> if (result.next()) result.message() else null }
        }

    private fun Connection.loadMessages(conversationId: ConversationId): List<ConversationMessage> =
        prepareStatement(
            """
            SELECT id, conversation_id, role, content, client_message_id, ai_request_id, turn_index, understood_at, created_at
            FROM conversation_messages
            WHERE conversation_id = ?
            ORDER BY turn_index ASC, CASE role WHEN 'User' THEN 0 ELSE 1 END ASC, created_at ASC, id ASC
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, conversationId.value)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(result.message())
                }
            }
        }

    private fun Connection.findResponseRun(responseRunId: ResponseRunId): ResponseRun? =
        prepareStatement(
            """
            SELECT
                id,
                conversation_id,
                user_message_id,
                turn_index,
                status,
                stage,
                attempt,
                available_at,
                lease_owner,
                lease_expires_at,
                deadline_at,
                expected_task_id,
                expected_task_revision,
                assistant_message_id,
                failure_category,
                origin_trace_id,
                created_at,
                started_at,
                updated_at,
                completed_at
            FROM response_runs
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, responseRunId.value)
            statement.executeQuery().use { result ->
                if (result.next()) result.responseRun() else null
            }
        }

    private fun Connection.lockResponseRun(responseRunId: ResponseRunId): ResponseRun? =
        prepareStatement(
            """
            SELECT
                id,
                conversation_id,
                user_message_id,
                turn_index,
                status,
                stage,
                attempt,
                available_at,
                lease_owner,
                lease_expires_at,
                deadline_at,
                expected_task_id,
                expected_task_revision,
                assistant_message_id,
                failure_category,
                origin_trace_id,
                created_at,
                started_at,
                updated_at,
                completed_at
            FROM response_runs
            WHERE id = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, responseRunId.value)
            statement.executeQuery().use { result ->
                if (result.next()) result.responseRun() else null
            }
        }

    private fun Connection.findResponseRunResult(
        responseRunId: ResponseRunId,
        attempt: Int,
    ): ResponseRunResult? =
        prepareStatement(
            """
            SELECT run_id, attempt, result_type, payload::text AS payload, created_at, consumed_at
            FROM response_run_results
            WHERE run_id = ? AND attempt = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, responseRunId.value)
            statement.setInt(2, attempt)
            statement.executeQuery().use { result ->
                if (result.next()) result.responseRunResult() else null
            }
        }

    private fun Connection.lockResponseRunResult(
        responseRunId: ResponseRunId,
        attempt: Int,
    ): ResponseRunResult? =
        prepareStatement(
            """
            SELECT run_id, attempt, result_type, payload::text AS payload, created_at, consumed_at
            FROM response_run_results
            WHERE run_id = ? AND attempt = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, responseRunId.value)
            statement.setInt(2, attempt)
            statement.executeQuery().use { result ->
                if (result.next()) result.responseRunResult() else null
            }
        }

    private fun Connection.completeResponseRunFromResult(
        runId: ResponseRunId,
        attempt: Int,
        assistantMessageId: MessageId,
        now: Instant,
    ): Boolean =
        prepareStatement(
            """
            UPDATE response_runs
            SET status = ?,
                assistant_message_id = ?,
                lease_owner = NULL,
                lease_expires_at = NULL,
                updated_at = ?,
                completed_at = ?
            WHERE id = ?
              AND attempt = ?
              AND status IN (?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, ResponseRunStatus.Completed.toDatabaseValue())
            statement.setObject(2, assistantMessageId.value)
            statement.setInstant(3, now)
            statement.setInstant(4, now)
            statement.setObject(5, runId.value)
            statement.setInt(6, attempt)
            statement.setString(7, ResponseRunStatus.Processing.toDatabaseValue())
            statement.setString(8, ResponseRunStatus.Streaming.toDatabaseValue())
            statement.executeUpdate() == 1
        }

    private fun Connection.preparePlanningStageFromResult(
        runId: ResponseRunId,
        attempt: Int,
        expectedTaskId: TaskId,
        expectedTaskRevision: Long,
        now: Instant,
    ): Boolean =
        prepareStatement(
            """
            UPDATE response_runs
            SET status = ?,
                stage = ?,
                available_at = ?,
                lease_owner = NULL,
                lease_expires_at = NULL,
                expected_task_id = ?,
                expected_task_revision = ?,
                failure_category = NULL,
                updated_at = ?
            WHERE id = ?
              AND attempt = ?
              AND status IN (?, ?)
              AND stage = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, ResponseRunStatus.Queued.toDatabaseValue())
            statement.setString(2, ResponseRunStage.Planning.toDatabaseValue())
            statement.setInstant(3, now)
            statement.setObject(4, expectedTaskId.value)
            statement.setLong(5, expectedTaskRevision)
            statement.setInstant(6, now)
            statement.setObject(7, runId.value)
            statement.setInt(8, attempt)
            statement.setString(9, ResponseRunStatus.Processing.toDatabaseValue())
            statement.setString(10, ResponseRunStatus.Streaming.toDatabaseValue())
            statement.setString(11, ResponseRunStage.Turn.toDatabaseValue())
            statement.executeUpdate() == 1
        }

    private fun Connection.completePlanningRunFromResult(
        runId: ResponseRunId,
        attempt: Int,
        now: Instant,
        failureCategory: ResponseRunFailureCategory?,
    ): Boolean =
        prepareStatement(
            """
            UPDATE response_runs
            SET status = ?,
                lease_owner = NULL,
                lease_expires_at = NULL,
                failure_category = ?,
                updated_at = ?,
                completed_at = ?
            WHERE id = ?
              AND attempt = ?
              AND status IN (?, ?)
              AND stage = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, if (failureCategory == null) ResponseRunStatus.Completed.toDatabaseValue() else ResponseRunStatus.Failed.toDatabaseValue())
            statement.setString(2, failureCategory?.toDatabaseValue())
            statement.setInstant(3, now)
            statement.setInstant(4, now)
            statement.setObject(5, runId.value)
            statement.setInt(6, attempt)
            statement.setString(7, ResponseRunStatus.Processing.toDatabaseValue())
            statement.setString(8, ResponseRunStatus.Streaming.toDatabaseValue())
            statement.setString(9, ResponseRunStage.Planning.toDatabaseValue())
            statement.executeUpdate() == 1
        }

    private fun Connection.markResponseRunResultConsumed(
        runId: ResponseRunId,
        attempt: Int,
        now: Instant,
    ) {
        prepareStatement(
            """
            UPDATE response_run_results
            SET consumed_at = ?
            WHERE run_id = ? AND attempt = ? AND consumed_at IS NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setInstant(1, now)
            statement.setObject(2, runId.value)
            statement.setInt(3, attempt)
            statement.executeUpdate()
        }
    }

    private fun Connection.loadResponseRuns(conversationId: ConversationId): List<ResponseRun> =
        prepareStatement(
            """
            SELECT
                id,
                conversation_id,
                user_message_id,
                turn_index,
                status,
                stage,
                attempt,
                available_at,
                lease_owner,
                lease_expires_at,
                deadline_at,
                expected_task_id,
                expected_task_revision,
                assistant_message_id,
                failure_category,
                origin_trace_id,
                created_at,
                started_at,
                updated_at,
                completed_at
            FROM response_runs
            WHERE conversation_id = ?
            ORDER BY turn_index ASC, created_at ASC, id ASC
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, conversationId.value)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(result.responseRun())
                }
            }
        }

    private fun ResultSet.conversation(): Conversation =
        Conversation(
            id = ConversationId(getObject("id", UUID::class.java)),
            owner = TaskOwner(TenantId(getObject("tenant_id", UUID::class.java)), UserId(getObject("owner_user_id", UUID::class.java))),
            creationRequestId = getString("creation_request_id"),
            nextTurnIndex = getLong("next_turn_index"),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
            archivedAt = getTimestamp("archived_at")?.toInstant(),
        )

    private fun ResultSet.message(): ConversationMessage =
        ConversationMessage(
            id = MessageId(getObject("id", UUID::class.java)),
            conversationId = ConversationId(getObject("conversation_id", UUID::class.java)),
            role = MessageRole.valueOf(getString("role")),
            content = getString("content"),
            clientMessageId = getString("client_message_id"),
            aiRequestId = getString("ai_request_id"),
            turnIndex = getLong("turn_index"),
            understoodAt = getTimestamp("understood_at")?.toInstant(),
            createdAt = getTimestamp("created_at").toInstant(),
        )

    private fun ResultSet.responseRun(): ResponseRun =
        ResponseRun(
            id = ResponseRunId(getObject("id", UUID::class.java)),
            conversationId = ConversationId(getObject("conversation_id", UUID::class.java)),
            userMessageId = MessageId(getObject("user_message_id", UUID::class.java)),
            turnIndex = getLong("turn_index"),
            status = getString("status").toResponseRunStatus(),
            stage = getString("stage").toResponseRunStage(),
            attempt = getInt("attempt"),
            availableAt = getTimestamp("available_at").toInstant(),
            leaseOwner = getString("lease_owner"),
            leaseExpiresAt = getTimestamp("lease_expires_at")?.toInstant(),
            deadlineAt = getTimestamp("deadline_at").toInstant(),
            expectedTaskId = getObject("expected_task_id", UUID::class.java)?.let(::TaskId),
            expectedTaskRevision = getNullableLong("expected_task_revision"),
            assistantMessageId = getObject("assistant_message_id", UUID::class.java)?.let(::MessageId),
            failureCategory = getString("failure_category")?.toResponseRunFailureCategory(),
            originTraceId = getString("origin_trace_id"),
            createdAt = getTimestamp("created_at").toInstant(),
            startedAt = getTimestamp("started_at")?.toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
            completedAt = getTimestamp("completed_at")?.toInstant(),
        )

    private fun ResultSet.responseRunResult(): ResponseRunResult {
        val resultType = getString("result_type").toResponseRunResultType()
        return ResponseRunResult(
            runId = ResponseRunId(getObject("run_id", UUID::class.java)),
            attempt = getInt("attempt"),
            resultType = resultType,
            payload = JsonFormat.decodeFromString(ResponseRunResultPayload.serializer(), getString("payload")),
            createdAt = getTimestamp("created_at").toInstant(),
            consumedAt = getTimestamp("consumed_at")?.toInstant(),
        )
    }

    private fun ResultSet.getNullableLong(columnLabel: String): Long? {
        val value = getLong(columnLabel)
        return if (wasNull()) null else value
    }

    private fun ResponseRunStatus.toDatabaseValue(): String =
        when (this) {
            ResponseRunStatus.Queued -> "QUEUED"
            ResponseRunStatus.Processing -> "PROCESSING"
            ResponseRunStatus.Streaming -> "STREAMING"
            ResponseRunStatus.Completed -> "COMPLETED"
            ResponseRunStatus.FailedRetryable -> "FAILED_RETRYABLE"
            ResponseRunStatus.Failed -> "FAILED"
            ResponseRunStatus.TimedOut -> "TIMED_OUT"
            ResponseRunStatus.Cancelled -> "CANCELLED"
        }

    private fun String.toResponseRunStatus(): ResponseRunStatus =
        when (this) {
            "QUEUED" -> ResponseRunStatus.Queued
            "PROCESSING" -> ResponseRunStatus.Processing
            "STREAMING" -> ResponseRunStatus.Streaming
            "COMPLETED" -> ResponseRunStatus.Completed
            "FAILED_RETRYABLE" -> ResponseRunStatus.FailedRetryable
            "FAILED" -> ResponseRunStatus.Failed
            "TIMED_OUT" -> ResponseRunStatus.TimedOut
            "CANCELLED" -> ResponseRunStatus.Cancelled
            else -> error("Unknown response run status: $this")
        }

    private fun ResponseRunStatus.isCancellable(): Boolean =
        when (this) {
            ResponseRunStatus.Queued,
            ResponseRunStatus.Processing,
            ResponseRunStatus.Streaming,
            ResponseRunStatus.FailedRetryable,
            -> true
            ResponseRunStatus.Completed,
            ResponseRunStatus.Failed,
            ResponseRunStatus.TimedOut,
            ResponseRunStatus.Cancelled,
            -> false
        }

    private fun ResponseRunStatus.isManuallyRetryable(): Boolean =
        when (this) {
            ResponseRunStatus.FailedRetryable,
            ResponseRunStatus.Failed,
            ResponseRunStatus.TimedOut,
            -> true
            ResponseRunStatus.Queued,
            ResponseRunStatus.Processing,
            ResponseRunStatus.Streaming,
            ResponseRunStatus.Completed,
            ResponseRunStatus.Cancelled,
            -> false
        }

    private fun ResponseRunStage.toDatabaseValue(): String =
        when (this) {
            ResponseRunStage.Turn -> "TURN"
            ResponseRunStage.Planning -> "PLANNING"
        }

    private fun String.toResponseRunStage(): ResponseRunStage =
        when (this) {
            "TURN" -> ResponseRunStage.Turn
            "PLANNING" -> ResponseRunStage.Planning
            else -> error("Unknown response run stage: $this")
        }

    private fun String.toResponseRunFailureCategory(): ResponseRunFailureCategory =
        when (this) {
            "PROVIDER_TEMPORARY" -> ResponseRunFailureCategory.ProviderTemporary
            "AI_INVALID_RESULT" -> ResponseRunFailureCategory.AiInvalidResult
            "WORKER_LOST" -> ResponseRunFailureCategory.WorkerLost
            "RUN_TIMEOUT" -> ResponseRunFailureCategory.RunTimeout
            "INTERNAL_INVARIANT" -> ResponseRunFailureCategory.InternalInvariant
            else -> error("Unknown response run failure category: $this")
        }

    private fun ResponseRunFailureCategory.toDatabaseValue(): String =
        when (this) {
            ResponseRunFailureCategory.ProviderTemporary -> "PROVIDER_TEMPORARY"
            ResponseRunFailureCategory.AiInvalidResult -> "AI_INVALID_RESULT"
            ResponseRunFailureCategory.WorkerLost -> "WORKER_LOST"
            ResponseRunFailureCategory.RunTimeout -> "RUN_TIMEOUT"
            ResponseRunFailureCategory.InternalInvariant -> "INTERNAL_INVARIANT"
        }

    private fun ResponseRunResultPayload.resultType(): ResponseRunResultType =
        when (this) {
            is ResponseRunResultPayload.ConversationAnswer -> ResponseRunResultType.ConversationAnswer
            is ResponseRunResultPayload.PlanningUnderstanding -> ResponseRunResultType.PlanningUnderstanding
            is ResponseRunResultPayload.PlanningResult -> ResponseRunResultType.PlanningResult
        }

    private fun ResponseRunResultType.toDatabaseValue(): String =
        when (this) {
            ResponseRunResultType.ConversationAnswer -> "CONVERSATION_ANSWER"
            ResponseRunResultType.PlanningUnderstanding -> "PLANNING_UNDERSTANDING"
            ResponseRunResultType.PlanningResult -> "PLANNING_RESULT"
        }

    private fun String.toResponseRunResultType(): ResponseRunResultType =
        when (this) {
            "CONVERSATION_ANSWER" -> ResponseRunResultType.ConversationAnswer
            "PLANNING_UNDERSTANDING" -> ResponseRunResultType.PlanningUnderstanding
            "PLANNING_RESULT" -> ResponseRunResultType.PlanningResult
            else -> error("Unknown response run result type: $this")
        }

    private fun ResponseRunStatus.isTerminal(): Boolean =
        when (this) {
            ResponseRunStatus.Completed,
            ResponseRunStatus.Failed,
            ResponseRunStatus.TimedOut,
            ResponseRunStatus.Cancelled,
            -> true
            ResponseRunStatus.Queued,
            ResponseRunStatus.Processing,
            ResponseRunStatus.Streaming,
            ResponseRunStatus.FailedRetryable,
            -> false
        }

    private fun ResponseRunStatus.isConsumable(): Boolean =
        when (this) {
            ResponseRunStatus.Processing,
            ResponseRunStatus.Streaming,
            -> true
            ResponseRunStatus.Queued,
            ResponseRunStatus.Completed,
            ResponseRunStatus.FailedRetryable,
            ResponseRunStatus.Failed,
            ResponseRunStatus.TimedOut,
            ResponseRunStatus.Cancelled,
            -> false
        }

    private fun java.sql.PreparedStatement.setOwner(owner: TaskOwner) {
        setObject(1, owner.tenantId.value)
        setObject(2, owner.userId.value)
    }

    private fun java.sql.PreparedStatement.setInstant(
        index: Int,
        value: Instant?,
    ) {
        setTimestamp(index, value?.let(Timestamp::from))
    }

    private companion object {
        const val FIRST_TURN_INDEX = 1L
        const val INITIAL_RESPONSE_RUN_ATTEMPT = 0
        val JsonFormat: Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
