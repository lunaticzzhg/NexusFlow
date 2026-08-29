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
import com.nexusflow.backend.feature.task.domain.Conversation
import com.nexusflow.backend.feature.task.domain.ConversationId
import com.nexusflow.backend.feature.task.domain.ConversationMessage
import com.nexusflow.backend.feature.task.domain.CreateFixturePlanningRunCommand
import com.nexusflow.backend.feature.task.domain.CreateFixturePlanningRunResult
import com.nexusflow.backend.feature.task.domain.CreateTaskPersistenceCommand
import com.nexusflow.backend.feature.task.domain.CreateTaskPersistenceResult
import com.nexusflow.backend.feature.task.domain.MessageId
import com.nexusflow.backend.feature.task.domain.MessageRole
import com.nexusflow.backend.feature.task.domain.Plan
import com.nexusflow.backend.feature.task.domain.PlanEstimatedCost
import com.nexusflow.backend.feature.task.domain.PlanId
import com.nexusflow.backend.feature.task.domain.PlanSourceRef
import com.nexusflow.backend.feature.task.domain.PlanTimelineItem
import com.nexusflow.backend.feature.task.domain.PlanningRun
import com.nexusflow.backend.feature.task.domain.PlanningRunId
import com.nexusflow.backend.feature.task.domain.RecordAiUnderstandingAuditCommand
import com.nexusflow.backend.feature.task.domain.RecordAiUnderstandingAuditResult
import com.nexusflow.backend.feature.task.domain.SelectPlanCommand
import com.nexusflow.backend.feature.task.domain.SelectPlanResult
import com.nexusflow.backend.feature.task.domain.Task
import com.nexusflow.backend.feature.task.domain.TaskConstraint
import com.nexusflow.backend.feature.task.domain.TaskDetail
import com.nexusflow.backend.feature.task.domain.TaskId
import com.nexusflow.backend.feature.task.domain.TaskOwner
import com.nexusflow.backend.feature.task.domain.TaskRepository
import com.nexusflow.backend.feature.task.domain.TaskState
import com.nexusflow.backend.feature.task.domain.TenantId
import com.nexusflow.backend.feature.task.domain.UserId
import com.nexusflow.backend.feature.task.domain.createTaskTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.postgresql.util.PGobject
import java.sql.Connection
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

class JdbcTaskRepository(
    private val dataSource: DataSource,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) : TaskRepository {
    override suspend fun createTaskWithConversation(command: CreateTaskPersistenceCommand): CreateTaskPersistenceResult =
        blocking {
            inTransaction { connection ->
                connection.findTaskByCreationRequest(command.owner, command.creationRequestId)?.let { existing ->
                    return@inTransaction if (existing.initialGoal == command.goal.trim()) {
                        CreateTaskPersistenceResult.Existing(connection.loadTaskDetail(command.owner, existing.id)!!)
                    } else {
                        CreateTaskPersistenceResult.ConflictingRequest
                    }
                }

                val goal = command.goal.trim()
                connection.insertTask(
                    Task(
                        id = command.taskId,
                        owner = command.owner,
                        creationRequestId = command.creationRequestId,
                        initialGoal = goal,
                        currentGoal = goal,
                        title = createTaskTitle(goal),
                        state = TaskState.Draft,
                        version = INITIAL_TASK_VERSION,
                        selectedPlanId = null,
                        createdAt = command.now,
                        updatedAt = command.now,
                    ),
                )
                connection.insertConversation(Conversation(command.conversationId, command.taskId, command.now))
                connection.insertAuditEvent(
                    taskId = command.taskId,
                    eventType = "TaskCreated",
                    requestId = command.creationRequestId,
                    aiRequestId = null,
                    metadataJson = "{}",
                    occurredAt = command.now,
                )
                CreateTaskPersistenceResult.Created(connection.loadTaskDetail(command.owner, command.taskId)!!)
            }
        }

    override suspend fun listTasks(owner: TaskOwner): List<Task> =
        blocking {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT id, tenant_id, owner_user_id, creation_request_id, initial_goal, current_goal, title,
                           state, version, selected_plan_id, created_at, updated_at
                    FROM tasks
                    WHERE tenant_id = ? AND owner_user_id = ?
                    ORDER BY updated_at DESC
                    """.trimIndent(),
                ).use { statement ->
                    statement.setOwner(owner)
                    statement.executeQuery().use { result ->
                        buildList {
                            while (result.next()) add(result.task())
                        }
                    }
                }
            }
        }

    override suspend fun findTaskDetail(
        owner: TaskOwner,
        taskId: TaskId,
    ): TaskDetail? =
        blocking {
            dataSource.connection.use { connection ->
                connection.loadTaskDetail(owner, taskId)
            }
        }

    override suspend fun appendUserMessage(command: AppendUserMessageCommand): AppendUserMessageResult =
        blocking {
            inTransaction { connection ->
                val task = connection.lockTask(command.owner, command.taskId) ?: return@inTransaction AppendUserMessageResult.TaskNotFound
                val conversation = connection.loadConversation(command.taskId)
                connection.findMessageByClientId(conversation.id, command.clientMessageId)?.let { existing ->
                    return@inTransaction if (existing.content == command.text) {
                        AppendUserMessageResult.Existing(connection.loadTaskDetail(command.owner, command.taskId)!!, existing, task.version)
                    } else {
                        AppendUserMessageResult.ConflictingMessage
                    }
                }

                val message = ConversationMessage(
                    id = command.messageId,
                    conversationId = conversation.id,
                    role = MessageRole.User,
                    content = command.text,
                    clientMessageId = command.clientMessageId,
                    aiRequestId = command.aiRequestId,
                    understoodAt = null,
                    createdAt = command.now,
                )
                connection.insertMessage(message)
                connection.touchTask(command.taskId, command.now)
                connection.insertAuditEvent(
                    taskId = command.taskId,
                    eventType = "MessageSent",
                    requestId = command.clientMessageId,
                    aiRequestId = command.aiRequestId,
                    metadataJson = "{}",
                    occurredAt = command.now,
                )
                AppendUserMessageResult.Appended(connection.loadTaskDetail(command.owner, command.taskId)!!, message, task.version)
            }
        }

    override suspend fun recordAiUnderstandingAudit(command: RecordAiUnderstandingAuditCommand): RecordAiUnderstandingAuditResult =
        blocking {
            inTransaction { connection ->
                connection.findTask(command.owner, command.taskId)
                    ?: return@inTransaction RecordAiUnderstandingAuditResult.TaskNotFound
                connection.insertAuditEvent(
                    taskId = command.taskId,
                    eventType = command.eventType.auditEventName(),
                    requestId = null,
                    aiRequestId = command.aiRequestId,
                    metadataJson = json.encodeToString(AiUnderstandingAuditDocument.from(command)),
                    occurredAt = command.now,
                )
                RecordAiUnderstandingAuditResult.Recorded
            }
        }

    override suspend fun applyUnderstanding(command: ApplyUnderstandingCommand): ApplyUnderstandingResult =
        blocking {
            inTransaction { connection ->
                val task = connection.lockTask(command.owner, command.taskId) ?: return@inTransaction ApplyUnderstandingResult.TaskNotFound
                if (task.version != command.expectedTaskVersion) {
                    return@inTransaction ApplyUnderstandingResult.StaleTaskVersion
                }
                val conversation = connection.loadConversation(command.taskId)
                val message = connection.findMessage(conversation.id, command.messageId)
                    ?: return@inTransaction ApplyUnderstandingResult.MessageNotFound

                command.constraints.forEach { constraint ->
                    connection.upsertConstraint(
                        taskId = command.taskId,
                        messageId = message.id,
                        constraint = constraint,
                        now = command.now,
                    )
                    connection.insertAuditEvent(
                        taskId = command.taskId,
                        eventType = "ConstraintConfirmed",
                        requestId = message.clientMessageId,
                        aiRequestId = command.aiRequestId,
                        metadataJson = """{"kind":"${constraint.kind.name}"}""",
                        occurredAt = command.now,
                    )
                }
                command.assistantMessage?.let { assistant ->
                    connection.insertAssistantMessage(conversation.id, command.aiRequestId, assistant, command.now)
                }
                connection.markMessageUnderstood(message.id, command.aiRequestId, command.now)
                connection.updateTaskState(
                    taskId = command.taskId,
                    fromState = task.state,
                    toState = command.targetState,
                    currentVersion = task.version,
                    now = command.now,
                )
                ApplyUnderstandingResult.Applied(connection.loadTaskDetail(command.owner, command.taskId)!!)
            }
        }

    override suspend fun createFixturePlanningRun(command: CreateFixturePlanningRunCommand): CreateFixturePlanningRunResult =
        blocking {
            inTransaction { connection ->
                val task = connection.lockTask(command.owner, command.taskId) ?: return@inTransaction CreateFixturePlanningRunResult.TaskNotFound
                connection.findPlanningRun(command.taskId, command.clientRequestId)?.let { existing ->
                    return@inTransaction CreateFixturePlanningRunResult.Existing(connection.loadTaskDetail(command.owner, command.taskId)!!, existing)
                }
                if (task.state != TaskState.Planning) {
                    return@inTransaction CreateFixturePlanningRunResult.InvalidState
                }

                val planningRun = PlanningRun(
                    id = command.planningRunId,
                    taskId = command.taskId,
                    clientRequestId = command.clientRequestId,
                    taskVersion = task.version,
                    createdAt = command.now,
                )
                connection.insertPlanningRun(planningRun)
                command.plans.forEach { plan -> connection.insertPlan(plan) }
                connection.transitionTask(
                    taskId = command.taskId,
                    fromState = TaskState.Planning,
                    toState = TaskState.WaitingForApproval,
                    currentVersion = task.version,
                    now = command.now,
                    requestId = command.clientRequestId,
                    aiRequestId = null,
                )
                connection.insertAuditEvent(command.taskId, "PlanningRequested", command.clientRequestId, null, "{}", command.now)
                connection.insertAuditEvent(command.taskId, "PlanGenerated", command.clientRequestId, null, "{}", command.now)
                CreateFixturePlanningRunResult.Created(connection.loadTaskDetail(command.owner, command.taskId)!!, planningRun)
            }
        }

    override suspend fun selectPlan(command: SelectPlanCommand): SelectPlanResult =
        blocking {
            inTransaction { connection ->
                val task = connection.lockTask(command.owner, command.taskId) ?: return@inTransaction SelectPlanResult.TaskNotFound
                if (task.state != TaskState.WaitingForApproval) {
                    return@inTransaction SelectPlanResult.InvalidState
                }
                if (!connection.planBelongsToTask(command.taskId, command.planId)) {
                    return@inTransaction SelectPlanResult.PlanNotFound
                }
                if (task.selectedPlanId != command.planId) {
                    connection.selectPlan(command.taskId, command.planId, command.now)
                    connection.insertAuditEvent(command.taskId, "PlanSelected", null, null, "{}", command.now)
                }
                SelectPlanResult.Selected(connection.loadTaskDetail(command.owner, command.taskId)!!)
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

    private fun Connection.insertTask(task: Task) {
        prepareStatement(
            """
            INSERT INTO tasks (
                id, tenant_id, owner_user_id, creation_request_id, initial_goal, current_goal, title,
                state, version, selected_plan_id, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, task.id.value)
            statement.setObject(2, task.owner.tenantId.value)
            statement.setObject(3, task.owner.userId.value)
            statement.setString(4, task.creationRequestId)
            statement.setString(5, task.initialGoal)
            statement.setString(6, task.currentGoal)
            statement.setString(7, task.title)
            statement.setString(8, task.state.name)
            statement.setLong(9, task.version)
            statement.setObject(10, task.selectedPlanId?.value)
            statement.setInstant(11, task.createdAt)
            statement.setInstant(12, task.updatedAt)
            statement.executeUpdate()
        }
    }

    private fun Connection.insertConversation(conversation: Conversation) {
        prepareStatement("INSERT INTO conversations (id, task_id, created_at) VALUES (?, ?, ?)").use { statement ->
            statement.setObject(1, conversation.id.value)
            statement.setObject(2, conversation.taskId.value)
            statement.setInstant(3, conversation.createdAt)
            statement.executeUpdate()
        }
    }

    private fun Connection.insertMessage(message: ConversationMessage) {
        prepareStatement(
            """
            INSERT INTO task_messages (
                id, conversation_id, client_message_id, role, content, ai_request_id, understood_at, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, message.id.value)
            statement.setObject(2, message.conversationId.value)
            statement.setString(3, message.clientMessageId)
            statement.setString(4, message.role.name)
            statement.setString(5, message.content)
            statement.setString(6, message.aiRequestId)
            statement.setInstant(7, message.understoodAt)
            statement.setInstant(8, message.createdAt)
            statement.executeUpdate()
        }
    }

    private fun Connection.insertAssistantMessage(
        conversationId: ConversationId,
        aiRequestId: String,
        assistant: AssistantMessageWrite,
        now: Instant,
    ) {
        insertMessage(
            ConversationMessage(
                id = assistant.id,
                conversationId = conversationId,
                role = MessageRole.Assistant,
                content = assistant.text,
                clientMessageId = null,
                aiRequestId = aiRequestId,
                understoodAt = now,
                createdAt = now,
            ),
        )
    }

    private fun Connection.upsertConstraint(
        taskId: TaskId,
        messageId: MessageId,
        constraint: ConfirmedConstraintWrite,
        now: Instant,
    ) {
        prepareStatement(
            """
            INSERT INTO task_constraints (
                id, task_id, kind, value_json, strength, source, evidence_message_id,
                confirmed_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (task_id, kind) DO UPDATE SET
                id = EXCLUDED.id,
                value_json = EXCLUDED.value_json,
                strength = EXCLUDED.strength,
                source = EXCLUDED.source,
                evidence_message_id = EXCLUDED.evidence_message_id,
                confirmed_at = EXCLUDED.confirmed_at,
                updated_at = EXCLUDED.updated_at
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, constraint.id.value)
            statement.setObject(2, taskId.value)
            statement.setString(3, constraint.kind.name)
            statement.setJson(4, json.encodeToString(ConstraintValueDocument.from(constraint.value)))
            statement.setString(5, constraint.strength.name)
            statement.setString(6, ConstraintSource.UserExplicit.name)
            statement.setObject(7, messageId.value)
            statement.setInstant(8, now)
            statement.setInstant(9, now)
            statement.setInstant(10, now)
            statement.executeUpdate()
        }
    }

    private fun Connection.markMessageUnderstood(
        messageId: MessageId,
        aiRequestId: String,
        now: Instant,
    ) {
        prepareStatement("UPDATE task_messages SET ai_request_id = ?, understood_at = ? WHERE id = ?").use { statement ->
            statement.setString(1, aiRequestId)
            statement.setInstant(2, now)
            statement.setObject(3, messageId.value)
            statement.executeUpdate()
        }
    }

    private fun Connection.touchTask(
        taskId: TaskId,
        now: Instant,
    ) {
        prepareStatement("UPDATE tasks SET updated_at = ? WHERE id = ?").use { statement ->
            statement.setInstant(1, now)
            statement.setObject(2, taskId.value)
            statement.executeUpdate()
        }
    }

    private fun Connection.updateTaskState(
        taskId: TaskId,
        fromState: TaskState,
        toState: TaskState,
        currentVersion: Long,
        now: Instant,
    ) {
        transitionTask(taskId, fromState, toState, currentVersion, now, null, null)
    }

    private fun Connection.transitionTask(
        taskId: TaskId,
        fromState: TaskState,
        toState: TaskState,
        currentVersion: Long,
        now: Instant,
        requestId: String?,
        aiRequestId: String?,
    ) {
        prepareStatement("UPDATE tasks SET state = ?, version = ?, updated_at = ? WHERE id = ?").use { statement ->
            statement.setString(1, toState.name)
            statement.setLong(2, currentVersion + 1)
            statement.setInstant(3, now)
            statement.setObject(4, taskId.value)
            statement.executeUpdate()
        }
        if (fromState != toState) {
            insertAuditEvent(
                taskId = taskId,
                eventType = "TaskStateChanged",
                requestId = requestId,
                aiRequestId = aiRequestId,
                metadataJson = """{"from_state":"${fromState.name}","to_state":"${toState.name}"}""",
                occurredAt = now,
            )
        }
    }

    private fun Connection.insertPlanningRun(planningRun: PlanningRun) {
        prepareStatement(
            """
            INSERT INTO planning_runs (id, task_id, client_request_id, task_version, created_at)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, planningRun.id.value)
            statement.setObject(2, planningRun.taskId.value)
            statement.setString(3, planningRun.clientRequestId)
            statement.setLong(4, planningRun.taskVersion)
            statement.setInstant(5, planningRun.createdAt)
            statement.executeUpdate()
        }
    }

    private fun Connection.insertPlan(plan: Plan) {
        prepareStatement(
            "INSERT INTO plans (id, task_id, planning_run_id, payload_json, created_at) VALUES (?, ?, ?, ?, ?)",
        ).use { statement ->
            statement.setObject(1, plan.id.value)
            statement.setObject(2, plan.taskId.value)
            statement.setObject(3, plan.planningRunId.value)
            statement.setJson(4, json.encodeToString(PlanDocument.from(plan)))
            statement.setInstant(5, plan.createdAt)
            statement.executeUpdate()
        }
    }

    private fun Connection.selectPlan(
        taskId: TaskId,
        planId: PlanId,
        now: Instant,
    ) {
        prepareStatement("UPDATE tasks SET selected_plan_id = ?, updated_at = ? WHERE id = ?").use { statement ->
            statement.setObject(1, planId.value)
            statement.setInstant(2, now)
            statement.setObject(3, taskId.value)
            statement.executeUpdate()
        }
    }

    private fun Connection.insertAuditEvent(
        taskId: TaskId,
        eventType: String,
        requestId: String?,
        aiRequestId: String?,
        metadataJson: String,
        occurredAt: Instant,
    ) {
        prepareStatement(
            """
            INSERT INTO task_audit_events (id, task_id, event_type, request_id, ai_request_id, metadata_json, occurred_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, UUID.randomUUID())
            statement.setObject(2, taskId.value)
            statement.setString(3, eventType)
            statement.setString(4, requestId)
            statement.setString(5, aiRequestId)
            statement.setJson(6, metadataJson)
            statement.setInstant(7, occurredAt)
            statement.executeUpdate()
        }
    }

    private fun Connection.findTaskByCreationRequest(
        owner: TaskOwner,
        creationRequestId: String,
    ): Task? =
        prepareStatement(
            """
            SELECT id, tenant_id, owner_user_id, creation_request_id, initial_goal, current_goal, title,
                   state, version, selected_plan_id, created_at, updated_at
            FROM tasks
            WHERE tenant_id = ? AND owner_user_id = ? AND creation_request_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setOwner(owner)
            statement.setString(3, creationRequestId)
            statement.executeQuery().use { result -> if (result.next()) result.task() else null }
        }

    private fun Connection.lockTask(
        owner: TaskOwner,
        taskId: TaskId,
    ): Task? =
        prepareStatement(
            """
            SELECT id, tenant_id, owner_user_id, creation_request_id, initial_goal, current_goal, title,
                   state, version, selected_plan_id, created_at, updated_at
            FROM tasks
            WHERE tenant_id = ? AND owner_user_id = ? AND id = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setOwner(owner)
            statement.setObject(3, taskId.value)
            statement.executeQuery().use { result -> if (result.next()) result.task() else null }
        }

    private fun Connection.loadTaskDetail(
        owner: TaskOwner,
        taskId: TaskId,
    ): TaskDetail? {
        val task = findTask(owner, taskId) ?: return null
        val conversation = loadConversation(taskId)
        return TaskDetail(
            task = task,
            conversation = conversation,
            messages = loadMessages(conversation.id),
            constraints = loadConstraints(taskId),
            planningRuns = loadPlanningRuns(taskId),
            plans = loadPlans(taskId),
        )
    }

    private fun Connection.findTask(
        owner: TaskOwner,
        taskId: TaskId,
    ): Task? =
        prepareStatement(
            """
            SELECT id, tenant_id, owner_user_id, creation_request_id, initial_goal, current_goal, title,
                   state, version, selected_plan_id, created_at, updated_at
            FROM tasks
            WHERE tenant_id = ? AND owner_user_id = ? AND id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setOwner(owner)
            statement.setObject(3, taskId.value)
            statement.executeQuery().use { result -> if (result.next()) result.task() else null }
        }

    private fun Connection.loadConversation(taskId: TaskId): Conversation =
        prepareStatement("SELECT id, task_id, created_at FROM conversations WHERE task_id = ?").use { statement ->
            statement.setObject(1, taskId.value)
            statement.executeQuery().use { result ->
                check(result.next()) { "Task conversation is missing" }
                result.conversation()
            }
        }

    private fun Connection.findMessageByClientId(
        conversationId: ConversationId,
        clientMessageId: String,
    ): ConversationMessage? =
        prepareStatement(
            """
            SELECT id, conversation_id, client_message_id, role, content, ai_request_id, understood_at, created_at
            FROM task_messages
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
            SELECT id, conversation_id, client_message_id, role, content, ai_request_id, understood_at, created_at
            FROM task_messages
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
            SELECT id, conversation_id, client_message_id, role, content, ai_request_id, understood_at, created_at
            FROM task_messages
            WHERE conversation_id = ?
            ORDER BY created_at ASC, id ASC
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, conversationId.value)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(result.message())
                }
            }
        }

    private fun Connection.loadConstraints(taskId: TaskId): List<TaskConstraint> =
        prepareStatement(
            """
            SELECT id, task_id, kind, value_json, strength, source, evidence_message_id,
                   confirmed_at, created_at, updated_at
            FROM task_constraints
            WHERE task_id = ?
            ORDER BY created_at ASC, id ASC
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, taskId.value)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(result.constraint())
                }
            }
        }

    private fun Connection.findPlanningRun(
        taskId: TaskId,
        clientRequestId: String,
    ): PlanningRun? =
        prepareStatement(
            """
            SELECT id, task_id, client_request_id, task_version, created_at
            FROM planning_runs
            WHERE task_id = ? AND client_request_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, taskId.value)
            statement.setString(2, clientRequestId)
            statement.executeQuery().use { result -> if (result.next()) result.planningRun() else null }
        }

    private fun Connection.loadPlanningRuns(taskId: TaskId): List<PlanningRun> =
        prepareStatement(
            """
            SELECT id, task_id, client_request_id, task_version, created_at
            FROM planning_runs
            WHERE task_id = ?
            ORDER BY created_at ASC, id ASC
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, taskId.value)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(result.planningRun())
                }
            }
        }

    private fun Connection.loadPlans(taskId: TaskId): List<Plan> =
        prepareStatement(
            """
            SELECT payload_json
            FROM plans
            WHERE task_id = ?
            ORDER BY created_at ASC, id ASC
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, taskId.value)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(json.decodeFromString<PlanDocument>(result.getString("payload_json")).toDomain())
                }
            }
        }

    private fun Connection.planBelongsToTask(
        taskId: TaskId,
        planId: PlanId,
    ): Boolean =
        prepareStatement("SELECT 1 FROM plans WHERE task_id = ? AND id = ?").use { statement ->
            statement.setObject(1, taskId.value)
            statement.setObject(2, planId.value)
            statement.executeQuery().use(ResultSet::next)
        }

    private fun ResultSet.task(): Task =
        Task(
            id = TaskId(getObject("id", UUID::class.java)),
            owner = TaskOwner(TenantId(getObject("tenant_id", UUID::class.java)), UserId(getObject("owner_user_id", UUID::class.java))),
            creationRequestId = getString("creation_request_id"),
            initialGoal = getString("initial_goal"),
            currentGoal = getString("current_goal"),
            title = getString("title"),
            state = TaskState.valueOf(getString("state")),
            version = getLong("version"),
            selectedPlanId = getObject("selected_plan_id", UUID::class.java)?.let(::PlanId),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )

    private fun ResultSet.conversation(): Conversation =
        Conversation(
            id = ConversationId(getObject("id", UUID::class.java)),
            taskId = TaskId(getObject("task_id", UUID::class.java)),
            createdAt = getTimestamp("created_at").toInstant(),
        )

    private fun ResultSet.message(): ConversationMessage =
        ConversationMessage(
            id = MessageId(getObject("id", UUID::class.java)),
            conversationId = ConversationId(getObject("conversation_id", UUID::class.java)),
            role = MessageRole.valueOf(getString("role")),
            content = getString("content"),
            clientMessageId = getString("client_message_id"),
            aiRequestId = getString("ai_request_id"),
            understoodAt = getTimestamp("understood_at")?.toInstant(),
            createdAt = getTimestamp("created_at").toInstant(),
        )

    private fun ResultSet.constraint(): TaskConstraint =
        TaskConstraint(
            id = ConstraintId(getObject("id", UUID::class.java)),
            taskId = TaskId(getObject("task_id", UUID::class.java)),
            kind = ConstraintKind.valueOf(getString("kind")),
            value = json.decodeFromString<ConstraintValueDocument>(getString("value_json")).toDomain(),
            strength = ConstraintStrength.valueOf(getString("strength")),
            source = ConstraintSource.valueOf(getString("source")),
            evidenceMessageId = MessageId(getObject("evidence_message_id", UUID::class.java)),
            confirmedAt = getTimestamp("confirmed_at").toInstant(),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )

    private fun ResultSet.planningRun(): PlanningRun =
        PlanningRun(
            id = PlanningRunId(getObject("id", UUID::class.java)),
            taskId = TaskId(getObject("task_id", UUID::class.java)),
            clientRequestId = getString("client_request_id"),
            taskVersion = getLong("task_version"),
            createdAt = getTimestamp("created_at").toInstant(),
        )

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

    private fun java.sql.PreparedStatement.setJson(
        index: Int,
        value: String,
    ) {
        setObject(
            index,
            PGobject().apply {
                type = "jsonb"
                this.value = value
            },
        )
    }

    @Serializable
    private data class AiUnderstandingAuditDocument(
        @SerialName("capability")
        val capability: String,
        @SerialName("taskVersion")
        val taskVersion: Long,
        @SerialName("provider")
        val provider: String? = null,
        @SerialName("model")
        val model: String? = null,
        @SerialName("promptVersion")
        val promptVersion: String? = null,
        @SerialName("providerRequestId")
        val providerRequestId: String? = null,
        @SerialName("attemptCount")
        val attemptCount: Int? = null,
        @SerialName("outcome")
        val outcome: String,
        @SerialName("latencyMs")
        val latencyMs: Long? = null,
        @SerialName("failureCategory")
        val failureCategory: String? = null,
    ) {
        companion object {
            fun from(command: RecordAiUnderstandingAuditCommand): AiUnderstandingAuditDocument =
                AiUnderstandingAuditDocument(
                    capability = "UnderstandUserMessage",
                    taskVersion = command.taskVersion,
                    provider = command.provider,
                    model = command.model,
                    promptVersion = command.promptVersion,
                    providerRequestId = command.providerRequestId,
                    attemptCount = command.attemptCount,
                    outcome = command.outcome,
                    latencyMs = command.latencyMs,
                    failureCategory = command.failureCategory,
                )
        }
    }

    private fun AiUnderstandingAuditEventType.auditEventName(): String =
        when (this) {
            AiUnderstandingAuditEventType.Started -> "AiUnderstandingStarted"
            AiUnderstandingAuditEventType.Succeeded -> "AiUnderstandingSucceeded"
            AiUnderstandingAuditEventType.Failed -> "AiUnderstandingFailed"
        }

    @Serializable
    private sealed class ConstraintValueDocument {
        abstract fun toDomain(): ConstraintValue

        @Serializable
        @SerialName("time_window")
        data class TimeWindow(
            @SerialName("startAt")
            val startAt: String? = null,
            @SerialName("endAt")
            val endAt: String? = null,
            @SerialName("timeZoneId")
            val timeZoneId: String,
            @SerialName("originalText")
            val originalText: String,
        ) : ConstraintValueDocument() {
            override fun toDomain(): ConstraintValue =
                ConstraintValue.TimeWindow(startAt?.let(Instant::parse), endAt?.let(Instant::parse), timeZoneId, originalText)
        }

        @Serializable
        @SerialName("budget_limit")
        data class BudgetLimit(
            @SerialName("wholeUnits")
            val wholeUnits: Long,
            @SerialName("currencyCode")
            val currencyCode: String? = null,
        ) : ConstraintValueDocument() {
            override fun toDomain(): ConstraintValue = ConstraintValue.BudgetLimit(wholeUnits, currencyCode)
        }

        @Serializable
        @SerialName("commute_limit")
        data class CommuteLimit(
            @SerialName("maxMinutes")
            val maxMinutes: Int,
        ) : ConstraintValueDocument() {
            override fun toDomain(): ConstraintValue = ConstraintValue.CommuteLimit(maxMinutes)
        }

        @Serializable
        @SerialName("location")
        data class Location(
            @SerialName("text")
            val text: String,
        ) : ConstraintValueDocument() {
            override fun toDomain(): ConstraintValue = ConstraintValue.Location(text)
        }

        @Serializable
        @SerialName("activity_domain")
        data class ActivityDomain(
            @SerialName("value")
            val value: String,
        ) : ConstraintValueDocument() {
            override fun toDomain(): ConstraintValue = ConstraintValue.ActivityDomain(value)
        }

        @Serializable
        @SerialName("topic")
        data class Topic(
            @SerialName("text")
            val text: String,
        ) : ConstraintValueDocument() {
            override fun toDomain(): ConstraintValue = ConstraintValue.Topic(text)
        }

        @Serializable
        @SerialName("experience_preference")
        data class ExperiencePreference(
            @SerialName("text")
            val text: String,
        ) : ConstraintValueDocument() {
            override fun toDomain(): ConstraintValue = ConstraintValue.ExperiencePreference(text)
        }

        companion object {
            fun from(value: ConstraintValue): ConstraintValueDocument =
                when (value) {
                    is ConstraintValue.TimeWindow ->
                        TimeWindow(value.startAt?.toString(), value.endAt?.toString(), value.timeZoneId, value.originalText)
                    is ConstraintValue.BudgetLimit -> BudgetLimit(value.wholeUnits, value.currencyCode)
                    is ConstraintValue.CommuteLimit -> CommuteLimit(value.maxMinutes)
                    is ConstraintValue.Location -> Location(value.text)
                    is ConstraintValue.ActivityDomain -> ActivityDomain(value.value)
                    is ConstraintValue.Topic -> Topic(value.text)
                    is ConstraintValue.ExperiencePreference -> ExperiencePreference(value.text)
                }
        }
    }

    @Serializable
    private data class PlanDocument(
        @SerialName("id")
        val id: String,
        @SerialName("taskId")
        val taskId: String,
        @SerialName("planningRunId")
        val planningRunId: String,
        @SerialName("direction")
        val direction: String,
        @SerialName("title")
        val title: String,
        @SerialName("summary")
        val summary: String,
        @SerialName("timeline")
        val timeline: List<PlanTimelineItemDocument>,
        @SerialName("estimatedCost")
        val estimatedCost: PlanEstimatedCostDocument?,
        @SerialName("commuteMinutes")
        val commuteMinutes: Int?,
        @SerialName("satisfiedConstraintIds")
        val satisfiedConstraintIds: List<String>,
        @SerialName("tradeoffs")
        val tradeoffs: List<String>,
        @SerialName("reasons")
        val reasons: List<String>,
        @SerialName("sourceRefs")
        val sourceRefs: List<PlanSourceRefDocument>,
        @SerialName("validUntil")
        val validUntil: String?,
        @SerialName("createdAt")
        val createdAt: String,
    ) {
        fun toDomain(): Plan =
            Plan(
                id = PlanId(UUID.fromString(id)),
                taskId = TaskId(UUID.fromString(taskId)),
                planningRunId = PlanningRunId(UUID.fromString(planningRunId)),
                direction = direction,
                title = title,
                summary = summary,
                timeline = timeline.map { it.toDomain() },
                estimatedCost = estimatedCost?.toDomain(),
                commuteMinutes = commuteMinutes,
                satisfiedConstraintIds = satisfiedConstraintIds.map { ConstraintId(UUID.fromString(it)) },
                tradeoffs = tradeoffs,
                reasons = reasons,
                sourceRefs = sourceRefs.map { it.toDomain() },
                validUntil = validUntil?.let(Instant::parse),
                createdAt = Instant.parse(createdAt),
            )

        companion object {
            fun from(plan: Plan): PlanDocument =
                PlanDocument(
                    id = plan.id.value.toString(),
                    taskId = plan.taskId.value.toString(),
                    planningRunId = plan.planningRunId.value.toString(),
                    direction = plan.direction,
                    title = plan.title,
                    summary = plan.summary,
                    timeline = plan.timeline.map(PlanTimelineItemDocument::from),
                    estimatedCost = plan.estimatedCost?.let(PlanEstimatedCostDocument::from),
                    commuteMinutes = plan.commuteMinutes,
                    satisfiedConstraintIds = plan.satisfiedConstraintIds.map { it.value.toString() },
                    tradeoffs = plan.tradeoffs,
                    reasons = plan.reasons,
                    sourceRefs = plan.sourceRefs.map(PlanSourceRefDocument::from),
                    validUntil = plan.validUntil?.toString(),
                    createdAt = plan.createdAt.toString(),
                )
        }
    }

    @Serializable
    private data class PlanTimelineItemDocument(
        @SerialName("title")
        val title: String,
        @SerialName("startAt")
        val startAt: String?,
        @SerialName("endAt")
        val endAt: String?,
        @SerialName("location")
        val location: String?,
    ) {
        fun toDomain(): PlanTimelineItem =
            PlanTimelineItem(title, startAt?.let(Instant::parse), endAt?.let(Instant::parse), location)

        companion object {
            fun from(item: PlanTimelineItem): PlanTimelineItemDocument =
                PlanTimelineItemDocument(item.title, item.startAt?.toString(), item.endAt?.toString(), item.location)
        }
    }

    @Serializable
    private data class PlanEstimatedCostDocument(
        @SerialName("wholeUnits")
        val wholeUnits: Long,
        @SerialName("currencyCode")
        val currencyCode: String?,
    ) {
        fun toDomain(): PlanEstimatedCost = PlanEstimatedCost(wholeUnits, currencyCode)

        companion object {
            fun from(cost: PlanEstimatedCost): PlanEstimatedCostDocument = PlanEstimatedCostDocument(cost.wholeUnits, cost.currencyCode)
        }
    }

    @Serializable
    private data class PlanSourceRefDocument(
        @SerialName("label")
        val label: String,
        @SerialName("uri")
        val uri: String?,
    ) {
        fun toDomain(): PlanSourceRef = PlanSourceRef(label, uri)

        companion object {
            fun from(sourceRef: PlanSourceRef): PlanSourceRefDocument = PlanSourceRefDocument(sourceRef.label, sourceRef.uri)
        }
    }

    private companion object {
        const val INITIAL_TASK_VERSION = 1L
    }
}
