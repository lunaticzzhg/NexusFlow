package com.nexusflow.backend.feature.task.domain

import java.time.Instant

interface TaskRepository {
    suspend fun createTask(command: CreateTaskPersistenceCommand): CreateTaskPersistenceResult

    suspend fun listTaskSummaries(owner: TaskOwner): List<TaskDetail>

    suspend fun findTaskDetail(
        owner: TaskOwner,
        taskId: TaskId,
    ): TaskDetail?

    suspend fun listTaskContextKeys(
        owner: TaskOwner,
        taskId: TaskId,
    ): List<String>

    suspend fun appendUserMessage(command: AppendUserMessageCommand): AppendUserMessageResult

    suspend fun recordAiUnderstandingAudit(command: RecordAiUnderstandingAuditCommand): RecordAiUnderstandingAuditResult

    suspend fun applyUnderstanding(command: ApplyUnderstandingCommand): ApplyUnderstandingResult

    suspend fun updateRequirement(command: UpdateRequirementCommand): RequirementMutationResult

    suspend fun deleteRequirement(command: DeleteRequirementCommand): RequirementMutationResult

    suspend fun persistPlans(command: PersistPlansCommand): PersistPlansResult

    suspend fun selectCurrentPlan(command: SelectPlanCommand): SelectPlanResult
}

data class CreateTaskPersistenceCommand(
    val owner: TaskOwner,
    val taskId: TaskId,
    val firstMessageId: MessageId,
    val creationRequestId: String,
    val message: String,
    val aiRequestId: String,
    val now: Instant,
)

sealed interface CreateTaskPersistenceResult {
    data class Created(
        val detail: TaskDetail,
        val message: TaskMessage,
        val taskRevision: Long,
    ) : CreateTaskPersistenceResult

    data class Existing(val detail: TaskDetail) : CreateTaskPersistenceResult

    data object ConflictingRequest : CreateTaskPersistenceResult
}

data class AppendUserMessageCommand(
    val owner: TaskOwner,
    val taskId: TaskId,
    val messageId: MessageId,
    val clientMessageId: String,
    val text: String,
    val aiRequestId: String,
    val now: Instant,
)

sealed interface AppendUserMessageResult {
    data class Appended(
        val detail: TaskDetail,
        val message: TaskMessage,
        val taskRevision: Long,
    ) : AppendUserMessageResult

    data class Existing(val detail: TaskDetail) : AppendUserMessageResult

    data object ConflictingMessage : AppendUserMessageResult

    data object TaskNotFound : AppendUserMessageResult
}

data class RecordAiUnderstandingAuditCommand(
    val owner: TaskOwner,
    val taskId: TaskId,
    val taskRevision: Long,
    val aiRequestId: String,
    val eventType: AiUnderstandingAuditEventType,
    val provider: String?,
    val model: String?,
    val promptVersion: String?,
    val providerRequestId: String?,
    val attemptCount: Int?,
    val usage: AiModelTokenUsage? = null,
    val diagnostics: AiInvocationDiagnostics = AiInvocationDiagnostics(),
    val outcome: String,
    val latencyMs: Long?,
    val failureCategory: String?,
    val now: Instant,
)

data class AiModelTokenUsage(
    val inputTokens: Int?,
    val outputTokens: Int?,
    val totalTokens: Int?,
)

data class AiInvocationDiagnostics(
    val availableContextDefinitionCount: Int = 0,
    val selectedContextKeyCount: Int = 0,
    val resolvedContextBlockCount: Int = 0,
    val includedContextBlockCount: Int = 0,
    val omittedContextBlockCount: Int = 0,
    val optionalContextSerializedChars: Int = 0,
    val contextDefinitionsSerializedChars: Int = 0,
    val fullUserPayloadSerializedChars: Int = 0,
)

enum class AiUnderstandingAuditEventType {
    Started,
    Succeeded,
    Failed,
}

sealed interface RecordAiUnderstandingAuditResult {
    data object Recorded : RecordAiUnderstandingAuditResult

    data object TaskNotFound : RecordAiUnderstandingAuditResult
}

data class ApplyUnderstandingCommand(
    val owner: TaskOwner,
    val taskId: TaskId,
    val expectedTaskRevision: Long,
    val messageId: MessageId,
    val aiRequestId: String,
    val intentPatch: String?,
    val requirements: List<RequirementWrite>,
    val selectedTaskContextKeys: List<String> = emptyList(),
    val assistantMessage: AssistantMessageWrite?,
    val now: Instant,
)

data class RequirementWrite(
    val id: RequirementId,
    val kind: RequirementKind,
    val value: RequirementValue,
    val strength: RequirementStrength,
    val source: RequirementSource = RequirementSource.UserExplicit,
)

data class AssistantMessageWrite(
    val id: MessageId,
    val text: String,
)

sealed interface ApplyUnderstandingResult {
    data class Applied(
        val detail: TaskDetail,
        val changedPlanningInputs: Boolean,
    ) : ApplyUnderstandingResult

    data object TaskNotFound : ApplyUnderstandingResult

    data object MessageNotFound : ApplyUnderstandingResult

    data object StaleTaskRevision : ApplyUnderstandingResult
}

data class UpdateRequirementCommand(
    val owner: TaskOwner,
    val taskId: TaskId,
    val requirementId: RequirementId,
    val kind: RequirementKind,
    val value: RequirementValue,
    val strength: RequirementStrength,
    val now: Instant,
)

data class DeleteRequirementCommand(
    val owner: TaskOwner,
    val taskId: TaskId,
    val requirementId: RequirementId,
    val now: Instant,
)

sealed interface RequirementMutationResult {
    data class Mutated(val detail: TaskDetail) : RequirementMutationResult

    data object TaskNotFound : RequirementMutationResult

    data object RequirementNotFound : RequirementMutationResult
}

data class PersistPlansCommand(
    val owner: TaskOwner,
    val taskId: TaskId,
    val expectedTaskRevision: Long,
    val opportunities: List<Opportunity>,
    val plans: List<Plan>,
    val now: Instant,
)

sealed interface PersistPlansResult {
    data class Persisted(val detail: TaskDetail) : PersistPlansResult

    data object TaskNotFound : PersistPlansResult

    data object StaleTaskRevision : PersistPlansResult
}

data class SelectPlanCommand(
    val owner: TaskOwner,
    val taskId: TaskId,
    val planId: PlanId,
    val now: Instant,
)

sealed interface SelectPlanResult {
    data class Selected(val detail: TaskDetail) : SelectPlanResult

    data object TaskNotFound : SelectPlanResult

    data object PlanNotFound : SelectPlanResult

    data object RevisionConflict : SelectPlanResult

    data object Expired : SelectPlanResult
}
