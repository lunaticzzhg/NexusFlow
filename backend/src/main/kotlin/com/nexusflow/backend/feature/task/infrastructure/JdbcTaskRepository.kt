package com.nexusflow.backend.feature.task.infrastructure

import com.nexusflow.backend.feature.task.domain.ActivityModeValue
import com.nexusflow.backend.feature.task.domain.ApplyConversationUnderstandingCommand
import com.nexusflow.backend.feature.task.domain.ApplyUnderstandingResult
import com.nexusflow.backend.feature.task.domain.AvailabilityFact
import com.nexusflow.backend.feature.task.domain.CommutePreferenceValue
import com.nexusflow.backend.feature.task.domain.ConsumePlanningResultCommand
import com.nexusflow.backend.feature.task.domain.ConsumePlanningUnderstandingCommand
import com.nexusflow.backend.feature.task.domain.CreateLinkedTaskPersistenceCommand
import com.nexusflow.backend.feature.task.domain.CreateLinkedTaskPersistenceResult
import com.nexusflow.backend.feature.task.domain.DeleteRequirementCommand
import com.nexusflow.backend.feature.task.domain.DurationFact
import com.nexusflow.backend.feature.task.domain.FactValue
import com.nexusflow.backend.feature.task.domain.LocationFact
import com.nexusflow.backend.feature.conversation.domain.MessageId
import com.nexusflow.backend.feature.conversation.domain.MessageRole
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
import com.nexusflow.backend.feature.task.domain.PlanningResultCommitter
import com.nexusflow.backend.feature.task.domain.PlanSourceRef
import com.nexusflow.backend.feature.task.domain.PlanTimelineItem
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
import com.nexusflow.backend.feature.conversation.domain.ConversationId
import com.nexusflow.backend.feature.conversation.domain.Conversation
import com.nexusflow.backend.feature.conversation.domain.ConversationDetail
import com.nexusflow.backend.feature.conversation.domain.ConversationMessage
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
) : TaskRepository, PlanningResultCommitter {
    override suspend fun createLinkedTask(command: CreateLinkedTaskPersistenceCommand): CreateLinkedTaskPersistenceResult =
        blocking {
            inTransaction { connection ->
                connection.findTaskByConversation(command.owner, command.conversationId)?.let { existing ->
                    return@inTransaction CreateLinkedTaskPersistenceResult.Existing(
                        connection.loadTaskDetail(command.owner, existing.id)!!,
                    )
                }
                connection.findTaskByCreationRequest(command.owner, command.creationRequestId)?.let { existing ->
                    return@inTransaction if (existing.intent == command.intent.trim()) {
                        CreateLinkedTaskPersistenceResult.Existing(connection.loadTaskDetail(command.owner, existing.id)!!)
                    } else {
                        CreateLinkedTaskPersistenceResult.ConflictingRequest
                    }
                }

                val task = Task(
                    id = command.taskId,
                    owner = command.owner,
                    creationRequestId = command.creationRequestId,
                    intent = command.intent.trim(),
                    revision = INITIAL_TASK_REVISION,
                    selectedPlanId = null,
                    createdAt = command.now,
                    updatedAt = command.now,
                    archivedAt = null,
                    conversationId = command.conversationId,
                )
                connection.insertTask(task, command.conversationId)
                connection.insertAuditEvent(command.taskId, "TaskCreated", command.creationRequestId, null, "{}", command.now)
                CreateLinkedTaskPersistenceResult.Created(connection.loadTaskDetail(command.owner, command.taskId)!!)
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

    override suspend fun findCurrentTaskForConversation(
        owner: TaskOwner,
        conversationId: ConversationId,
    ): TaskDetail? =
        blocking {
            dataSource.connection.use { connection ->
                connection.findTaskByConversation(owner, conversationId)?.let { task ->
                    connection.loadTaskDetail(owner, task.id)
                }
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

    override suspend fun applyConversationUnderstanding(command: ApplyConversationUnderstandingCommand): ApplyUnderstandingResult =
        blocking {
            inTransaction { connection ->
                val task = connection.lockTask(command.owner, command.taskId) ?: return@inTransaction ApplyUnderstandingResult.TaskNotFound
                if (task.revision != command.expectedTaskRevision) {
                    return@inTransaction ApplyUnderstandingResult.StaleTaskRevision
                }

                val removedRequirementCount = command.removedRequirementKinds.distinct().sumOf { kind ->
                    val deleted = connection.deleteRequirementByKind(command.taskId, kind)
                    if (deleted > 0) {
                        connection.insertAuditEvent(
                            command.taskId,
                            "RequirementRemoved",
                            null,
                            command.aiRequestId,
                            """{"kind":"${kind.name}"}""",
                            command.now,
                        )
                    }
                    deleted
                }
                command.requirements.forEach { requirement ->
                    connection.upsertRequirementFromConversation(command.taskId, command.conversationMessageId, requirement, command.now)
                    connection.insertAuditEvent(
                        command.taskId,
                        "RequirementConfirmed",
                        null,
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
                val intentPatch = command.intentPatch?.trim()?.takeIf(String::isNotBlank)
                val intentChanged = intentPatch != null && intentPatch != task.intent
                val changedPlanningInputs =
                    intentChanged || command.requirements.isNotEmpty() || removedRequirementCount > 0 || newSelectionCount > 0
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

    override suspend fun consumePlanningUnderstanding(command: ConsumePlanningUnderstandingCommand): ConsumeResponseRunResult =
        blocking {
            inTransaction { connection ->
                connection.consumePlanningUnderstandingAtomically(command)
            }
        }

    override suspend fun consumePlanningResult(command: ConsumePlanningResultCommand): ConsumeResponseRunResult =
        blocking {
            inTransaction { connection ->
                connection.consumePlanningResultAtomically(command)
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

    private fun Connection.consumePlanningUnderstandingAtomically(
        command: ConsumePlanningUnderstandingCommand,
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
        if (run.stage != ResponseRunStage.Turn) {
            return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.PlanningPreconditionMismatch)
        }
        val payload = command.payload
        if (payload.conversationId != run.conversationId.value.toString()) {
            return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.PayloadConversationMismatch)
        }
        if (payload.userMessageId != run.userMessageId.value.toString()) {
            return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.PayloadUserMessageMismatch)
        }
        val conversation = findConversationById(run.conversationId)
            ?: error("conversation missing after failed planning run consumption")
        val userMessage = findConversationMessage(run.conversationId, run.userMessageId)
            ?: return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.UserMessageMissing)
        if (userMessage.role != MessageRole.User) {
            return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.UserMessageRoleMismatch)
        }
        if (userMessage.aiRequestId != payload.aiRequestId) {
            return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.AiRequestMismatch)
        }
        val task = resolvePlanningTaskForUnderstanding(
            owner = conversation.owner,
            conversationId = run.conversationId,
            payload = payload,
            newTaskId = command.newTaskId,
            now = command.now,
        ) ?: return failPlanningRunFromResult(
            run = run,
            result = result,
            now = command.now,
            failureCategory = ResponseRunFailureCategory.AiInvalidResult,
        )
        val expectedRevision = payload.expectedTaskRevision ?: task.revision
        val lockedTask = lockTask(conversation.owner, task.id) ?: return failPlanningRunFromResult(
            run = run,
            result = result,
            now = command.now,
            failureCategory = ResponseRunFailureCategory.AiInvalidResult,
        )
        if (lockedTask.revision != expectedRevision) {
            return failPlanningRunFromResult(
                run = run,
                result = result,
                now = command.now,
                failureCategory = ResponseRunFailureCategory.AiInvalidResult,
            )
        }

        val removedRequirementCount = command.removedRequirementKinds.distinct().sumOf { kind ->
            val deleted = deleteRequirementByKind(lockedTask.id, kind)
            if (deleted > 0) {
                insertAuditEvent(
                    lockedTask.id,
                    "RequirementRemoved",
                    null,
                    payload.aiRequestId,
                    """{"kind":"${kind.name}"}""",
                    command.now,
                )
            }
            deleted
        }
        command.requirements.forEach { requirement ->
            upsertRequirementFromConversation(lockedTask.id, userMessage.id, requirement, command.now)
            insertAuditEvent(
                lockedTask.id,
                "RequirementConfirmed",
                null,
                payload.aiRequestId,
                """{"kind":"${requirement.kind.name}"}""",
                command.now,
            )
        }
        val newSelectionCount = insertTaskContextSelections(
            taskId = lockedTask.id,
            contextKeys = payload.selectedTaskContextKeys,
            selectedAt = command.now,
        )
        val intentPatch = payload.intentPatch?.trim()?.takeIf(String::isNotBlank)
        val intentChanged = intentPatch != null && intentPatch != lockedTask.intent
        val changedPlanningInputs =
            intentChanged || command.requirements.isNotEmpty() || removedRequirementCount > 0 || newSelectionCount > 0
        if (changedPlanningInputs) {
            updateTaskAfterPlanningInputChange(
                taskId = lockedTask.id,
                intent = intentPatch ?: lockedTask.intent,
                revision = lockedTask.revision + 1,
                now = command.now,
            )
        } else {
            touchTask(lockedTask.id, command.now)
        }
        val applied = loadTaskDetail(conversation.owner, lockedTask.id)
            ?: error("task detail missing after planning understanding durable mutation")
        if (!preparePlanningStageFromResult(run.id, run.attempt, applied.task.id, applied.task.revision, command.now)) {
            error("response run was not queueable after planning understanding precondition passed")
        }
        markMessageUnderstood(run.userMessageId, payload.aiRequestId, command.now)
        markResponseRunResultConsumed(result.runId, result.attempt, command.now)
        return ConsumeResponseRunResult.Consumed(
            loadConversationDetail(conversation.owner, run.conversationId)
                ?: error("conversation detail missing after planning understanding consumption"),
        )
    }

    private fun Connection.consumePlanningResultAtomically(command: ConsumePlanningResultCommand): ConsumeResponseRunResult {
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
        if (run.stage != ResponseRunStage.Planning) {
            return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.PlanningPreconditionMismatch)
        }
        val payload = command.payload
        if (run.expectedTaskId?.value?.toString() != payload.taskId) {
            return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.PlanningPreconditionMismatch)
        }
        if (run.expectedTaskRevision != payload.expectedTaskRevision) {
            return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.TaskRevisionMismatch)
        }
        if (payload.conversationId != run.conversationId.value.toString()) {
            return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.PayloadConversationMismatch)
        }
        if (payload.userMessageId != run.userMessageId.value.toString()) {
            return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.PayloadUserMessageMismatch)
        }
        val conversation = findConversationById(run.conversationId)
            ?: return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.ConversationMissing)
        if (payload.outcome == "Ready") {
            val taskId = TaskId(UUID.fromString(payload.taskId))
            val task = lockTask(conversation.owner, taskId)
            if (task == null || task.revision != payload.expectedTaskRevision) {
                completePlanningRunFromResult(run.id, run.attempt, command.now, null)
                markResponseRunResultConsumed(result.runId, result.attempt, command.now)
                return ConsumeResponseRunResult.Consumed(
                    loadConversationDetail(conversation.owner, run.conversationId)
                        ?: error("conversation detail missing after stale planning result completion"),
                )
            }
            persistPlansInCurrentTransaction(
                owner = conversation.owner,
                taskId = taskId,
                expectedTaskRevision = payload.expectedTaskRevision,
                opportunities = command.opportunities,
                plans = command.plans,
                now = command.now,
            )
        }
        if (!completePlanningRunFromResult(run.id, run.attempt, command.now, command.failureCategory)) {
            error("response run was not completable after planning result precondition passed")
        }
        markResponseRunResultConsumed(result.runId, result.attempt, command.now)
        return ConsumeResponseRunResult.Consumed(
            loadConversationDetail(conversation.owner, run.conversationId)
                ?: error("conversation detail missing after planning result consumption"),
        )
    }

    private fun Connection.resolvePlanningTaskForUnderstanding(
        owner: TaskOwner,
        conversationId: ConversationId,
        payload: ResponseRunResultPayload.PlanningUnderstanding,
        newTaskId: TaskId,
        now: Instant,
    ): Task? {
        payload.taskId?.let { taskId ->
            return lockTask(owner, TaskId(UUID.fromString(taskId)))
        }
        findTaskByConversation(owner, conversationId)?.let { return it }
        findTaskByCreationRequest(owner, payload.taskCreationRequestId)?.let { existing ->
            return if (existing.intent == payload.intent.trim()) existing else null
        }
        val task = Task(
            id = newTaskId,
            owner = owner,
            creationRequestId = payload.taskCreationRequestId,
            intent = payload.intent.trim(),
            revision = INITIAL_TASK_REVISION,
            selectedPlanId = null,
            createdAt = now,
            updatedAt = now,
            archivedAt = null,
            conversationId = conversationId,
        )
        insertTask(task, conversationId)
        insertAuditEvent(newTaskId, "TaskCreated", payload.taskCreationRequestId, null, "{}", now)
        return task
    }

    private fun Connection.persistPlansInCurrentTransaction(
        owner: TaskOwner,
        taskId: TaskId,
        expectedTaskRevision: Long,
        opportunities: List<Opportunity>,
        plans: List<Plan>,
        now: Instant,
    ) {
        val task = lockTask(owner, taskId) ?: error("Task disappeared while persisting planning response")
        if (task.revision != expectedTaskRevision) throw StaleRevisionWriteException()
        if (plans.any { it.taskId != taskId || it.revision != expectedTaskRevision }) {
            error("Plans must belong to the task and current revision")
        }
        if (plans.isEmpty() || plans.any { it.validUntil == null }) {
            error("Planning result must contain current plans with validUntil")
        }
        val opportunityIds = opportunities.mapTo(mutableSetOf()) { it.id }
        if (plans.flatMap { it.opportunityRefs }.any { it !in opportunityIds }) {
            error("Plans must reference persisted opportunity snapshots")
        }

        opportunities.forEach { upsertOpportunity(it) }
        plans.forEach { plan ->
            insertPlan(plan)
            replacePlanOpportunityRefs(plan)
            replacePlanRequirementEvaluations(plan)
        }
        clearSelectedPlan(taskId, now)
        insertAuditEvent(
            taskId,
            "PlansCreated",
            null,
            null,
            json.encodeToString(
                PlanningAuditDocument(
                    revision = expectedTaskRevision,
                    planIds = plans.map { it.id.value.toString() },
                    opportunityIds = opportunities.map { it.id.value.toString() },
                ),
            ),
            now,
        )
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

    private fun Connection.insertTask(
        task: Task,
        conversationId: ConversationId,
    ) {
        prepareStatement(
            """
            INSERT INTO tasks (
                id, tenant_id, owner_user_id, creation_request_id, intent, revision,
                selected_plan_id, created_at, updated_at, archived_at, conversation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            statement.setObject(11, conversationId.value)
            statement.executeUpdate()
        }
    }

    private fun Connection.upsertRequirementFromConversation(
        taskId: TaskId,
        conversationMessageId: MessageId,
        requirement: RequirementWrite,
        now: Instant,
    ) {
        prepareStatement(
            """
            INSERT INTO task_requirements (
                id, task_id, kind, value_json, strength, source, conversation_evidence_message_id, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (task_id, kind) DO UPDATE SET
                value_json = EXCLUDED.value_json,
                strength = EXCLUDED.strength,
                source = EXCLUDED.source,
                conversation_evidence_message_id = EXCLUDED.conversation_evidence_message_id,
                updated_at = EXCLUDED.updated_at
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, requirement.id.value)
            statement.setObject(2, taskId.value)
            statement.setString(3, requirement.kind.name)
            statement.setJson(4, json.encodeToString(RequirementValueDocument.from(requirement.value)))
            statement.setString(5, requirement.strength.name)
            statement.setString(6, requirement.source.name)
            statement.setObject(7, conversationMessageId.value)
            statement.setInstant(8, now)
            statement.setInstant(9, now)
            statement.executeUpdate()
        }
    }

    private fun Connection.updateRequirement(command: UpdateRequirementCommand) {
        prepareStatement(
            """
            UPDATE task_requirements
            SET kind = ?, value_json = ?, strength = ?, source = ?, conversation_evidence_message_id = NULL, updated_at = ?
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

    private fun Connection.deleteRequirementByKind(
        taskId: TaskId,
        kind: RequirementKind,
    ): Int =
        prepareStatement("DELETE FROM task_requirements WHERE task_id = ? AND kind = ?").use { statement ->
            statement.setObject(1, taskId.value)
            statement.setString(2, kind.name)
            statement.executeUpdate()
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
                   selected_plan_id, created_at, updated_at, archived_at, conversation_id
            FROM tasks
            WHERE tenant_id = ? AND owner_user_id = ? AND creation_request_id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setOwner(owner)
            statement.setString(3, creationRequestId)
            statement.executeQuery().use { result -> if (result.next()) result.task() else null }
        }

    private fun Connection.findTaskByConversation(
        owner: TaskOwner,
        conversationId: ConversationId,
    ): Task? =
        prepareStatement(
            """
            SELECT id, tenant_id, owner_user_id, creation_request_id, intent, revision,
                   selected_plan_id, created_at, updated_at, archived_at, conversation_id
            FROM tasks
            WHERE tenant_id = ? AND owner_user_id = ? AND conversation_id = ? AND archived_at IS NULL
            """.trimIndent(),
        ).use { statement ->
            statement.setOwner(owner)
            statement.setObject(3, conversationId.value)
            statement.executeQuery().use { result -> if (result.next()) result.task() else null }
        }

    private fun Connection.lockTask(
        owner: TaskOwner,
        taskId: TaskId,
    ): Task? =
        prepareStatement(
            """
            SELECT id, tenant_id, owner_user_id, creation_request_id, intent, revision,
                   selected_plan_id, created_at, updated_at, archived_at, conversation_id
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
            requirements = loadRequirements(taskId),
            plans = loadPlans(taskId),
            selectedContextKeys = loadTaskContextKeys(taskId),
        )
    }

    private fun Connection.loadConversationDetail(
        owner: TaskOwner,
        conversationId: ConversationId,
    ): ConversationDetail? {
        val conversation = findConversation(owner, conversationId) ?: return null
        return ConversationDetail(
            conversation = conversation,
            messages = loadConversationMessages(conversationId),
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

    private fun Connection.findConversationMessage(
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
            statement.executeQuery().use { result -> if (result.next()) result.conversationMessage() else null }
        }

    private fun Connection.loadConversationMessages(conversationId: ConversationId): List<ConversationMessage> =
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
                    while (result.next()) add(result.conversationMessage())
                }
            }
        }

    private fun Connection.loadResponseRuns(conversationId: ConversationId): List<ResponseRun> =
        prepareStatement(
            """
            SELECT id, conversation_id, user_message_id, turn_index, status, stage, attempt,
                   available_at, lease_owner, lease_expires_at, deadline_at, expected_task_id,
                   expected_task_revision, assistant_message_id, failure_category, origin_trace_id, created_at,
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

    private fun Connection.lockResponseRun(responseRunId: ResponseRunId): ResponseRun? =
        prepareStatement(
            """
            SELECT id, conversation_id, user_message_id, turn_index, status, stage, attempt,
                   available_at, lease_owner, lease_expires_at, deadline_at, expected_task_id,
                   expected_task_revision, assistant_message_id, failure_category, origin_trace_id, created_at,
                   started_at, updated_at, completed_at
            FROM response_runs
            WHERE id = ?
            FOR UPDATE
            """.trimIndent(),
        ).use { statement ->
            statement.setObject(1, responseRunId.value)
            statement.executeQuery().use { result -> if (result.next()) result.responseRun() else null }
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
            statement.executeQuery().use { result -> if (result.next()) result.responseRunResult() else null }
        }

    private fun Connection.failPlanningRunFromResult(
        run: ResponseRun,
        result: ResponseRunResult,
        now: Instant,
        failureCategory: ResponseRunFailureCategory,
    ): ConsumeResponseRunResult {
        if (!completeRunFromResult(run.id, run.attempt, run.stage, now, failureCategory)) {
            error("response run was not failable after planning precondition passed")
        }
        markResponseRunResultConsumed(result.runId, result.attempt, now)
        val conversation = findConversationById(run.conversationId)
            ?: return ConsumeResponseRunResult.Ignored(ConsumeResponseRunIgnoreReason.ConversationMissing)
        return ConsumeResponseRunResult.Consumed(
            loadConversationDetail(conversation.owner, run.conversationId)
                ?: error("conversation detail missing after failed planning run consumption"),
        )
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
        completeRunFromResult(
            runId = runId,
            attempt = attempt,
            expectedStage = ResponseRunStage.Planning,
            now = now,
            failureCategory = failureCategory,
        )

    private fun Connection.completeRunFromResult(
        runId: ResponseRunId,
        attempt: Int,
        expectedStage: ResponseRunStage,
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
            statement.setString(9, expectedStage.toDatabaseValue())
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

    private fun Connection.findTask(
        owner: TaskOwner,
        taskId: TaskId,
    ): Task? =
        prepareStatement(
            """
            SELECT id, tenant_id, owner_user_id, creation_request_id, intent, revision,
                   selected_plan_id, created_at, updated_at, archived_at, conversation_id
            FROM tasks
            WHERE tenant_id = ? AND owner_user_id = ? AND id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setOwner(owner)
            statement.setObject(3, taskId.value)
            statement.executeQuery().use { result -> if (result.next()) result.task() else null }
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
            SELECT id, task_id, kind, value_json, strength, source,
                   conversation_evidence_message_id AS evidence_message_id,
                   created_at, updated_at
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
            conversationId = getObject("conversation_id", UUID::class.java)?.let(::ConversationId),
        )

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

    private fun ResultSet.conversationMessage(): ConversationMessage =
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
            payload = json.decodeFromString(ResponseRunResultPayload.serializer(), getString("payload")),
            createdAt = getTimestamp("created_at").toInstant(),
            consumedAt = getTimestamp("consumed_at")?.toInstant(),
        )
    }

    private fun ResultSet.getNullableLong(columnLabel: String): Long? {
        val value = getLong(columnLabel)
        return if (wasNull()) null else value
    }

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

    private fun ResponseRunFailureCategory.toDatabaseValue(): String =
        when (this) {
            ResponseRunFailureCategory.ProviderTemporary -> "PROVIDER_TEMPORARY"
            ResponseRunFailureCategory.AiInvalidResult -> "AI_INVALID_RESULT"
            ResponseRunFailureCategory.WorkerLost -> "WORKER_LOST"
            ResponseRunFailureCategory.RunTimeout -> "RUN_TIMEOUT"
            ResponseRunFailureCategory.InternalInvariant -> "INTERNAL_INVARIANT"
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
        @SerialName("sourceId")
        val sourceId: String? = null,
        @SerialName("sourceAuthority")
        val sourceAuthority: String? = null,
        @SerialName("factKeys")
        val factKeys: List<String> = emptyList(),
    ) {
        fun toDomain(): PlanSourceRef = PlanSourceRef(label, uri, sourceUpdatedAt?.let(Instant::parse))

        companion object {
            fun from(sourceRef: SourceRef): SourceRefDocument =
                SourceRefDocument(
                    label = sourceRef.label,
                    uri = sourceRef.uri,
                    sourceUpdatedAt = sourceRef.sourceUpdatedAt?.toString(),
                    sourceId = sourceRef.sourceId,
                    sourceAuthority = sourceRef.authority.name,
                    factKeys = sourceRef.factKeys.map { it.name }.sorted(),
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
