package com.nexusflow.backend.feature.conversation.infrastructure

import com.nexusflow.backend.feature.conversation.domain.AppendConversationUserMessageCommand
import com.nexusflow.backend.feature.conversation.domain.AppendConversationUserMessageResult
import com.nexusflow.backend.feature.conversation.domain.Conversation
import com.nexusflow.backend.feature.conversation.domain.ConversationDetail
import com.nexusflow.backend.feature.conversation.domain.ConversationId
import com.nexusflow.backend.feature.conversation.domain.ConversationMessage
import com.nexusflow.backend.feature.conversation.domain.ConversationTurnStartCommitter
import com.nexusflow.backend.feature.conversation.domain.CreateConversationCommand
import com.nexusflow.backend.feature.conversation.domain.CreateConversationResult
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
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcConversationTurnStartCommitter(
    private val dataSource: DataSource,
) : ConversationTurnStartCommitter {
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
    }
}
