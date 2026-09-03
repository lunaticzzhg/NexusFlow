package com.nexusflow.backend.feature.task.infrastructure

import com.nexusflow.backend.feature.task.domain.ActivityModeValue
import com.nexusflow.backend.feature.task.domain.AiInvocationDiagnostics
import com.nexusflow.backend.feature.task.domain.AiModelTokenUsage
import com.nexusflow.backend.feature.task.domain.AiUnderstandingAuditEventType
import com.nexusflow.backend.feature.task.domain.AppendUserMessageCommand
import com.nexusflow.backend.feature.task.domain.AppendUserMessageResult
import com.nexusflow.backend.feature.task.domain.ApplyUnderstandingCommand
import com.nexusflow.backend.feature.task.domain.ApplyUnderstandingResult
import com.nexusflow.backend.feature.task.domain.AssistantMessageWrite
import com.nexusflow.backend.feature.task.domain.AvailabilityFact
import com.nexusflow.backend.feature.task.domain.CommutePreferenceValue
import com.nexusflow.backend.feature.task.domain.TaskMessage
import com.nexusflow.backend.feature.task.domain.CreateTaskPersistenceCommand
import com.nexusflow.backend.feature.task.domain.CreateTaskPersistenceResult
import com.nexusflow.backend.feature.task.domain.DeleteRequirementCommand
import com.nexusflow.backend.feature.task.domain.DurationFact
import com.nexusflow.backend.feature.task.domain.FactValue
import com.nexusflow.backend.feature.task.domain.LocationFact
import com.nexusflow.backend.feature.task.domain.MessageId
import com.nexusflow.backend.feature.task.domain.MessageRole
import com.nexusflow.backend.feature.task.domain.MoneyFact
import com.nexusflow.backend.feature.task.domain.Opportunity
import com.nexusflow.backend.feature.task.domain.OpportunityFacts
import com.nexusflow.backend.feature.task.domain.OpportunityId
import com.nexusflow.backend.feature.task.domain.OpportunityKind
import com.nexusflow.backend.feature.task.domain.PersistPlansCommand
import com.nexusflow.backend.feature.task.domain.PersistPlansResult
import com.nexusflow.backend.feature.task.domain.Plan
import com.nexusflow.backend.feature.task.domain.PlanDirection
import com.nexusflow.backend.feature.task.domain.PlanEstimatedCost
import com.nexusflow.backend.feature.task.domain.PlanId
import com.nexusflow.backend.feature.task.domain.PlanSourceRef
import com.nexusflow.backend.feature.task.domain.PlanTimelineItem
import com.nexusflow.backend.feature.task.domain.RecordAiUnderstandingAuditCommand
import com.nexusflow.backend.feature.task.domain.RecordAiUnderstandingAuditResult
import com.nexusflow.backend.feature.task.domain.Requirement
import com.nexusflow.backend.feature.task.domain.RequirementEvaluation
import com.nexusflow.backend.feature.task.domain.RequirementEvaluationResult
import com.nexusflow.backend.feature.task.domain.RequirementEvidence
import com.nexusflow.backend.feature.task.domain.RequirementId
import com.nexusflow.backend.feature.task.domain.RequirementKind
import com.nexusflow.backend.feature.task.domain.RequirementMutationResult
import com.nexusflow.backend.feature.task.domain.RequirementSource
import com.nexusflow.backend.feature.task.domain.RequirementStrength
import com.nexusflow.backend.feature.task.domain.RequirementValue
import com.nexusflow.backend.feature.task.domain.RequirementWrite
import com.nexusflow.backend.feature.task.domain.SelectPlanCommand
import com.nexusflow.backend.feature.task.domain.SelectPlanResult
import com.nexusflow.backend.feature.task.domain.SourceRef
import com.nexusflow.backend.feature.task.domain.Task
import com.nexusflow.backend.feature.task.domain.TaskDetail
import com.nexusflow.backend.feature.task.domain.TaskId
import com.nexusflow.backend.feature.task.domain.TaskOwner
import com.nexusflow.backend.feature.task.domain.TaskRepository
import com.nexusflow.backend.feature.task.domain.TenantId
import com.nexusflow.backend.feature.task.domain.UpdateRequirementCommand
import com.nexusflow.backend.feature.task.domain.UserId
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
    override suspend fun createTask(command: CreateTaskPersistenceCommand): CreateTaskPersistenceResult =
        blocking {
            inTransaction { connection ->
                connection.findTaskByCreationRequest(command.owner, command.creationRequestId)?.let { existing ->
                    return@inTransaction if (existing.intent == command.message.trim()) {
                        CreateTaskPersistenceResult.Existing(connection.loadTaskDetail(command.owner, existing.id)!!)
                    } else {
                        CreateTaskPersistenceResult.ConflictingRequest
                    }
                }

                val task = Task(
                    id = command.taskId,
                    owner = command.owner,
                    creationRequestId = command.creationRequestId,
                    intent = command.message.trim(),
                    revision = INITIAL_TASK_REVISION,
                    selectedPlanId = null,
                    createdAt = command.now,
                    updatedAt = command.now,
                    archivedAt = null,
                )
                connection.insertTask(task)
                val message = TaskMessage(
                    id = command.firstMessageId,
                    taskId = command.taskId,
                    role = MessageRole.User,
                    content = command.message,
                    clientMessageId = command.creationRequestId,
                    aiRequestId = command.aiRequestId,
                    understoodAt = null,
                    createdAt = command.now,
                )
                connection.insertMessage(message)
                connection.insertAuditEvent(command.taskId, "TaskCreated", command.creationRequestId, null, "{}", command.now)
                CreateTaskPersistenceResult.Created(connection.loadTaskDetail(command.owner, command.taskId)!!, message, task.revision)
            }
        }

    override suspend fun listTaskSummaries(owner: TaskOwner): List<TaskDetail> =
        blocking {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    SELECT id
                    FROM tasks
                    WHERE tenant_id = ? AND owner_user_id = ? AND archived_at IS NULL
                    ORDER BY updated_at DESC
                    """.trimIndent(),
                ).use { statement ->
                    statement.setOwner(owner)
                    statement.executeQuery().use { result ->
                        buildList {
                            while (result.next()) {
                                add(connection.loadTaskDetail(owner, TaskId(result.getObject("id", UUID::class.java)))!!)
                            }
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

    override suspend fun listTaskContextKeys(
        owner: TaskOwner,
        taskId: TaskId,
    ): List<String> =
        blocking {
            dataSource.connection.use { connection ->
                connection.findTask(owner, taskId) ?: return@use emptyList()
                connection.loadTaskContextKeys(taskId)
            }
        }

    override suspend fun appendUserMessage(command: AppendUserMessageCommand): AppendUserMessageResult =
        blocking {
            inTransaction { connection ->
                val task = connection.lockTask(command.owner, command.taskId) ?: return@inTransaction AppendUserMessageResult.TaskNotFound
                connection.findMessageByClientId(command.taskId, command.clientMessageId)?.let { existing ->
                    return@inTransaction if (existing.content == command.text) {
                        AppendUserMessageResult.Existing(connection.loadTaskDetail(command.owner, command.taskId)!!)
                    } else {
                        AppendUserMessageResult.ConflictingMessage
                    }
                }
                val message = TaskMessage(
                    id = command.messageId,
                    taskId = command.taskId,
                    role = MessageRole.User,
                    content = command.text,
                    clientMessageId = command.clientMessageId,
                    aiRequestId = command.aiRequestId,
                    understoodAt = null,
                    createdAt = command.now,
                )
                connection.insertMessage(message)
                connection.touchTask(command.taskId, command.now)
                connection.insertAuditEvent(command.taskId, "MessageSent", command.clientMessageId, command.aiRequestId, "{}", command.now)
                AppendUserMessageResult.Appended(connection.loadTaskDetail(command.owner, command.taskId)!!, message, task.revision)
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
                if (task.revision != command.expectedTaskRevision) {
                    return@inTransaction ApplyUnderstandingResult.StaleTaskRevision
                }
                val message = connection.findMessage(command.taskId, command.messageId)
                    ?: return@inTransaction ApplyUnderstandingResult.MessageNotFound

                command.requirements.forEach { requirement ->
                    connection.upsertRequirement(command.taskId, message.id, requirement, command.now)
                    connection.insertAuditEvent(
                        command.taskId,
                        "RequirementConfirmed",
                        message.clientMessageId,
                        command.aiRequestId,
                        """{"kind":"${requirement.kind.name}"}""",
                        command.now,
                    )
                }
                val newSelectionCount = connection.insertTaskContextSelections(
                    taskId = command.taskId,
                    contextKeys = command.selectedTaskContextKeys,
                    selectedAt = command.now,
                )
                command.assistantMessage?.let { assistant ->
                    connection.insertAssistantMessage(command.taskId, command.aiRequestId, assistant, command.now)
                }
                connection.markMessageUnderstood(message.id, command.aiRequestId, command.now)

                val intentPatch = command.intentPatch?.trim()?.takeIf(String::isNotBlank)
                val intentChanged = intentPatch != null && intentPatch != task.intent
                val changedPlanningInputs = intentChanged || command.requirements.isNotEmpty() || newSelectionCount > 0
                if (changedPlanningInputs) {
                    connection.updateTaskAfterPlanningInputChange(
                        taskId = command.taskId,
                        intent = intentPatch ?: task.intent,
                        revision = task.revision + 1,
                        now = command.now,
                    )
                } else {
                    connection.touchTask(command.taskId, command.now)
                }
                ApplyUnderstandingResult.Applied(connection.loadTaskDetail(command.owner, command.taskId)!!, changedPlanningInputs)
            }
        }

    override suspend fun updateRequirement(command: UpdateRequirementCommand): RequirementMutationResult =
        blocking {
            inTransaction { connection ->
                val task = connection.lockTask(command.owner, command.taskId)
                    ?: return@inTransaction RequirementMutationResult.TaskNotFound
                if (!connection.requirementBelongsToTask(command.taskId, command.requirementId)) {
                    return@inTransaction RequirementMutationResult.RequirementNotFound
                }
                connection.updateRequirement(command)
                connection.updateTaskAfterPlanningInputChange(command.taskId, task.intent, task.revision + 1, command.now)
                connection.insertAuditEvent(
                    command.taskId,
                    "RequirementUpdated",
                    null,
                    null,
                    """{"requirementId":"${command.requirementId.value}"}""",
                    command.now,
                )
                RequirementMutationResult.Mutated(connection.loadTaskDetail(command.owner, command.taskId)!!)
            }
        }

    override suspend fun deleteRequirement(command: DeleteRequirementCommand): RequirementMutationResult =
        blocking {
            inTransaction { connection ->
                val task = connection.lockTask(command.owner, command.taskId)
                    ?: return@inTransaction RequirementMutationResult.TaskNotFound
                if (!connection.requirementBelongsToTask(command.taskId, command.requirementId)) {
                    return@inTransaction RequirementMutationResult.RequirementNotFound
                }
                connection.deleteRequirement(command.taskId, command.requirementId)
                connection.updateTaskAfterPlanningInputChange(command.taskId, task.intent, task.revision + 1, command.now)
                connection.insertAuditEvent(
                    command.taskId,
                    "RequirementRemoved",
                    null,
                    null,
                    """{"requirementId":"${command.requirementId.value}"}""",
                    command.now,
                )
                RequirementMutationResult.Mutated(connection.loadTaskDetail(command.owner, command.taskId)!!)
            }
        }

    override suspend fun persistPlans(command: PersistPlansCommand): PersistPlansResult =
        blocking {
            try {
                inTransaction { connection ->
                    val task = connection.lockTask(command.owner, command.taskId) ?: return@inTransaction PersistPlansResult.TaskNotFound
                    if (task.revision != command.expectedTaskRevision) {
                        return@inTransaction PersistPlansResult.StaleTaskRevision
                    }
                    if (command.plans.any { it.taskId != command.taskId || it.revision != command.expectedTaskRevision }) {
                        error("Plans must belong to the task and current revision")
                    }
                    if (command.plans.isEmpty() || command.plans.any { it.validUntil == null }) {
                        error("Planning result must contain current plans with validUntil")
                    }
                    val opportunityIds = command.opportunities.mapTo(mutableSetOf()) { it.id }
                    if (command.plans.flatMap { it.opportunityRefs }.any { it !in opportunityIds }) {
                        error("Plans must reference persisted opportunity snapshots")
                    }

                    command.opportunities.forEach { connection.upsertOpportunity(it) }
                    command.plans.forEach { plan ->
                        connection.insertPlan(plan)
                        connection.replacePlanOpportunityRefs(plan)
                        connection.replacePlanRequirementEvaluations(plan)
                    }
                    connection.clearSelectedPlan(command.taskId, command.now)
                    connection.insertAuditEvent(
                        command.taskId,
                        "PlansCreated",
                        null,
                        null,
                        json.encodeToString(PlanningAuditDocument.from(command)),
                        command.now,
                    )
                    PersistPlansResult.Persisted(connection.loadTaskDetail(command.owner, command.taskId)!!)
                }
            } catch (_: StaleRevisionWriteException) {
                PersistPlansResult.StaleTaskRevision
            }
        }

    override suspend fun selectCurrentPlan(command: SelectPlanCommand): SelectPlanResult =
        blocking {
            inTransaction { connection ->
                val task = connection.lockTask(command.owner, command.taskId) ?: return@inTransaction SelectPlanResult.TaskNotFound
                val plan = connection.findPlan(command.taskId, command.planId)
                    ?: return@inTransaction SelectPlanResult.PlanNotFound
                if (plan.revision != task.revision) {
                    return@inTransaction SelectPlanResult.RevisionConflict
                }
                if (plan.validUntil == null || !plan.validUntil.isAfter(command.now)) {
                    return@inTransaction SelectPlanResult.Expired
                }
                if (task.selectedPlanId != command.planId) {
                    connection.selectPlan(command.taskId, command.planId, command.now)
                    connection.insertAuditEvent(
                        command.taskId,
                        "PlanSelected",
                        null,
                        null,
                        json.encodeToString(PlanSelectedAuditDocument.from(plan)),
                        command.now,
                    )
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
                id, tenant_id, owner_user_id, creation_request_id, intent, revision,
                selected_plan_id, created_at, updated_at, archived_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, task.id.value)
            statement.setObject(2, task.owner.tenantId.value)
            statement.setObject(3, task.owner.userId.value)
            statement.setString(4, task.creationRequestId)
            statement.setString(5, task.intent)
            statement.setLong(6, task.revision)
            statement.setObject(7, task.selectedPlanId?.value)
            statement.setInstant(8, task.createdAt)
            statement.setInstant(9, task.updatedAt)
            statement.setInstant(10, task.archivedAt)
            statement.executeUpdate()
        }
    }

    private fun Connection.insertMessage(message: TaskMessage) {
        prepareStatement(
            """
            INSERT INTO task_messages (
                id, task_id, client_message_id, role, content, ai_request_id, understood_at, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, message.id.value)
            statement.setObject(2, message.taskId.value)
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
        taskId: TaskId,
        aiRequestId: String,
        assistant: AssistantMessageWrite,
        now: Instant,
    ) {
        insertMessage(
            TaskMessage(
                id = assistant.id,
                taskId = taskId,
                role = MessageRole.Assistant,
                content = assistant.text,
                clientMessageId = null,
                aiRequestId = aiRequestId,
                understoodAt = now,
                createdAt = now,
            ),
        )
    }

    private fun Connection.upsertRequirement(
        taskId: TaskId,
        messageId: MessageId,
        requirement: RequirementWrite,
        now: Instant,
    ) {
        prepareStatement(
            """
            INSERT INTO task_requirements (
                id, task_id, kind, value_json, strength, source, evidence_message_id, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (task_id, kind) DO UPDATE SET
                value_json = EXCLUDED.value_json,
                strength = EXCLUDED.strength,
                source = EXCLUDED.source,
                evidence_message_id = EXCLUDED.evidence_message_id,
                updated_at = EXCLUDED.updated_at
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, requirement.id.value)
            statement.setObject(2, taskId.value)
            statement.setString(3, requirement.kind.name)
            statement.setJson(4, json.encodeToString(RequirementValueDocument.from(requirement.value)))
            statement.setString(5, requirement.strength.name)
            statement.setString(6, requirement.source.name)
            statement.setObject(7, messageId.value)
            statement.setInstant(8, now)
            statement.setInstant(9, now)
            statement.executeUpdate()
        }
    }

    private fun Connection.updateRequirement(command: UpdateRequirementCommand) {
        prepareStatement(
            """
            UPDATE task_requirements
            SET kind = ?, value_json = ?, strength = ?, source = ?, evidence_message_id = NULL, updated_at = ?
            WHERE task_id = ? AND id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, command.kind.name)
            statement.setJson(2, json.encodeToString(RequirementValueDocument.from(command.value)))
            statement.setString(3, command.strength.name)
            statement.setString(4, RequirementSource.UserExplicit.name)
            statement.setInstant(5, command.now)
            statement.setObject(6, command.taskId.value)
            statement.setObject(7, command.requirementId.value)
            statement.executeUpdate()
        }
    }

    private fun Connection.upsertOpportunity(opportunity: Opportunity) {
        prepareStatement(
            """
            INSERT INTO opportunity_snapshots (
                id, provider, external_key, kind, title, facts_json, sources_json, observed_at, valid_until
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET
                provider = EXCLUDED.provider,
                external_key = EXCLUDED.external_key,
                kind = EXCLUDED.kind,
                title = EXCLUDED.title,
                facts_json = EXCLUDED.facts_json,
                sources_json = EXCLUDED.sources_json,
                observed_at = EXCLUDED.observed_at,
                valid_until = EXCLUDED.valid_until
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, opportunity.id.value)
            statement.setString(2, opportunity.provider)
            statement.setString(3, opportunity.externalKey)
            statement.setString(4, opportunity.kind.name)
            statement.setString(5, opportunity.title)
            statement.setJson(6, json.encodeToString(OpportunityFactsDocument.from(opportunity.facts)))
            statement.setJson(7, json.encodeToString(opportunity.sources.map(SourceRefDocument::from)))
            statement.setInstant(8, opportunity.observedAt)
            statement.setInstant(9, opportunity.validUntil)
            statement.executeUpdate()
        }
    }

    private fun Connection.insertPlan(plan: Plan) {
        prepareStatement(
            """
            INSERT INTO plans (
                id, task_id, revision, direction, title, summary, timeline_json, estimated_cost_json,
                commute_minutes, tradeoffs_json, reasons_json, valid_until, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, plan.id.value)
            statement.setObject(2, plan.taskId.value)
            statement.setLong(3, plan.revision)
            statement.setString(4, plan.direction.name)
            statement.setString(5, plan.title)
            statement.setString(6, plan.summary)
            statement.setJson(7, json.encodeToString(plan.timeline.map(PlanTimelineItemDocument::from)))
            statement.setNullableJson(8, plan.estimatedCost?.let { json.encodeToString(PlanEstimatedCostDocument.from(it)) })
            statement.setObject(9, plan.commuteMinutes)
            statement.setJson(10, json.encodeToString(plan.tradeoffs))
            statement.setJson(11, json.encodeToString(plan.reasons))
            statement.setInstant(12, plan.validUntil)
            statement.setInstant(13, plan.createdAt)
            statement.executeUpdate()
        }
    }

    private fun Connection.replacePlanOpportunityRefs(plan: Plan) {
        prepareStatement("DELETE FROM plan_opportunities WHERE plan_id = ?").use { statement ->
            statement.setObject(1, plan.id.value)
            statement.executeUpdate()
        }
        prepareStatement("INSERT INTO plan_opportunities (plan_id, opportunity_snapshot_id) VALUES (?, ?)").use { statement ->
            plan.opportunityRefs.distinct().forEach { opportunityId ->
                statement.setObject(1, plan.id.value)
                statement.setObject(2, opportunityId.value)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun Connection.replacePlanRequirementEvaluations(plan: Plan) {
        prepareStatement("DELETE FROM plan_requirement_evaluations WHERE plan_id = ?").use { statement ->
            statement.setObject(1, plan.id.value)
            statement.executeUpdate()
        }
        prepareStatement(
            """
            INSERT INTO plan_requirement_evaluations (plan_id, requirement_id, result, explanation)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            plan.requirementEvaluations.forEach { evaluation ->
                statement.setObject(1, plan.id.value)
                statement.setObject(2, evaluation.requirementId.value)
                statement.setString(3, evaluation.result.name)
                statement.setString(4, evaluation.explanation)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun Connection.insertTaskContextSelections(
        taskId: TaskId,
        contextKeys: List<String>,
        selectedAt: Instant,
    ): Int {
        if (contextKeys.isEmpty()) return 0
        var inserted = 0
        prepareStatement(
            """
            INSERT INTO task_context_selections (task_id, context_key, selected_at)
            VALUES (?, ?, ?)
            ON CONFLICT (task_id, context_key) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            contextKeys.forEach { key ->
                statement.setObject(1, taskId.value)
                statement.setString(2, key)
                statement.setInstant(3, selectedAt)
                inserted += statement.executeUpdate()
            }
        }
        return inserted
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

    private fun Connection.updateTaskAfterPlanningInputChange(
        taskId: TaskId,
        intent: String,
        revision: Long,
        now: Instant,
    ) {
        prepareStatement(
            """
            UPDATE tasks
            SET intent = ?, revision = ?, selected_plan_id = NULL, updated_at = ?
            WHERE id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, intent)
            statement.setLong(2, revision)
            statement.setInstant(3, now)
            statement.setObject(4, taskId.value)
            statement.executeUpdate()
        }
    }

    private fun Connection.clearSelectedPlan(
        taskId: TaskId,
        now: Instant,
    ) {
        prepareStatement("UPDATE tasks SET selected_plan_id = NULL, updated_at = ? WHERE id = ?").use { statement ->
            statement.setInstant(1, now)
            statement.setObject(2, taskId.value)
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

    private fun Connection.deleteRequirement(
        taskId: TaskId,
        requirementId: RequirementId,
    ) {
        prepareStatement("DELETE FROM task_requirements WHERE task_id = ? AND id = ?").use { statement ->
            statement.setObject(1, taskId.value)
            statement.setObject(2, requirementId.value)
            statement.executeUpdate()
        }
    }

    private fun Connection.requirementBelongsToTask(
        taskId: TaskId,
        requirementId: RequirementId,
    ): Boolean =
        prepareStatement("SELECT 1 FROM task_requirements WHERE task_id = ? AND id = ?").use { statement ->
            statement.setObject(1, taskId.value)
            statement.setObject(2, requirementId.value)
            statement.executeQuery().use(ResultSet::next)
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
            SELECT id, tenant_id, owner_user_id, creation_request_id, intent, revision,
                   selected_plan_id, created_at, updated_at, archived_at
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
            SELECT id, tenant_id, owner_user_id, creation_request_id, intent, revision,
                   selected_plan_id, created_at, updated_at, archived_at
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
        return TaskDetail(
            task = task,
            messages = loadMessages(taskId),
            requirements = loadRequirements(taskId),
            plans = loadPlans(taskId),
            selectedContextKeys = loadTaskContextKeys(taskId),
        )
    }

    private fun Connection.findTask(
        owner: TaskOwner,
        taskId: TaskId,
    ): Task? =
        prepareStatement(
            """
            SELECT id, tenant_id, owner_user_id, creation_request_id, intent, revision,
                   selected_plan_id, created_at, updated_at, archived_at
            FROM tasks
            WHERE tenant_id = ? AND owner_user_id = ? AND id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setOwner(owner)
            statement.setObject(3, taskId.value)
            statement.executeQuery().use { result -> if (result.next()) result.task() else null }
        }

    private fun Connection.findMessageByClientId(
        taskId: TaskId,
        clientMessageId: String,
    ): TaskMessage? =
        prepareStatement(
            """
            SELECT id, task_id, client_message_id, role, content, ai_request_id, understood_at, created_at
            FROM task_messages
            WHERE task_id = ? AND client_message_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, taskId.value)
            statement.setString(2, clientMessageId)
            statement.executeQuery().use { result -> if (result.next()) result.message() else null }
        }

    private fun Connection.findMessage(
        taskId: TaskId,
        messageId: MessageId,
    ): TaskMessage? =
        prepareStatement(
            """
            SELECT id, task_id, client_message_id, role, content, ai_request_id, understood_at, created_at
            FROM task_messages
            WHERE task_id = ? AND id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, taskId.value)
            statement.setObject(2, messageId.value)
            statement.executeQuery().use { result -> if (result.next()) result.message() else null }
        }

    private fun Connection.loadMessages(taskId: TaskId): List<TaskMessage> =
        prepareStatement(
            """
            SELECT id, task_id, client_message_id, role, content, ai_request_id, understood_at, created_at
            FROM task_messages
            WHERE task_id = ?
            ORDER BY created_at ASC, id ASC
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, taskId.value)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(result.message())
                }
            }
        }

    private fun Connection.loadTaskContextKeys(taskId: TaskId): List<String> =
        prepareStatement(
            """
            SELECT context_key
            FROM task_context_selections
            WHERE task_id = ?
            ORDER BY selected_at ASC, context_key ASC
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, taskId.value)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(result.getString("context_key"))
                }
            }
        }

    private fun Connection.loadRequirements(taskId: TaskId): List<Requirement> =
        prepareStatement(
            """
            SELECT id, task_id, kind, value_json, strength, source, evidence_message_id, created_at, updated_at
            FROM task_requirements
            WHERE task_id = ?
            ORDER BY created_at ASC, id ASC
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, taskId.value)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(result.requirement())
                }
            }
        }

    private fun Connection.loadPlans(taskId: TaskId): List<Plan> =
        prepareStatement(
            """
            SELECT id, task_id, revision, direction, title, summary, timeline_json, estimated_cost_json,
                   commute_minutes, tradeoffs_json, reasons_json, valid_until, created_at
            FROM plans
            WHERE task_id = ?
            ORDER BY revision ASC, created_at ASC, id ASC
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, taskId.value)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(result.plan())
                }
            }
        }

    private fun Connection.findPlan(
        taskId: TaskId,
        planId: PlanId,
    ): Plan? =
        prepareStatement(
            """
            SELECT id, task_id, revision, direction, title, summary, timeline_json, estimated_cost_json,
                   commute_minutes, tradeoffs_json, reasons_json, valid_until, created_at
            FROM plans
            WHERE task_id = ? AND id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, taskId.value)
            statement.setObject(2, planId.value)
            statement.executeQuery().use { result -> if (result.next()) result.plan() else null }
        }

    private fun ResultSet.task(): Task =
        Task(
            id = TaskId(getObject("id", UUID::class.java)),
            owner = TaskOwner(TenantId(getObject("tenant_id", UUID::class.java)), UserId(getObject("owner_user_id", UUID::class.java))),
            creationRequestId = getString("creation_request_id"),
            intent = getString("intent"),
            revision = getLong("revision"),
            selectedPlanId = getObject("selected_plan_id", UUID::class.java)?.let(::PlanId),
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
            archivedAt = getTimestamp("archived_at")?.toInstant(),
        )

    private fun ResultSet.message(): TaskMessage =
        TaskMessage(
            id = MessageId(getObject("id", UUID::class.java)),
            taskId = TaskId(getObject("task_id", UUID::class.java)),
            role = MessageRole.valueOf(getString("role")),
            content = getString("content"),
            clientMessageId = getString("client_message_id"),
            aiRequestId = getString("ai_request_id"),
            understoodAt = getTimestamp("understood_at")?.toInstant(),
            createdAt = getTimestamp("created_at").toInstant(),
        )

    private fun ResultSet.requirement(): Requirement =
        Requirement(
            id = RequirementId(getObject("id", UUID::class.java)),
            taskId = TaskId(getObject("task_id", UUID::class.java)),
            kind = RequirementKind.valueOf(getString("kind")),
            value = json.decodeFromString<RequirementValueDocument>(getString("value_json")).toDomain(),
            strength = RequirementStrength.valueOf(getString("strength")),
            source = RequirementSource.valueOf(getString("source")),
            evidence = getObject("evidence_message_id", UUID::class.java)?.let { RequirementEvidence.UserMessage(MessageId(it)) },
            createdAt = getTimestamp("created_at").toInstant(),
            updatedAt = getTimestamp("updated_at").toInstant(),
        )

    private fun ResultSet.plan(): Plan {
        val planId = PlanId(getObject("id", UUID::class.java))
        return Plan(
            id = planId,
            taskId = TaskId(getObject("task_id", UUID::class.java)),
            revision = getLong("revision"),
            direction = PlanDirection.valueOf(getString("direction")),
            title = getString("title"),
            summary = getString("summary"),
            timeline = json.decodeFromString<List<PlanTimelineItemDocument>>(getString("timeline_json")).map { it.toDomain() },
            estimatedCost = getString("estimated_cost_json")?.let { json.decodeFromString<PlanEstimatedCostDocument>(it).toDomain() },
            commuteMinutes = getObject("commute_minutes") as? Int,
            requirementEvaluations = loadRequirementEvaluations(planId),
            tradeoffs = json.decodeFromString(getString("tradeoffs_json")),
            reasons = json.decodeFromString(getString("reasons_json")),
            sourceRefs = loadSourceRefs(planId),
            opportunityRefs = loadOpportunityRefs(planId),
            validUntil = getTimestamp("valid_until")?.toInstant(),
            createdAt = getTimestamp("created_at").toInstant(),
        )
    }

    private fun ResultSet.loadRequirementEvaluations(planId: PlanId): List<RequirementEvaluation> =
        statement.connection.prepareStatement(
            """
            SELECT requirement_id, result, explanation
            FROM plan_requirement_evaluations
            WHERE plan_id = ?
            ORDER BY requirement_id ASC
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, planId.value)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        add(
                            RequirementEvaluation(
                                requirementId = RequirementId(result.getObject("requirement_id", UUID::class.java)),
                                result = RequirementEvaluationResult.valueOf(result.getString("result")),
                                explanation = result.getString("explanation"),
                            ),
                        )
                    }
                }
            }
        }

    private fun ResultSet.loadOpportunityRefs(planId: PlanId): List<OpportunityId> =
        statement.connection.prepareStatement(
            """
            SELECT opportunity_snapshot_id
            FROM plan_opportunities
            WHERE plan_id = ?
            ORDER BY opportunity_snapshot_id ASC
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, planId.value)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(OpportunityId(result.getObject("opportunity_snapshot_id", UUID::class.java)))
                }
            }
        }

    private fun ResultSet.loadSourceRefs(planId: PlanId): List<PlanSourceRef> =
        statement.connection.prepareStatement(
            """
            SELECT DISTINCT snapshot.sources_json
            FROM plan_opportunities link
            JOIN opportunity_snapshots snapshot ON snapshot.id = link.opportunity_snapshot_id
            WHERE link.plan_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, planId.value)
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) {
                        addAll(json.decodeFromString<List<SourceRefDocument>>(result.getString("sources_json")).map { it.toDomain() })
                    }
                }.distinct()
            }
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

    private fun java.sql.PreparedStatement.setNullableJson(
        index: Int,
        value: String?,
    ) {
        if (value == null) {
            setNull(index, java.sql.Types.OTHER)
        } else {
            setJson(index, value)
        }
    }

    @Serializable
    private data class AiUnderstandingAuditDocument(
        @SerialName("capability")
        val capability: String,
        @SerialName("taskRevision")
        val taskRevision: Long,
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
        @SerialName("usage")
        val usage: AiModelTokenUsageDocument? = null,
        @SerialName("diagnostics")
        val diagnostics: AiInvocationDiagnosticsDocument = AiInvocationDiagnosticsDocument(),
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
                    taskRevision = command.taskRevision,
                    provider = command.provider,
                    model = command.model,
                    promptVersion = command.promptVersion,
                    providerRequestId = command.providerRequestId,
                    attemptCount = command.attemptCount,
                    usage = command.usage?.let { usage ->
                        AiModelTokenUsageDocument(
                            inputTokens = usage.inputTokens,
                            outputTokens = usage.outputTokens,
                            totalTokens = usage.totalTokens,
                        )
                    },
                    diagnostics = AiInvocationDiagnosticsDocument.from(command.diagnostics),
                    outcome = command.outcome,
                    latencyMs = command.latencyMs,
                    failureCategory = command.failureCategory,
                )
        }
    }

    @Serializable
    private data class AiModelTokenUsageDocument(
        @SerialName("inputTokens")
        val inputTokens: Int? = null,
        @SerialName("outputTokens")
        val outputTokens: Int? = null,
        @SerialName("totalTokens")
        val totalTokens: Int? = null,
    )

    @Serializable
    private data class AiInvocationDiagnosticsDocument(
        @SerialName("availableContextDefinitionCount")
        val availableContextDefinitionCount: Int = 0,
        @SerialName("selectedContextKeyCount")
        val selectedContextKeyCount: Int = 0,
        @SerialName("resolvedContextBlockCount")
        val resolvedContextBlockCount: Int = 0,
        @SerialName("includedContextBlockCount")
        val includedContextBlockCount: Int = 0,
        @SerialName("omittedContextBlockCount")
        val omittedContextBlockCount: Int = 0,
        @SerialName("optionalContextSerializedChars")
        val optionalContextSerializedChars: Int = 0,
        @SerialName("contextDefinitionsSerializedChars")
        val contextDefinitionsSerializedChars: Int = 0,
        @SerialName("fullUserPayloadSerializedChars")
        val fullUserPayloadSerializedChars: Int = 0,
    ) {
        companion object {
            fun from(diagnostics: AiInvocationDiagnostics): AiInvocationDiagnosticsDocument =
                AiInvocationDiagnosticsDocument(
                    availableContextDefinitionCount = diagnostics.availableContextDefinitionCount,
                    selectedContextKeyCount = diagnostics.selectedContextKeyCount,
                    resolvedContextBlockCount = diagnostics.resolvedContextBlockCount,
                    includedContextBlockCount = diagnostics.includedContextBlockCount,
                    omittedContextBlockCount = diagnostics.omittedContextBlockCount,
                    optionalContextSerializedChars = diagnostics.optionalContextSerializedChars,
                    contextDefinitionsSerializedChars = diagnostics.contextDefinitionsSerializedChars,
                    fullUserPayloadSerializedChars = diagnostics.fullUserPayloadSerializedChars,
                )
        }
    }

    @Serializable
    private data class PlanningAuditDocument(
        @SerialName("revision")
        val revision: Long,
        @SerialName("planIds")
        val planIds: List<String>,
        @SerialName("opportunityIds")
        val opportunityIds: List<String>,
    ) {
        companion object {
            fun from(command: PersistPlansCommand): PlanningAuditDocument =
                PlanningAuditDocument(
                    revision = command.expectedTaskRevision,
                    planIds = command.plans.map { it.id.value.toString() },
                    opportunityIds = command.opportunities.map { it.id.value.toString() },
                )
        }
    }

    @Serializable
    private data class PlanSelectedAuditDocument(
        @SerialName("revision")
        val revision: Long,
        @SerialName("planId")
        val planId: String,
    ) {
        companion object {
            fun from(plan: Plan): PlanSelectedAuditDocument =
                PlanSelectedAuditDocument(
                    revision = plan.revision,
                    planId = plan.id.value.toString(),
                )
        }
    }

    @Serializable
    private sealed class RequirementValueDocument {
        abstract fun toDomain(): RequirementValue

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
        ) : RequirementValueDocument() {
            override fun toDomain(): RequirementValue =
                RequirementValue.TimeWindow(startAt?.let(Instant::parse), endAt?.let(Instant::parse), timeZoneId, originalText)
        }

        @Serializable
        @SerialName("budget_limit")
        data class BudgetLimit(
            @SerialName("wholeUnits")
            val wholeUnits: Long,
            @SerialName("currencyCode")
            val currencyCode: String? = null,
        ) : RequirementValueDocument() {
            override fun toDomain(): RequirementValue = RequirementValue.BudgetLimit(wholeUnits, currencyCode)
        }

        @Serializable
        @SerialName("commute_limit")
        data class CommuteLimit(
            @SerialName("maxMinutes")
            val maxMinutes: Int,
        ) : RequirementValueDocument() {
            override fun toDomain(): RequirementValue = RequirementValue.CommuteLimit(maxMinutes)
        }

        @Serializable
        @SerialName("commute_preference")
        data class CommutePreference(
            @SerialName("value")
            val value: CommutePreferenceValue,
        ) : RequirementValueDocument() {
            override fun toDomain(): RequirementValue = RequirementValue.CommutePreference(value)
        }

        @Serializable
        @SerialName("location")
        data class Location(
            @SerialName("text")
            val text: String,
        ) : RequirementValueDocument() {
            override fun toDomain(): RequirementValue = RequirementValue.Location(text)
        }

        @Serializable
        @SerialName("activity_domain")
        data class ActivityDomain(
            @SerialName("value")
            val value: String,
        ) : RequirementValueDocument() {
            override fun toDomain(): RequirementValue = RequirementValue.ActivityDomain(value)
        }

        @Serializable
        @SerialName("activity_mode")
        data class ActivityMode(
            @SerialName("value")
            val value: ActivityModeValue,
        ) : RequirementValueDocument() {
            override fun toDomain(): RequirementValue = RequirementValue.ActivityMode(value)
        }

        @Serializable
        @SerialName("topic")
        data class Topic(
            @SerialName("text")
            val text: String,
        ) : RequirementValueDocument() {
            override fun toDomain(): RequirementValue = RequirementValue.Topic(text)
        }

        @Serializable
        @SerialName("experience_preference")
        data class ExperiencePreference(
            @SerialName("text")
            val text: String,
        ) : RequirementValueDocument() {
            override fun toDomain(): RequirementValue = RequirementValue.ExperiencePreference(text)
        }

        companion object {
            fun from(value: RequirementValue): RequirementValueDocument =
                when (value) {
                    is RequirementValue.TimeWindow ->
                        TimeWindow(value.startAt?.toString(), value.endAt?.toString(), value.timeZoneId, value.originalText)
                    is RequirementValue.BudgetLimit -> BudgetLimit(value.wholeUnits, value.currencyCode)
                    is RequirementValue.CommuteLimit -> CommuteLimit(value.maxMinutes)
                    is RequirementValue.CommutePreference -> CommutePreference(value.value)
                    is RequirementValue.Location -> Location(value.text)
                    is RequirementValue.ActivityDomain -> ActivityDomain(value.value)
                    is RequirementValue.ActivityMode -> ActivityMode(value.value)
                    is RequirementValue.Topic -> Topic(value.text)
                    is RequirementValue.ExperiencePreference -> ExperiencePreference(value.text)
                }
        }
    }

    @Serializable
    private data class OpportunityFactsDocument(
        @SerialName("summary")
        val summary: String? = null,
        @SerialName("startTime")
        val startTime: String? = null,
        @SerialName("endTime")
        val endTime: String? = null,
        @SerialName("location")
        val location: LocationFactDocument? = null,
        @SerialName("activityMode")
        val activityMode: ActivityModeValue? = null,
        @SerialName("price")
        val price: MoneyFactDocument? = null,
        @SerialName("commute")
        val commute: DurationFactDocument? = null,
        @SerialName("availability")
        val availability: AvailabilityFact? = null,
        @SerialName("attributes")
        val attributes: Map<String, String> = emptyMap(),
    ) {
        companion object {
            fun from(facts: OpportunityFacts): OpportunityFactsDocument =
                OpportunityFactsDocument(
                    summary = facts.summary,
                    startTime = facts.startTime?.toString(),
                    endTime = facts.endTime?.toString(),
                    location = facts.location?.let { LocationFactDocument(it.displayName, it.normalizedName) },
                    activityMode = facts.activityMode,
                    price = facts.price?.let { MoneyFactDocument(it.wholeUnits, it.currencyCode) },
                    commute = facts.commute?.let { DurationFactDocument(it.minutes) },
                    availability = facts.availability,
                    attributes = facts.attributes.mapValues { (_, value) -> value.toDocumentValue() },
                )
        }
    }

    @Serializable
    private data class LocationFactDocument(val displayName: String, val normalizedName: String)

    @Serializable
    private data class MoneyFactDocument(val wholeUnits: Long, val currencyCode: String?)

    @Serializable
    private data class DurationFactDocument(val minutes: Int)

    @Serializable
    private data class SourceRefDocument(
        @SerialName("label")
        val label: String,
        @SerialName("uri")
        val uri: String?,
        @SerialName("sourceUpdatedAt")
        val sourceUpdatedAt: String? = null,
    ) {
        fun toDomain(): PlanSourceRef = PlanSourceRef(label, uri, sourceUpdatedAt?.let(Instant::parse))

        companion object {
            fun from(sourceRef: SourceRef): SourceRefDocument =
                SourceRefDocument(sourceRef.label, sourceRef.uri, sourceRef.sourceUpdatedAt?.toString())
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

    private fun AiUnderstandingAuditEventType.auditEventName(): String =
        when (this) {
            AiUnderstandingAuditEventType.Started -> "AiUnderstandingStarted"
            AiUnderstandingAuditEventType.Succeeded -> "AiUnderstandingSucceeded"
            AiUnderstandingAuditEventType.Failed -> "AiUnderstandingFailed"
        }

    private companion object {
        const val INITIAL_TASK_REVISION = 1L
    }
}

private class StaleRevisionWriteException : RuntimeException()

private fun FactValue.toDocumentValue(): String =
    when (this) {
        is FactValue.Flag -> value.toString()
        is FactValue.Number -> value.toString()
        is FactValue.Text -> value
    }
