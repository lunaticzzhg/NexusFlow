package com.nexusflow.backend.feature.task.application

import com.nexusflow.ai.context.ModelContextBlockPayload as AiModelContextBlockPayload
import com.nexusflow.ai.context.ModelContextTrustPayload as AiModelContextTrustPayload
import com.nexusflow.ai.provider.StructuredModelCapability
import com.nexusflow.ai.provider.StructuredModelRequestDiagnostics
import com.nexusflow.ai.provider.StructuredModelUsage
import com.nexusflow.ai.understanding.ActivityModeValue as AiActivityModeValue
import com.nexusflow.ai.understanding.ClarificationReasonCategory as AiClarificationReasonCategory
import com.nexusflow.ai.understanding.CommutePreferenceValue as AiCommutePreferenceValue
import com.nexusflow.ai.understanding.CurrentRequirement as AiCurrentRequirement
import com.nexusflow.ai.understanding.ProposedRequirementChange as AiProposedRequirementChange
import com.nexusflow.ai.understanding.RequirementKind as AiRequirementKind
import com.nexusflow.ai.understanding.RequirementStrength as AiRequirementStrength
import com.nexusflow.ai.understanding.RequirementValue as AiRequirementValue
import com.nexusflow.ai.understanding.UnderstandingContext as AiUnderstandingContext
import com.nexusflow.ai.understanding.UserMessageUnderstanding
import com.nexusflow.ai.understanding.UserMessageUnderstandingException
import com.nexusflow.backend.core.aicontext.ModelContextAllowance
import com.nexusflow.backend.core.aicontext.ModelContextAssemblyDiagnostics
import com.nexusflow.backend.core.aicontext.ModelContextAssembler
import com.nexusflow.backend.core.aicontext.ModelContextBlock
import com.nexusflow.backend.core.aicontext.ModelContextCatalog
import com.nexusflow.backend.core.aicontext.ModelContextKey
import com.nexusflow.backend.core.aicontext.ModelContextLifecycle
import com.nexusflow.backend.core.aicontext.ModelContextResolveRequest
import com.nexusflow.backend.core.aicontext.ModelContextTrust
import com.nexusflow.backend.core.aicontext.SelectableModelContextDefinition
import com.nexusflow.backend.core.aicontext.toSelectable
import com.nexusflow.backend.core.identity.ActorContext
import com.nexusflow.backend.feature.task.domain.ActivityModeValue
import com.nexusflow.backend.feature.task.domain.AiInvocationDiagnostics
import com.nexusflow.backend.feature.task.domain.AiModelTokenUsage
import com.nexusflow.backend.feature.task.domain.AiUnderstandingAuditEventType
import com.nexusflow.backend.feature.task.domain.AppendUserMessageCommand
import com.nexusflow.backend.feature.task.domain.AppendUserMessageResult
import com.nexusflow.backend.feature.task.domain.ApplyUnderstandingCommand
import com.nexusflow.backend.feature.task.domain.ApplyUnderstandingResult
import com.nexusflow.backend.feature.task.domain.AssistantMessageWrite
import com.nexusflow.backend.feature.task.domain.CommutePreferenceValue
import com.nexusflow.backend.feature.task.domain.TaskMessage
import com.nexusflow.backend.feature.task.domain.CreateTaskPersistenceCommand
import com.nexusflow.backend.feature.task.domain.CreateTaskPersistenceResult
import com.nexusflow.backend.feature.task.domain.DeleteRequirementCommand
import com.nexusflow.backend.feature.task.domain.MessageId
import com.nexusflow.backend.feature.task.domain.MessageRole
import com.nexusflow.backend.feature.task.domain.RecordAiUnderstandingAuditCommand
import com.nexusflow.backend.feature.task.domain.RecordAiUnderstandingAuditResult
import com.nexusflow.backend.feature.task.domain.Requirement
import com.nexusflow.backend.feature.task.domain.RequirementId
import com.nexusflow.backend.feature.task.domain.RequirementKind
import com.nexusflow.backend.feature.task.domain.RequirementMutationResult
import com.nexusflow.backend.feature.task.domain.RequirementSource
import com.nexusflow.backend.feature.task.domain.RequirementStrength
import com.nexusflow.backend.feature.task.domain.RequirementValue
import com.nexusflow.backend.feature.task.domain.RequirementWrite
import com.nexusflow.backend.feature.task.domain.Task
import com.nexusflow.backend.feature.task.domain.TaskDetail
import com.nexusflow.backend.feature.task.domain.TaskId
import com.nexusflow.backend.feature.task.domain.TaskOwner
import com.nexusflow.backend.feature.task.domain.TaskRepository
import com.nexusflow.backend.feature.task.domain.TenantId
import com.nexusflow.backend.feature.task.domain.UpdateRequirementCommand
import com.nexusflow.backend.feature.task.domain.UserId
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant as ContractInstant
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.ZoneId
import java.util.UUID

class TaskService(
    private val repository: TaskRepository,
    private val planningService: PlanningService,
    private val understanding: UserMessageUnderstanding? = null,
    private val modelContextCatalog: ModelContextCatalog? = null,
    private val modelContextAssembler: ModelContextAssembler? = modelContextCatalog?.let(::ModelContextAssembler),
    private val logUnderstandingFailure: (TaskUnderstandingFailureEvent) -> Unit = {},
    private val clock: Clock = Clock.systemUTC(),
    private val uuidFactory: () -> UUID = UUID::randomUUID,
) {
    suspend fun createTask(
        actor: ActorContext,
        clientRequestId: String,
        message: String,
        timeZoneId: String,
    ): TaskDetail {
        actor.requireScope(WRITE_SCOPE)
        val owner = actor.taskOwner()
        val requestId = clientRequestId.requireBounded("clientRequestId", MAX_ID_LENGTH)
        val trimmedMessage = message.requireBounded("message", MAX_MESSAGE_LENGTH)
        val normalizedTimeZoneId = timeZoneId.requireBounded("timeZoneId", MAX_TIME_ZONE_LENGTH).requireValidTimeZoneId()
        val aiRequestId = "understand-${uuidFactory()}"
        val created = when (
            val result = repository.createTask(
                CreateTaskPersistenceCommand(
                    owner = owner,
                    taskId = TaskId(uuidFactory()),
                    firstMessageId = MessageId(uuidFactory()),
                    creationRequestId = requestId,
                    message = trimmedMessage,
                    aiRequestId = aiRequestId,
                    now = clock.instant(),
                ),
            )
        ) {
            is CreateTaskPersistenceResult.Created -> PendingUnderstanding(result.detail, result.message, result.taskRevision)
            is CreateTaskPersistenceResult.Existing -> {
                return continueExistingRequestIfNeeded(
                    actor = actor,
                    owner = owner,
                    detail = result.detail,
                    clientMessageId = requestId,
                    text = trimmedMessage,
                    timeZoneId = normalizedTimeZoneId,
                )
            }
            CreateTaskPersistenceResult.ConflictingRequest -> throw TaskConflictException()
        }

        return understandAndMaybePlan(actor, owner, created, normalizedTimeZoneId)
    }

    suspend fun listTasks(actor: ActorContext): List<TaskDetail> {
        actor.requireScope(READ_SCOPE)
        return repository.listTaskSummaries(actor.taskOwner())
    }

    suspend fun getTask(
        actor: ActorContext,
        taskId: String,
    ): TaskDetail {
        actor.requireScope(READ_SCOPE)
        val owner = actor.taskOwner()
        return repository.findTaskDetail(owner, taskId.toTaskId()) ?: throw TaskNotFoundException()
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
            is AppendUserMessageResult.Appended -> PendingUnderstanding(result.detail, result.message, result.taskRevision)
            is AppendUserMessageResult.Existing -> {
                return continueExistingRequestIfNeeded(
                    actor = actor,
                    owner = owner,
                    detail = result.detail,
                    clientMessageId = parsedClientMessageId,
                    text = trimmedText,
                    timeZoneId = normalizedTimeZoneId,
                )
            }
            AppendUserMessageResult.ConflictingMessage -> throw TaskConflictException()
            AppendUserMessageResult.TaskNotFound -> throw TaskNotFoundException()
        }

        return understandAndMaybePlan(actor, owner, pending, normalizedTimeZoneId)
    }

    suspend fun updateRequirement(
        actor: ActorContext,
        taskId: String,
        requirementId: String,
        kind: RequirementKind,
        value: RequirementValue,
        strength: RequirementStrength,
    ): TaskDetail {
        actor.requireScope(WRITE_SCOPE)
        val owner = actor.taskOwner()
        val detail = when (
            val result = repository.updateRequirement(
                UpdateRequirementCommand(
                    owner = owner,
                    taskId = taskId.toTaskId(),
                    requirementId = requirementId.toRequirementId(),
                    kind = kind,
                    value = value,
                    strength = strength,
                    now = clock.instant(),
                ),
            )
        ) {
            is RequirementMutationResult.Mutated -> result.detail
            RequirementMutationResult.RequirementNotFound,
            RequirementMutationResult.TaskNotFound,
            -> throw TaskNotFoundException()
        }
        return planningService.planIfReady(actor, owner, detail)
    }

    suspend fun deleteRequirement(
        actor: ActorContext,
        taskId: String,
        requirementId: String,
    ): TaskDetail {
        actor.requireScope(WRITE_SCOPE)
        val owner = actor.taskOwner()
        val detail = when (
            val result = repository.deleteRequirement(
                DeleteRequirementCommand(
                    owner = owner,
                    taskId = taskId.toTaskId(),
                    requirementId = requirementId.toRequirementId(),
                    now = clock.instant(),
                ),
            )
        ) {
            is RequirementMutationResult.Mutated -> result.detail
            RequirementMutationResult.RequirementNotFound,
            RequirementMutationResult.TaskNotFound,
            -> throw TaskNotFoundException()
        }
        return planningService.planIfReady(actor, owner, detail)
    }

    private suspend fun continueExistingRequestIfNeeded(
        actor: ActorContext,
        owner: TaskOwner,
        detail: TaskDetail,
        clientMessageId: String,
        text: String,
        timeZoneId: String,
    ): TaskDetail {
        val pendingMessage = detail.messages.firstOrNull { message ->
            message.role == MessageRole.User &&
                message.clientMessageId == clientMessageId &&
                message.content == text &&
                message.understoodAt == null
        }
        return if (pendingMessage != null) {
            understandAndMaybePlan(
                actor = actor,
                owner = owner,
                pending = PendingUnderstanding(detail, pendingMessage, detail.task.revision),
                timeZoneId = timeZoneId,
                tolerateStaleReplay = true,
            )
        } else {
            planningService.planIfReady(actor, owner, detail)
        }
    }

    private suspend fun understandAndMaybePlan(
        actor: ActorContext,
        owner: TaskOwner,
        pending: PendingUnderstanding,
        timeZoneId: String,
        tolerateStaleReplay: Boolean = false,
    ): TaskDetail {
        val attemptStartedAt = clock.instant()
        pending.recordAiUnderstandingStarted(attemptStartedAt)
        val capability = understanding
        if (capability == null) {
            pending.recordAiUnderstandingFailed("DependencyUnavailable", attemptStartedAt.elapsedMs())
            throw TaskDependencyUnavailableException("Task understanding is temporarily unavailable")
        }
        val understandingModelContext = try {
            pending.understandingModelContext(actor)
        } catch (_: IllegalArgumentException) {
            pending.recordAiUnderstandingFailed("InvalidModelContext", attemptStartedAt.elapsedMs())
            throw TaskDependencyUnavailableException("Task understanding is temporarily unavailable")
        }
        val outcome = try {
            capability.understand(
                pending.toAiContext(
                    timeZoneId = timeZoneId,
                    modelContext = understandingModelContext,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: UserMessageUnderstandingException) {
            pending.recordAiUnderstandingFailed(error.safeFailureCategory(), attemptStartedAt.elapsedMs())
            throw error.toUnavailable(pending)
        }
        val selectedContextKeys = try {
            outcome.contextSelection.selectedKeys.validateSelectedContextKeys(understandingModelContext.availableDefinitions)
        } catch (error: TaskDependencyUnavailableException) {
            pending.recordAiUnderstandingFailed("InvalidAiContextSelection", attemptStartedAt.elapsedMs())
            throw error
        }
        val requirements = try {
            outcome.requirementChanges.toRequirementWrites(pending.message.content)
        } catch (error: TaskDependencyUnavailableException) {
            pending.recordAiUnderstandingFailed("InvalidAiResult", attemptStartedAt.elapsedMs())
            throw error
        }
        val assistantMessage = try {
            outcome.assistantMessageWrite()
        } catch (error: TaskDependencyUnavailableException) {
            pending.recordAiUnderstandingFailed("InvalidAiResult", attemptStartedAt.elapsedMs())
            throw error
        }
        pending.recordAiUnderstandingSucceeded(outcome.metadata, attemptStartedAt.elapsedMs())

        val applied = when (
            val result = repository.applyUnderstanding(
                ApplyUnderstandingCommand(
                    owner = owner,
                    taskId = pending.detail.task.id,
                    expectedTaskRevision = pending.taskRevision,
                    messageId = pending.message.id,
                    aiRequestId = pending.message.aiRequestId ?: "",
                    intentPatch = outcome.intentPatch,
                    requirements = requirements,
                    selectedTaskContextKeys = selectedContextKeys,
                    assistantMessage = assistantMessage,
                    now = clock.instant(),
                ),
            )
        ) {
            is ApplyUnderstandingResult.Applied -> result
            ApplyUnderstandingResult.MessageNotFound,
            ApplyUnderstandingResult.TaskNotFound,
            -> throw TaskNotFoundException()
            ApplyUnderstandingResult.StaleTaskRevision -> {
                if (tolerateStaleReplay) {
                    return repository.findTaskDetail(owner, pending.detail.task.id) ?: throw TaskNotFoundException()
                }
                throw TaskConflictException()
            }
        }

        return if (applied.changedPlanningInputs) {
            planningService.planIfReady(actor, owner, applied.detail)
        } else {
            applied.detail
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

    private fun String.toRequirementId(): RequirementId = RequirementId(toUuid("requirementId"))

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

    private suspend fun PendingUnderstanding.recordAiUnderstandingStarted(now: java.time.Instant) {
        recordAiUnderstandingAudit(
            RecordAiUnderstandingAuditCommand(
                owner = detail.task.owner,
                taskId = detail.task.id,
                taskRevision = taskRevision,
                aiRequestId = message.aiRequestId ?: "",
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
        latencyMs: Long,
    ) {
        recordAiUnderstandingAudit(
            RecordAiUnderstandingAuditCommand(
                owner = detail.task.owner,
                taskId = detail.task.id,
                taskRevision = taskRevision,
                aiRequestId = message.aiRequestId ?: "",
                eventType = AiUnderstandingAuditEventType.Succeeded,
                provider = metadata.provider,
                model = metadata.model,
                promptVersion = metadata.promptVersion,
                providerRequestId = metadata.providerRequestId,
                attemptCount = metadata.attemptCount,
                usage = metadata.usage.toAuditUsage(),
                diagnostics = metadata.diagnostics.toAuditDiagnostics(),
                outcome = "succeeded",
                latencyMs = latencyMs,
                failureCategory = null,
                now = clock.instant(),
            ),
        )
    }

    private suspend fun PendingUnderstanding.recordAiUnderstandingFailed(
        failureCategory: String,
        latencyMs: Long,
    ) {
        recordAiUnderstandingAudit(
            RecordAiUnderstandingAuditCommand(
                owner = detail.task.owner,
                taskId = detail.task.id,
                taskRevision = taskRevision,
                aiRequestId = message.aiRequestId ?: "",
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
        pending: PendingUnderstanding,
    ): TaskDependencyUnavailableException {
        logUnderstandingFailure(
            TaskUnderstandingFailureEvent(
                taskId = pending.detail.task.id.value.toString(),
                taskRevision = pending.taskRevision,
                aiRequestId = pending.message.aiRequestId ?: "",
                failureType = this::class.simpleName ?: "UserMessageUnderstandingException",
            ),
        )
        return TaskDependencyUnavailableException("Task understanding is temporarily unavailable")
    }

    private fun UserMessageUnderstandingException.safeFailureCategory(): String =
        this::class.simpleName ?: "UserMessageUnderstandingException"

    private suspend fun PendingUnderstanding.understandingModelContext(actor: ActorContext): UnderstandingModelContext {
        val catalog = modelContextCatalog ?: return UnderstandingModelContext()
        val assembler = modelContextAssembler ?: return UnderstandingModelContext()
        val allowance = ModelContextAllowance(
            capability = StructuredModelCapability.UserMessageUnderstanding,
            lifecycles = setOf(ModelContextLifecycle.Task),
        )
        val selectedKeys = detail.selectedContextKeys.map(::ModelContextKey)
        val resolveRequest = ModelContextResolveRequest(
            actor = actor,
            allowance = allowance,
            taskId = detail.task.id.value.toString(),
            taskVersion = detail.task.revision,
            shadowedKeys = detail.requirements.mapNotNullTo(mutableSetOf()) { it.kind.profileContextKeyOrNull() },
        )
        val assembledContext = assembler.assemble(resolveRequest, selectedKeys)
        val availableDefinitions = catalog.definitions(allowance)
            .filterNot { definition -> definition.key in selectedKeys }
            .take(MAX_CONTEXT_DEFINITIONS_OFFERED)
            .map { it.toSelectable() }
        return UnderstandingModelContext(
            optionalContext = assembledContext.optionalContext,
            availableDefinitions = availableDefinitions,
            diagnostics = assembledContext.diagnostics.toAiRequestDiagnostics(
                availableContextDefinitionCount = availableDefinitions.size,
            ),
        )
    }

    private fun PendingUnderstanding.toAiContext(
        timeZoneId: String,
        modelContext: UnderstandingModelContext,
    ): AiUnderstandingContext =
        AiUnderstandingContext(
            aiRequestId = message.aiRequestId ?: "",
            taskId = detail.task.id.value.toString(),
            taskRevision = taskRevision,
            intent = detail.task.intent,
            requirements = detail.requirements.map { it.toAiCurrentRequirement() },
            currentMessage = message.content,
            referenceTime = clock.instant().toContractInstant(),
            timeZoneId = timeZoneId,
            optionalContext = modelContext.optionalContext.map { it.toAiPayload() },
            availableContextDefinitions = modelContext.availableDefinitions.map { it.toAiPayload() },
            diagnostics = modelContext.diagnostics,
        )

    private fun List<String>.validateSelectedContextKeys(
        availableDefinitions: List<SelectableModelContextDefinition>,
    ): List<String> {
        val offeredKeys = availableDefinitions.mapTo(linkedSetOf()) { it.key }
        val cleanKeys = map { it.trim() }
        val duplicate = cleanKeys.groupBy { it }.entries.firstOrNull { it.value.size > 1 }?.key
        if (
            cleanKeys.any(String::isBlank) ||
            duplicate != null ||
            cleanKeys.size > MAX_NEW_CONTEXT_SELECTIONS ||
            (offeredKeys.isEmpty() && cleanKeys.isNotEmpty()) ||
            cleanKeys.any { it !in offeredKeys }
        ) {
            throw TaskDependencyUnavailableException("Task understanding is temporarily unavailable")
        }

        return try {
            val parsedKeys = cleanKeys.map(::ModelContextKey)
            modelContextCatalog?.validateSelectedKeys(
                parsedKeys,
                ModelContextAllowance(
                    capability = StructuredModelCapability.UserMessageUnderstanding,
                    lifecycles = setOf(ModelContextLifecycle.Task),
                    allowedKeys = offeredKeys.mapTo(mutableSetOf(), ::ModelContextKey),
                ),
            )
            parsedKeys.map { it.value }
        } catch (_: IllegalArgumentException) {
            throw TaskDependencyUnavailableException("Task understanding is temporarily unavailable")
        }
    }

    private fun ModelContextBlock.toAiPayload(): AiModelContextBlockPayload =
        AiModelContextBlockPayload(
            key = key,
            trust = trust.toAiPayload(),
            content = content,
        )

    private fun ModelContextTrust.toAiPayload(): AiModelContextTrustPayload =
        AiModelContextTrustPayload.valueOf(name)

    private fun SelectableModelContextDefinition.toAiPayload(): com.nexusflow.ai.context.SelectableContextDefinitionPayload =
        com.nexusflow.ai.context.SelectableContextDefinitionPayload(
            key = key,
            description = description,
            selectionHint = selectionHint,
        )

    private fun ModelContextAssemblyDiagnostics.toAiRequestDiagnostics(
        availableContextDefinitionCount: Int,
    ): StructuredModelRequestDiagnostics =
        StructuredModelRequestDiagnostics(
            availableContextDefinitionCount = availableContextDefinitionCount,
            selectedContextKeyCount = selectedContextKeyCount,
            resolvedContextBlockCount = resolvedContextBlockCount,
            includedContextBlockCount = includedContextBlockCount,
            omittedContextBlockCount = omittedContextBlockCount,
            optionalContextSerializedChars = optionalContextSerializedChars,
        )

    private fun StructuredModelUsage?.toAuditUsage(): AiModelTokenUsage? =
        this?.let {
            AiModelTokenUsage(
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                totalTokens = totalTokens,
            )
        }

    private fun StructuredModelRequestDiagnostics.toAuditDiagnostics(): AiInvocationDiagnostics =
        AiInvocationDiagnostics(
            availableContextDefinitionCount = availableContextDefinitionCount,
            selectedContextKeyCount = selectedContextKeyCount,
            resolvedContextBlockCount = resolvedContextBlockCount,
            includedContextBlockCount = includedContextBlockCount,
            omittedContextBlockCount = omittedContextBlockCount,
            optionalContextSerializedChars = optionalContextSerializedChars,
            contextDefinitionsSerializedChars = contextDefinitionsSerializedChars,
            fullUserPayloadSerializedChars = fullUserPayloadSerializedChars,
        )

    private fun Requirement.toAiCurrentRequirement(): AiCurrentRequirement =
        AiCurrentRequirement(
            kind = kind.toAiRequirementKind(),
            value = value.toAiRequirementValue(),
            strength = strength.toAiRequirementStrength(),
        )

    private fun RequirementKind.toAiRequirementKind(): AiRequirementKind =
        when (this) {
            RequirementKind.TimeWindow -> AiRequirementKind.TimeWindow
            RequirementKind.BudgetLimit -> AiRequirementKind.BudgetLimit
            RequirementKind.CommuteLimit -> AiRequirementKind.CommuteLimit
            RequirementKind.CommutePreference -> AiRequirementKind.CommutePreference
            RequirementKind.Location -> AiRequirementKind.Location
            RequirementKind.ActivityDomain -> AiRequirementKind.ActivityDomain
            RequirementKind.ActivityMode -> AiRequirementKind.ActivityMode
            RequirementKind.Topic -> AiRequirementKind.Topic
            RequirementKind.ExperiencePreference -> AiRequirementKind.ExperiencePreference
        }

    private fun RequirementKind.profileContextKeyOrNull(): ModelContextKey? =
        when (this) {
            RequirementKind.TimeWindow -> ModelContextKey("profile.preference.time_window")
            RequirementKind.BudgetLimit -> ModelContextKey("profile.preference.budget_limit")
            RequirementKind.CommuteLimit -> ModelContextKey("profile.preference.commute_limit")
            RequirementKind.CommutePreference -> ModelContextKey("profile.preference.commute_mode")
            RequirementKind.Location -> ModelContextKey("profile.preference.location")
            RequirementKind.ActivityDomain -> ModelContextKey("profile.preference.activity_domain")
            RequirementKind.ActivityMode -> ModelContextKey("profile.preference.activity_mode")
            RequirementKind.Topic -> ModelContextKey("profile.preference.topic")
            RequirementKind.ExperiencePreference -> ModelContextKey("profile.preference.experience")
        }

    private fun RequirementValue.toAiRequirementValue(): AiRequirementValue =
        when (this) {
            is RequirementValue.TimeWindow -> AiRequirementValue.TimeWindow(
                startAt = startAt?.toContractInstant(),
                endAt = endAt?.toContractInstant(),
                timeZoneId = timeZoneId,
                originalText = originalText,
            )
            is RequirementValue.BudgetLimit -> AiRequirementValue.BudgetLimit(wholeUnits = wholeUnits, currencyCode = currencyCode)
            is RequirementValue.CommuteLimit -> AiRequirementValue.CommuteLimit(maxMinutes = maxMinutes)
            is RequirementValue.CommutePreference -> AiRequirementValue.CommutePreference(
                value = this.value.toAiCommutePreferenceValue(),
            )
            is RequirementValue.Location -> AiRequirementValue.Location(text = text)
            is RequirementValue.ActivityDomain -> AiRequirementValue.ActivityDomain(value = value)
            is RequirementValue.ActivityMode -> AiRequirementValue.ActivityMode(value = this.value.toAiActivityModeValue())
            is RequirementValue.Topic -> AiRequirementValue.Topic(text = text)
            is RequirementValue.ExperiencePreference -> AiRequirementValue.ExperiencePreference(text = text)
        }

    private fun RequirementStrength.toAiRequirementStrength(): AiRequirementStrength =
        when (this) {
            RequirementStrength.Must -> AiRequirementStrength.Must
            RequirementStrength.Prefer -> AiRequirementStrength.Prefer
        }

    private fun java.time.Instant.toContractInstant(): ContractInstant =
        ContractInstant.fromEpochSeconds(epochSecond, nano.toLong())

    private fun ContractInstant.toJavaInstant(): java.time.Instant =
        java.time.Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong())

    private fun List<AiProposedRequirementChange>.toRequirementWrites(messageText: String): List<RequirementWrite> =
        map { proposal ->
            proposal.validate(messageText)
            RequirementWrite(
                id = RequirementId(uuidFactory()),
                kind = proposal.kind.toBackendKind(),
                value = proposal.value.toBackendValue(),
                strength = proposal.strength.toBackendStrength(),
            )
        }

    private fun AiProposedRequirementChange.validate(messageText: String) {
        if (evidenceText.isBlank() || !messageText.contains(evidenceText)) {
            throw TaskDependencyUnavailableException("Task understanding is temporarily unavailable")
        }
        when (val currentValue = value) {
            is AiRequirementValue.BudgetLimit -> if (currentValue.wholeUnits <= 0) {
                throw TaskDependencyUnavailableException("Task understanding is temporarily unavailable")
            }
            is AiRequirementValue.CommuteLimit -> if (currentValue.maxMinutes <= 0) {
                throw TaskDependencyUnavailableException("Task understanding is temporarily unavailable")
            }
            is AiRequirementValue.TimeWindow -> if (
                run {
                    val startAt = currentValue.startAt
                    val endAt = currentValue.endAt
                    startAt != null && endAt != null && startAt >= endAt
                }
            ) {
                throw TaskDependencyUnavailableException("Task understanding is temporarily unavailable")
            }
            is AiRequirementValue.ActivityDomain -> currentValue.value.requireAiText()
            is AiRequirementValue.ActivityMode -> Unit
            is AiRequirementValue.CommutePreference -> Unit
            is AiRequirementValue.ExperiencePreference -> currentValue.text.requireAiText()
            is AiRequirementValue.Location -> currentValue.text.requireAiText()
            is AiRequirementValue.Topic -> currentValue.text.requireAiText()
        }
    }

    private fun String.requireAiText() {
        if (isBlank()) {
            throw TaskDependencyUnavailableException("Task understanding is temporarily unavailable")
        }
    }

    private fun com.nexusflow.ai.understanding.UnderstandingOutcome.assistantMessageWrite(): AssistantMessageWrite? {
        if (clarification.needed && clarification.questionDraft.isNullOrBlank()) {
            throw TaskDependencyUnavailableException("Task understanding is temporarily unavailable")
        }
        return clarification.questionDraft
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?.let { AssistantMessageWrite(MessageId(uuidFactory()), it) }
    }

    private fun AiRequirementKind.toBackendKind(): RequirementKind =
        when (this) {
            AiRequirementKind.TimeWindow -> RequirementKind.TimeWindow
            AiRequirementKind.BudgetLimit -> RequirementKind.BudgetLimit
            AiRequirementKind.CommuteLimit -> RequirementKind.CommuteLimit
            AiRequirementKind.CommutePreference -> RequirementKind.CommutePreference
            AiRequirementKind.Location -> RequirementKind.Location
            AiRequirementKind.ActivityDomain -> RequirementKind.ActivityDomain
            AiRequirementKind.ActivityMode -> RequirementKind.ActivityMode
            AiRequirementKind.Topic -> RequirementKind.Topic
            AiRequirementKind.ExperiencePreference -> RequirementKind.ExperiencePreference
        }

    private fun AiRequirementStrength.toBackendStrength(): RequirementStrength =
        when (this) {
            AiRequirementStrength.Must -> RequirementStrength.Must
            AiRequirementStrength.Prefer -> RequirementStrength.Prefer
        }

    private fun AiRequirementValue.toBackendValue(): RequirementValue =
        when (this) {
            is AiRequirementValue.TimeWindow -> RequirementValue.TimeWindow(
                startAt = startAt?.toJavaInstant(),
                endAt = endAt?.toJavaInstant(),
                timeZoneId = timeZoneId,
                originalText = originalText,
            )
            is AiRequirementValue.BudgetLimit -> RequirementValue.BudgetLimit(wholeUnits = wholeUnits, currencyCode = currencyCode)
            is AiRequirementValue.CommuteLimit -> RequirementValue.CommuteLimit(maxMinutes = maxMinutes)
            is AiRequirementValue.CommutePreference -> RequirementValue.CommutePreference(
                value.toBackendCommutePreferenceValue(),
            )
            is AiRequirementValue.Location -> RequirementValue.Location(text = text)
            is AiRequirementValue.ActivityDomain -> RequirementValue.ActivityDomain(value = value)
            is AiRequirementValue.ActivityMode -> RequirementValue.ActivityMode(value.toBackendActivityModeValue())
            is AiRequirementValue.Topic -> RequirementValue.Topic(text = text)
            is AiRequirementValue.ExperiencePreference -> RequirementValue.ExperiencePreference(text = text)
        }

    private fun CommutePreferenceValue.toAiCommutePreferenceValue(): AiCommutePreferenceValue =
        when (this) {
            CommutePreferenceValue.PreferShorter -> AiCommutePreferenceValue.PreferShorter
        }

    private fun AiCommutePreferenceValue.toBackendCommutePreferenceValue(): CommutePreferenceValue =
        when (this) {
            AiCommutePreferenceValue.PreferShorter -> CommutePreferenceValue.PreferShorter
        }

    private fun ActivityModeValue.toAiActivityModeValue(): AiActivityModeValue =
        when (this) {
            ActivityModeValue.AtHome -> AiActivityModeValue.AtHome
            ActivityModeValue.OutOfHome -> AiActivityModeValue.OutOfHome
        }

    private fun AiActivityModeValue.toBackendActivityModeValue(): ActivityModeValue =
        when (this) {
            AiActivityModeValue.AtHome -> ActivityModeValue.AtHome
            AiActivityModeValue.OutOfHome -> ActivityModeValue.OutOfHome
        }
}

private data class PendingUnderstanding(
    val detail: TaskDetail,
    val message: TaskMessage,
    val taskRevision: Long,
)

private data class UnderstandingModelContext(
    val optionalContext: List<ModelContextBlock> = emptyList(),
    val availableDefinitions: List<SelectableModelContextDefinition> = emptyList(),
    val diagnostics: StructuredModelRequestDiagnostics = StructuredModelRequestDiagnostics(),
)

data class TaskUnderstandingFailureEvent(
    val taskId: String,
    val taskRevision: Long,
    val aiRequestId: String,
    val failureType: String,
)

sealed class TaskServiceException(message: String) : RuntimeException(message)

class MissingTaskScopeException : TaskServiceException("Missing required task scope")

class InvalidTaskRequestException(message: String) : TaskServiceException(message)

class TaskNotFoundException : TaskServiceException("Task was not found")

class TaskConflictException : TaskServiceException("Task request conflicts with existing state")

class InvalidTaskOperationException : TaskServiceException("Task operation is not allowed")

class TaskDependencyUnavailableException(message: String) : TaskServiceException(message)

private const val READ_SCOPE = "orbit.tasks.read"
private const val WRITE_SCOPE = "orbit.tasks.write"
private const val MAX_ID_LENGTH = 128
private const val MAX_MESSAGE_LENGTH = 4_000
private const val MAX_TIME_ZONE_LENGTH = 128
private const val MAX_NEW_CONTEXT_SELECTIONS = 6
private const val MAX_CONTEXT_DEFINITIONS_OFFERED = 24
