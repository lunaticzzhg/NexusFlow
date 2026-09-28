package com.nexusflow.backend.feature.conversation.infrastructure

import com.nexusflow.backend.feature.conversation.domain.Conversation
import com.nexusflow.backend.feature.conversation.domain.ConversationAnswerResultCommitter
import com.nexusflow.backend.feature.conversation.domain.ConversationDetail
import com.nexusflow.backend.feature.conversation.domain.ConversationId
import com.nexusflow.backend.feature.conversation.domain.ConversationMessage
import com.nexusflow.backend.feature.conversation.domain.ConsumeConversationAnswerResultCommand
import com.nexusflow.backend.feature.responserun.domain.ConsumeResponseRunIgnoreReason
import com.nexusflow.backend.feature.responserun.domain.ConsumeResponseRunResult
import com.nexusflow.backend.feature.responserun.domain.ResponseRun
import com.nexusflow.backend.feature.responserun.domain.ResponseRunFailureCategory
import com.nexusflow.backend.feature.responserun.domain.ResponseRunId
import com.nexusflow.backend.feature.responserun.domain.ResponseRunResult
import com.nexusflow.backend.feature.responserun.domain.ResponseRunResultPayload
import com.nexusflow.backend.feature.responserun.domain.ResponseRunResultType
import com.nexusflow.backend.feature.responserun.domain.ResponseRunStage
import com.nexusflow.backend.feature.responserun.domain.ResponseRunStatus
import com.nexusflow.backend.feature.conversation.domain.MessageId
import com.nexusflow.backend.feature.conversation.domain.MessageRole
import com.nexusflow.backend.feature.task.domain.TaskId
import com.nexusflow.backend.feature.task.domain.TaskOwner
import com.nexusflow.backend.feature.task.domain.TenantId
import com.nexusflow.backend.feature.task.domain.UserId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcConversationAnswerCommitter(
    private val dataSource: DataSource,
) : ConversationAnswerResultCommitter {
    override suspend fun consumeConversationAnswerResult(
        command: ConsumeConversationAnswerResultCommand,
    ): ConsumeResponseRunResult =
        blocking {
            inTransaction { connection ->
                connection.consumeConversationAnswerResult(command)
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
            ?: return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.ConversationMissing)
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
            return reconcileExistingAssistant(command, result, run, existingAssistant, conversation)
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

    private fun Connection.reconcileExistingAssistant(
        command: ConsumeConversationAnswerResultCommand,
        result: ResponseRunResult,
        run: ResponseRun,
        existingAssistant: ConversationMessage,
        conversation: Conversation,
    ): ConsumeResponseRunResult {
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

    private fun Connection.markMessageUnderstood(
        messageId: MessageId,
        aiRequestId: String,
        now: Instant,
    ) {
        prepareStatement(
            """
            UPDATE conversation_messages
            SET understood_at = ?
            WHERE id = ? AND ai_request_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setInstant(1, now)
            statement.setObject(2, messageId.value)
            statement.setString(3, aiRequestId)
            statement.executeUpdate()
        }
    }

    private fun Connection.touchConversation(
        conversationId: ConversationId,
        now: Instant,
    ) {
        prepareStatement(
            """
            UPDATE conversations
            SET updated_at = ?
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setInstant(1, now)
            statement.setObject(2, conversationId.value)
            statement.executeUpdate()
        }
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
                id, conversation_id, user_message_id, turn_index, status, stage, attempt,
                available_at, lease_owner, lease_expires_at, deadline_at, expected_task_id,
                expected_task_revision, assistant_message_id, failure_category, origin_trace_id, time_zone_id, created_at,
                started_at, updated_at, completed_at
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

    private fun String.toResponseRunResultType(): ResponseRunResultType =
        when (this) {
            "CONVERSATION_ANSWER" -> ResponseRunResultType.ConversationAnswer
            "PLANNING_UNDERSTANDING" -> ResponseRunResultType.PlanningUnderstanding
            "PLANNING_RESULT" -> ResponseRunResultType.PlanningResult
            else -> error("Unknown response run result type: $this")
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
        val JsonFormat: Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
