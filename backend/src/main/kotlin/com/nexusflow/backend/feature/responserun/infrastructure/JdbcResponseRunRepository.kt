package com.nexusflow.backend.feature.responserun.infrastructure

import com.nexusflow.backend.feature.conversation.domain.ConversationId
import com.nexusflow.backend.feature.responserun.domain.CancelResponseRunCommand
import com.nexusflow.backend.feature.responserun.domain.CancelResponseRunResult
import com.nexusflow.backend.feature.responserun.domain.ClaimNextResponseRunCommand
import com.nexusflow.backend.feature.responserun.domain.ClaimedResponseRun
import com.nexusflow.backend.feature.responserun.domain.CompleteResponseRunAttemptCommand
import com.nexusflow.backend.feature.responserun.domain.FailResponseRunAttemptCommand
import com.nexusflow.backend.feature.responserun.domain.HeartbeatResponseRunLeaseCommand
import com.nexusflow.backend.feature.responserun.domain.MarkResponseRunRetryableCommand
import com.nexusflow.backend.feature.responserun.domain.ResponseRun
import com.nexusflow.backend.feature.responserun.domain.ResponseRunFailureCategory
import com.nexusflow.backend.feature.responserun.domain.ResponseRunId
import com.nexusflow.backend.feature.responserun.domain.ResponseRunRepository
import com.nexusflow.backend.feature.responserun.domain.ResponseRunResult
import com.nexusflow.backend.feature.responserun.domain.ResponseRunResultPayload
import com.nexusflow.backend.feature.responserun.domain.ResponseRunResultType
import com.nexusflow.backend.feature.responserun.domain.ResponseRunStage
import com.nexusflow.backend.feature.responserun.domain.ResponseRunStatus
import com.nexusflow.backend.feature.responserun.domain.RetryResponseRunCommand
import com.nexusflow.backend.feature.responserun.domain.RetryResponseRunResult
import com.nexusflow.backend.feature.responserun.domain.StoreResponseRunResult
import com.nexusflow.backend.feature.responserun.domain.StoreResponseRunResultCommand
import com.nexusflow.backend.feature.conversation.domain.MessageId
import com.nexusflow.backend.feature.task.domain.TaskId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcResponseRunRepository(
    private val dataSource: DataSource,
) : ResponseRunRepository {
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
                run.time_zone_id,
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
                expected_task_revision, assistant_message_id, failure_category, origin_trace_id, time_zone_id, created_at,
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
                expected_task_revision, assistant_message_id, failure_category, origin_trace_id, time_zone_id, created_at,
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

    private fun Connection.findResponseRun(responseRunId: ResponseRunId): ResponseRun? =
        prepareStatement(
            """
            SELECT
                id, conversation_id, user_message_id, turn_index, status, stage, attempt,
                available_at, lease_owner, lease_expires_at, deadline_at, expected_task_id,
                expected_task_revision, assistant_message_id, failure_category, origin_trace_id, time_zone_id, created_at,
                started_at, updated_at, completed_at
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
                id, conversation_id, user_message_id, turn_index, status, stage, attempt,
                available_at, lease_owner, lease_expires_at, deadline_at, expected_task_id,
                expected_task_revision, assistant_message_id, failure_category, origin_trace_id, time_zone_id, created_at,
                started_at, updated_at, completed_at
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
            timeZoneId = getString("time_zone_id"),
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

    private fun java.sql.PreparedStatement.setInstant(
        index: Int,
        value: Instant?,
    ) {
        setTimestamp(index, value?.let(Timestamp::from))
    }

    private companion object {
        val JsonFormat: Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
