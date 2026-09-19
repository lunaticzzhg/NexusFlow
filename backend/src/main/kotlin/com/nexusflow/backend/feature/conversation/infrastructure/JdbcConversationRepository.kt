package com.nexusflow.backend.feature.conversation.infrastructure

import com.nexusflow.backend.feature.conversation.domain.Conversation
import com.nexusflow.backend.feature.conversation.domain.ConversationDetail
import com.nexusflow.backend.feature.conversation.domain.ConversationId
import com.nexusflow.backend.feature.conversation.domain.ConversationMessage
import com.nexusflow.backend.feature.conversation.domain.ConversationRepository
import com.nexusflow.backend.feature.responserun.domain.ResponseRun
import com.nexusflow.backend.feature.responserun.domain.ResponseRunFailureCategory
import com.nexusflow.backend.feature.responserun.domain.ResponseRunId
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
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID
import javax.sql.DataSource

class JdbcConversationRepository(
    private val dataSource: DataSource,
) : ConversationRepository {
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

    private suspend fun <T> blocking(block: () -> T): T = withContext(Dispatchers.IO) { block() }

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

    private fun ResultSet.getNullableLong(columnLabel: String): Long? {
        val value = getLong(columnLabel)
        return if (wasNull()) null else value
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

    private fun java.sql.PreparedStatement.setOwner(owner: TaskOwner) {
        setObject(1, owner.tenantId.value)
        setObject(2, owner.userId.value)
    }

}
