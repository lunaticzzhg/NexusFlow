package com.nexusflow.backend.feature.task.application

import com.nexusflow.ai.understanding.ConfirmedConstraint as AiConfirmedConstraint
import com.nexusflow.ai.understanding.ConstraintCandidate as AiConstraintCandidate
import com.nexusflow.ai.understanding.ConstraintKind as AiConstraintKind
import com.nexusflow.ai.understanding.ConstraintStrength as AiConstraintStrength
import com.nexusflow.ai.understanding.ConstraintValue as AiConstraintValue
import com.nexusflow.ai.understanding.UnderstandingContext as AiUnderstandingContext
import com.nexusflow.ai.understanding.UserMessageUnderstanding
import com.nexusflow.ai.understanding.UserMessageUnderstandingException
import com.nexusflow.backend.core.identity.ActorContext
import com.nexusflow.backend.feature.task.domain.AppendUserMessageCommand
import com.nexusflow.backend.feature.task.domain.AppendUserMessageResult
import com.nexusflow.backend.feature.task.domain.ApplyUnderstandingCommand
import com.nexusflow.backend.feature.task.domain.ApplyUnderstandingResult
import com.nexusflow.backend.feature.task.domain.AssistantMessageWrite
import com.nexusflow.backend.feature.task.domain.AiUnderstandingAuditEventType
import com.nexusflow.backend.feature.task.domain.ConfirmedConstraintWrite
import com.nexusflow.backend.feature.task.domain.ConstraintId
import com.nexusflow.backend.feature.task.domain.ConstraintKind
import com.nexusflow.backend.feature.task.domain.ConstraintStrength
import com.nexusflow.backend.feature.task.domain.ConstraintValue
import com.nexusflow.backend.feature.task.domain.ConversationId
import com.nexusflow.backend.feature.task.domain.ConversationMessage
import com.nexusflow.backend.feature.task.domain.CreateFixturePlanningRunCommand
import com.nexusflow.backend.feature.task.domain.CreateFixturePlanningRunResult
import com.nexusflow.backend.feature.task.domain.CreateTaskPersistenceCommand
import com.nexusflow.backend.feature.task.domain.CreateTaskPersistenceResult
import com.nexusflow.backend.feature.task.domain.MessageId
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
import com.nexusflow.backend.feature.task.domain.canTransitionTo
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant as ContractInstant
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.ZoneId
import java.util.UUID

class TaskService(
    private val repository: TaskRepository,
    private val understanding: UserMessageUnderstanding? = null,
    private val fixturePlanningEnabled: Boolean = false,
    private val logUnderstandingFailure: (TaskUnderstandingFailureEvent) -> Unit = {},
    private val clock: Clock = Clock.systemUTC(),
    private val uuidFactory: () -> UUID = UUID::randomUUID,
) {
    suspend fun createTask(
        actor: ActorContext,
        clientRequestId: String,
        goal: String,
    ): TaskDetail {
        actor.requireScope(WRITE_SCOPE)
        val owner = actor.taskOwner()
        val requestId = clientRequestId.requireBounded("clientRequestId", MAX_ID_LENGTH)
        val trimmedGoal = goal.requireBounded("goal", MAX_GOAL_LENGTH)

        return when (
            val result = repository.createTaskWithConversation(
                CreateTaskPersistenceCommand(
                    owner = owner,
                    taskId = TaskId(uuidFactory()),
                    conversationId = ConversationId(uuidFactory()),
                    creationRequestId = requestId,
                    goal = trimmedGoal,
                    now = clock.instant(),
                ),
            )
        ) {
            is CreateTaskPersistenceResult.Created -> result.detail
            is CreateTaskPersistenceResult.Existing -> result.detail
            CreateTaskPersistenceResult.ConflictingRequest -> throw TaskConflictException()
        }
    }

    suspend fun listTasks(actor: ActorContext): List<Task> {
        actor.requireScope(READ_SCOPE)
        return repository.listTasks(actor.taskOwner())
    }

    suspend fun getTask(
        actor: ActorContext,
        taskId: String,
    ): TaskDetail {
        actor.requireScope(READ_SCOPE)
        return repository.findTaskDetail(actor.taskOwner(), taskId.toTaskId()) ?: throw TaskNotFoundException()
    }

    suspend fun sendMessage(
        actor: ActorContext,
        taskId: String,
        clientMessageId: String,
        text: String,
        timeZoneId: String,
    ): TaskDetail {
        actor.requireScope(WRITE_SCOPE)
        val owner = actor.taskOwner()
        val parsedTaskId = taskId.toTaskId()
        val parsedClientMessageId = clientMessageId.requireBounded("clientMessageId", MAX_ID_LENGTH)
        val trimmedText = text.requireBounded("text", MAX_MESSAGE_LENGTH)
        val normalizedTimeZoneId = timeZoneId.requireBounded("timeZoneId", MAX_TIME_ZONE_LENGTH).requireValidTimeZoneId()
        val aiRequestId = "understand-${uuidFactory()}"

        val pending = when (
            val result = repository.appendUserMessage(
                AppendUserMessageCommand(
                    owner = owner,
                    taskId = parsedTaskId,
                    messageId = MessageId(uuidFactory()),
                    clientMessageId = parsedClientMessageId,
                    text = trimmedText,
                    aiRequestId = aiRequestId,
                    now = clock.instant(),
                ),
            )
        ) {
            is AppendUserMessageResult.Appended -> PendingUnderstanding(result.detail, result.message, result.taskVersion)
            is AppendUserMessageResult.Existing -> {
                if (result.message.understoodAt != null) return result.detail
                PendingUnderstanding(result.detail, result.message, result.taskVersion)
            }
            AppendUserMessageResult.ConflictingMessage -> throw TaskConflictException()
            AppendUserMessageResult.TaskNotFound -> throw TaskNotFoundException()
        }

        val attemptStartedAt = clock.instant()
        pending.recordAiUnderstandingStarted(aiRequestId, attemptStartedAt)
        val capability = understanding
        if (capability == null) {
            pending.recordAiUnderstandingFailed(
                aiRequestId = aiRequestId,
                failureCategory = "DependencyUnavailable",
                latencyMs = attemptStartedAt.elapsedMs(),
            )
            throw TaskDependencyUnavailableException("Task understanding is temporarily unavailable")
        }
        val outcome = try {
            capability.understand(pending.toAiContext(aiRequestId = aiRequestId, timeZoneId = normalizedTimeZoneId))
        } catch (error: CancellationException) {
            throw error
        } catch (error: UserMessageUnderstandingException) {
            pending.recordAiUnderstandingFailed(
                aiRequestId = aiRequestId,
                failureCategory = error.safeFailureCategory(),
                latencyMs = attemptStartedAt.elapsedMs(),
            )
            throw error.toUnavailable(aiRequestId, pending)
        }
        val targetState = if (outcome.clarificationNeeded || outcome.missingInformation.isNotEmpty()) {
            TaskState.CollectingConstraints
        } else {
            TaskState.Planning
        }
        if (!pending.detail.task.state.canTransitionTo(targetState)) {
            throw InvalidTaskStateException()
        }
        val constraints = try {
            outcome.extractedConstraints.toConfirmedWrites(pending.message.content)
        } catch (error: TaskDependencyUnavailableException) {
            pending.recordAiUnderstandingFailed(
                aiRequestId = aiRequestId,
                failureCategory = "InvalidAiResult",
                latencyMs = attemptStartedAt.elapsedMs(),
            )
            throw error
        }
        val assistantMessage = try {
            outcome.assistantMessageWrite()
        } catch (error: TaskDependencyUnavailableException) {
            pending.recordAiUnderstandingFailed(
                aiRequestId = aiRequestId,
                failureCategory = "InvalidAiResult",
                latencyMs = attemptStartedAt.elapsedMs(),
            )
            throw error
        }
        pending.recordAiUnderstandingSucceeded(outcome.metadata, aiRequestId, attemptStartedAt.elapsedMs())

        return when (
            val result = repository.applyUnderstanding(
                ApplyUnderstandingCommand(
                    owner = owner,
                    taskId = parsedTaskId,
                    expectedTaskVersion = pending.taskVersion,
                    messageId = pending.message.id,
                    aiRequestId = aiRequestId,
                    constraints = constraints,
                    assistantMessage = assistantMessage,
                    targetState = targetState,
                    now = clock.instant(),
                ),
            )
        ) {
            is ApplyUnderstandingResult.Applied -> result.detail
            ApplyUnderstandingResult.MessageNotFound,
            ApplyUnderstandingResult.TaskNotFound,
            -> throw TaskNotFoundException()
            ApplyUnderstandingResult.StaleTaskVersion -> throw TaskConflictException()
        }
    }

    suspend fun generateFixturePlans(
        actor: ActorContext,
        taskId: String,
        clientRequestId: String,
    ): GeneratePlansResult {
        actor.requireScope(WRITE_SCOPE)
        if (!fixturePlanningEnabled) {
            throw TaskDependencyUnavailableException("Fixture planning is not available")
        }
        val owner = actor.taskOwner()
        val parsedTaskId = taskId.toTaskId()
        val requestId = clientRequestId.requireBounded("clientRequestId", MAX_ID_LENGTH)
        val detail = repository.findTaskDetail(owner, parsedTaskId) ?: throw TaskNotFoundException()
        val planningRunId = PlanningRunId(uuidFactory())
        val plan = detail.fixturePlan(planningRunId, clock.instant())

        return when (
            val result = repository.createFixturePlanningRun(
                CreateFixturePlanningRunCommand(
                    owner = owner,
                    taskId = parsedTaskId,
                    planningRunId = planningRunId,
                    clientRequestId = requestId,
                    plans = listOf(plan),
                    now = clock.instant(),
                ),
            )
        ) {
            is CreateFixturePlanningRunResult.Created -> GeneratePlansResult(
                planningRun = result.planningRun,
                plans = result.detail.plans.filter { it.planningRunId == result.planningRun.id },
            )
            is CreateFixturePlanningRunResult.Existing -> GeneratePlansResult(
                planningRun = result.planningRun,
                plans = result.detail.plans.filter { it.planningRunId == result.planningRun.id },
            )
            CreateFixturePlanningRunResult.InvalidState -> throw InvalidTaskStateException()
            CreateFixturePlanningRunResult.TaskNotFound -> throw TaskNotFoundException()
        }
    }

    suspend fun selectPlan(
        actor: ActorContext,
        taskId: String,
        planId: String,
    ): TaskDetail {
        actor.requireScope(WRITE_SCOPE)
        return when (
            val result = repository.selectPlan(
                SelectPlanCommand(
                    owner = actor.taskOwner(),
                    taskId = taskId.toTaskId(),
                    planId = planId.toPlanId(),
                    now = clock.instant(),
                ),
            )
        ) {
            is SelectPlanResult.Selected -> result.detail
            SelectPlanResult.InvalidState -> throw InvalidTaskStateException()
            SelectPlanResult.PlanNotFound,
            SelectPlanResult.TaskNotFound,
            -> throw TaskNotFoundException()
        }
    }

    private fun ActorContext.taskOwner(): TaskOwner =
        TaskOwner(
            tenantId = TenantId(tenantId.toUuid("tenantId")),
            userId = UserId(userId.toUuid("userId")),
        )

    private fun ActorContext.requireScope(scope: String) {
        if (!hasScope(scope)) throw MissingTaskScopeException()
    }

    private fun String.toTaskId(): TaskId = TaskId(toUuid("taskId"))

    private fun String.toPlanId(): PlanId = PlanId(toUuid("planId"))

    private fun String.toUuid(fieldName: String): UUID =
        try {
            UUID.fromString(this)
        } catch (_: IllegalArgumentException) {
            throw InvalidTaskRequestException("$fieldName is invalid")
        }

    private fun String.requireBounded(
        fieldName: String,
        maxLength: Int,
    ): String {
        val trimmed = trim()
        if (trimmed.isBlank() || trimmed.length > maxLength) {
            throw InvalidTaskRequestException("$fieldName is invalid")
        }
        return trimmed
    }

    private fun String.requireValidTimeZoneId(): String =
        try {
            ZoneId.of(this).id
        } catch (_: DateTimeException) {
            throw InvalidTaskRequestException("timeZoneId is invalid")
        }

    private suspend fun PendingUnderstanding.recordAiUnderstandingStarted(
        aiRequestId: String,
        now: java.time.Instant,
    ) {
        recordAiUnderstandingAudit(
            RecordAiUnderstandingAuditCommand(
                owner = detail.task.owner,
                taskId = detail.task.id,
                taskVersion = taskVersion,
                aiRequestId = aiRequestId,
                eventType = AiUnderstandingAuditEventType.Started,
                provider = null,
                model = null,
                promptVersion = null,
                providerRequestId = null,
                attemptCount = null,
                outcome = "started",
                latencyMs = null,
                failureCategory = null,
                now = now,
            ),
        )
    }

    private suspend fun PendingUnderstanding.recordAiUnderstandingSucceeded(
        metadata: com.nexusflow.ai.understanding.UnderstandingMetadata,
        aiRequestId: String,
        latencyMs: Long,
    ) {
        recordAiUnderstandingAudit(
            RecordAiUnderstandingAuditCommand(
                owner = detail.task.owner,
                taskId = detail.task.id,
                taskVersion = taskVersion,
                aiRequestId = aiRequestId,
                eventType = AiUnderstandingAuditEventType.Succeeded,
                provider = metadata.provider,
                model = metadata.model,
                promptVersion = metadata.promptVersion,
                providerRequestId = metadata.providerRequestId,
                attemptCount = metadata.attemptCount,
                outcome = "succeeded",
                latencyMs = latencyMs,
                failureCategory = null,
                now = clock.instant(),
            ),
        )
    }

    private suspend fun PendingUnderstanding.recordAiUnderstandingFailed(
        aiRequestId: String,
        failureCategory: String,
        latencyMs: Long,
    ) {
        recordAiUnderstandingAudit(
            RecordAiUnderstandingAuditCommand(
                owner = detail.task.owner,
                taskId = detail.task.id,
                taskVersion = taskVersion,
                aiRequestId = aiRequestId,
                eventType = AiUnderstandingAuditEventType.Failed,
                provider = null,
                model = null,
                promptVersion = null,
                providerRequestId = null,
                attemptCount = null,
                outcome = "failed",
                latencyMs = latencyMs,
                failureCategory = failureCategory,
                now = clock.instant(),
            ),
        )
    }

    private suspend fun recordAiUnderstandingAudit(command: RecordAiUnderstandingAuditCommand) {
        when (repository.recordAiUnderstandingAudit(command)) {
            RecordAiUnderstandingAuditResult.Recorded -> Unit
            RecordAiUnderstandingAuditResult.TaskNotFound -> throw TaskNotFoundException()
        }
    }

    private fun java.time.Instant.elapsedMs(): Long =
        Duration.between(this, clock.instant()).toMillis().coerceAtLeast(0)

    private fun UserMessageUnderstandingException.toUnavailable(
        aiRequestId: String,
        pending: PendingUnderstanding,
    ): TaskDependencyUnavailableException {
        logUnderstandingFailure(
            TaskUnderstandingFailureEvent(
                taskId = pending.detail.task.id.value.toString(),
                taskVersion = pending.taskVersion,
                aiRequestId = aiRequestId,
                failureType = this::class.simpleName ?: "UserMessageUnderstandingException",
            ),
        )
        return TaskDependencyUnavailableException("Task understanding is temporarily unavailable")
    }

    private fun UserMessageUnderstandingException.safeFailureCategory(): String =
        this::class.simpleName ?: "UserMessageUnderstandingException"

    private fun PendingUnderstanding.toAiContext(
        aiRequestId: String,
        timeZoneId: String,
    ): AiUnderstandingContext =
        AiUnderstandingContext(
            aiRequestId = aiRequestId,
            taskId = detail.task.id.value.toString(),
            taskVersion = taskVersion,
            currentGoal = detail.task.currentGoal,
            confirmedConstraints = detail.constraints.map { it.toAiConfirmedConstraint() },
            currentMessage = message.content,
            referenceTime = clock.instant().toContractInstant(),
            timeZoneId = timeZoneId,
        )

    private fun TaskConstraint.toAiConfirmedConstraint(): AiConfirmedConstraint =
        AiConfirmedConstraint(
            kind = kind.toAiConstraintKind(),
            value = value.toAiConstraintValue(),
            strength = strength.toAiConstraintStrength(),
        )

    private fun ConstraintKind.toAiConstraintKind(): AiConstraintKind =
        when (this) {
            ConstraintKind.TimeWindow -> AiConstraintKind.TimeWindow
            ConstraintKind.BudgetLimit -> AiConstraintKind.BudgetLimit
            ConstraintKind.CommuteLimit -> AiConstraintKind.CommuteLimit
            ConstraintKind.Location -> AiConstraintKind.Location
            ConstraintKind.ActivityDomain -> AiConstraintKind.ActivityDomain
            ConstraintKind.Topic -> AiConstraintKind.Topic
            ConstraintKind.ExperiencePreference -> AiConstraintKind.ExperiencePreference
        }

    private fun ConstraintValue.toAiConstraintValue(): AiConstraintValue =
        when (this) {
            is ConstraintValue.TimeWindow -> AiConstraintValue.TimeWindow(
                startAt = startAt?.toContractInstant(),
                endAt = endAt?.toContractInstant(),
                timeZoneId = timeZoneId,
                originalText = originalText,
            )
            is ConstraintValue.BudgetLimit -> AiConstraintValue.BudgetLimit(wholeUnits = wholeUnits, currencyCode = currencyCode)
            is ConstraintValue.CommuteLimit -> AiConstraintValue.CommuteLimit(maxMinutes = maxMinutes)
            is ConstraintValue.Location -> AiConstraintValue.Location(text = text)
            is ConstraintValue.ActivityDomain -> AiConstraintValue.ActivityDomain(value = value)
            is ConstraintValue.Topic -> AiConstraintValue.Topic(text = text)
            is ConstraintValue.ExperiencePreference -> AiConstraintValue.ExperiencePreference(text = text)
        }

    private fun ConstraintStrength.toAiConstraintStrength(): AiConstraintStrength =
        when (this) {
            ConstraintStrength.Hard -> AiConstraintStrength.Hard
            ConstraintStrength.Soft -> AiConstraintStrength.Soft
        }

    private fun java.time.Instant.toContractInstant(): ContractInstant =
        ContractInstant.fromEpochSeconds(epochSecond, nano.toLong())

    private fun List<AiConstraintCandidate>.toConfirmedWrites(messageText: String): List<ConfirmedConstraintWrite> =
        map { candidate ->
            candidate.validate(messageText)
            ConfirmedConstraintWrite(
                id = ConstraintId(uuidFactory()),
                kind = candidate.kind.toBackendKind(),
                value = candidate.value.toBackendValue(),
                strength = candidate.strength.toBackendStrength(),
            )
        }

    private fun AiConstraintCandidate.validate(messageText: String) {
        if (evidenceText.isBlank() || !messageText.contains(evidenceText)) {
            throw TaskDependencyUnavailableException("Task understanding is temporarily unavailable")
        }
        when (val currentValue = value) {
            is AiConstraintValue.BudgetLimit -> if (currentValue.wholeUnits <= 0) {
                throw TaskDependencyUnavailableException("Task understanding is temporarily unavailable")
            }
            is AiConstraintValue.CommuteLimit -> if (currentValue.maxMinutes <= 0) {
                throw TaskDependencyUnavailableException("Task understanding is temporarily unavailable")
            }
            is AiConstraintValue.TimeWindow -> if (
                run {
                    val startAt = currentValue.startAt
                    val endAt = currentValue.endAt
                    startAt != null && endAt != null && startAt >= endAt
                }
            ) {
                throw TaskDependencyUnavailableException("Task understanding is temporarily unavailable")
            }
            is AiConstraintValue.ActivityDomain -> currentValue.value.requireAiText()
            is AiConstraintValue.ExperiencePreference -> currentValue.text.requireAiText()
            is AiConstraintValue.Location -> currentValue.text.requireAiText()
            is AiConstraintValue.Topic -> currentValue.text.requireAiText()
        }
    }

    private fun String.requireAiText() {
        if (isBlank()) {
            throw TaskDependencyUnavailableException("Task understanding is temporarily unavailable")
        }
    }

    private fun com.nexusflow.ai.understanding.UnderstandingOutcome.assistantMessageWrite(): AssistantMessageWrite? {
        if (clarificationNeeded && assistantMessageDraft.isNullOrBlank()) {
            throw TaskDependencyUnavailableException("Task understanding is temporarily unavailable")
        }
        return assistantMessageDraft
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { AssistantMessageWrite(MessageId(uuidFactory()), it) }
    }

    private fun AiConstraintKind.toBackendKind(): ConstraintKind =
        when (this) {
            AiConstraintKind.TimeWindow -> ConstraintKind.TimeWindow
            AiConstraintKind.BudgetLimit -> ConstraintKind.BudgetLimit
            AiConstraintKind.CommuteLimit -> ConstraintKind.CommuteLimit
            AiConstraintKind.Location -> ConstraintKind.Location
            AiConstraintKind.ActivityDomain -> ConstraintKind.ActivityDomain
            AiConstraintKind.Topic -> ConstraintKind.Topic
            AiConstraintKind.ExperiencePreference -> ConstraintKind.ExperiencePreference
        }

    private fun AiConstraintStrength.toBackendStrength(): ConstraintStrength =
        when (this) {
            AiConstraintStrength.Hard -> ConstraintStrength.Hard
            AiConstraintStrength.Soft -> ConstraintStrength.Soft
        }

    private fun AiConstraintValue.toBackendValue(): ConstraintValue =
        when (this) {
            is AiConstraintValue.TimeWindow -> ConstraintValue.TimeWindow(
                startAt = startAt?.toJavaInstant(),
                endAt = endAt?.toJavaInstant(),
                timeZoneId = timeZoneId,
                originalText = originalText,
            )
            is AiConstraintValue.BudgetLimit -> ConstraintValue.BudgetLimit(wholeUnits = wholeUnits, currencyCode = currencyCode)
            is AiConstraintValue.CommuteLimit -> ConstraintValue.CommuteLimit(maxMinutes = maxMinutes)
            is AiConstraintValue.Location -> ConstraintValue.Location(text = text)
            is AiConstraintValue.ActivityDomain -> ConstraintValue.ActivityDomain(value = value)
            is AiConstraintValue.Topic -> ConstraintValue.Topic(text = text)
            is AiConstraintValue.ExperiencePreference -> ConstraintValue.ExperiencePreference(text = text)
        }

    private fun ContractInstant.toJavaInstant(): java.time.Instant =
        java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong())

    private fun TaskDetail.fixturePlan(
        planningRunId: PlanningRunId,
        now: java.time.Instant,
    ): Plan =
        Plan(
            id = PlanId(uuidFactory()),
            taskId = task.id,
            planningRunId = planningRunId,
            direction = "fixture",
            title = "Fixture plan",
            summary = "Deterministic M0 fixture plan for validating task planning persistence.",
            timeline = listOf(
                PlanTimelineItem(
                    title = "Review confirmed constraints",
                    startAt = null,
                    endAt = null,
                    location = null,
                ),
            ),
            estimatedCost = PlanEstimatedCost(wholeUnits = 0, currencyCode = null),
            commuteMinutes = null,
            satisfiedConstraintIds = constraints.map { it.id },
            tradeoffs = emptyList(),
            reasons = listOf("Generated by fixture planning capability"),
            sourceRefs = listOf(PlanSourceRef(label = "M0 fixture", uri = null)),
            validUntil = null,
            createdAt = now,
        )
}

private data class PendingUnderstanding(
    val detail: TaskDetail,
    val message: ConversationMessage,
    val taskVersion: Long,
)

data class GeneratePlansResult(
    val planningRun: PlanningRun,
    val plans: List<Plan>,
)

data class TaskUnderstandingFailureEvent(
    val taskId: String,
    val taskVersion: Long,
    val aiRequestId: String,
    val failureType: String,
)

sealed class TaskServiceException(message: String) : RuntimeException(message)

class MissingTaskScopeException : TaskServiceException("Missing required task scope")

class InvalidTaskRequestException(message: String) : TaskServiceException(message)

class TaskNotFoundException : TaskServiceException("Task was not found")

class TaskConflictException : TaskServiceException("Task request conflicts with existing state")

class InvalidTaskStateException : TaskServiceException("Task state does not allow this operation")

class TaskDependencyUnavailableException(message: String) : TaskServiceException(message)

private const val READ_SCOPE = "orbit.tasks.read"
private const val WRITE_SCOPE = "orbit.tasks.write"
private const val MAX_ID_LENGTH = 128
private const val MAX_GOAL_LENGTH = 2_000
private const val MAX_MESSAGE_LENGTH = 4_000
private const val MAX_TIME_ZONE_LENGTH = 128
