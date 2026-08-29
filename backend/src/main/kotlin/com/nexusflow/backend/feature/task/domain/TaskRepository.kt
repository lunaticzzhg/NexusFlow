package com.nexusflow.backend.feature.task.domain

import java.time.Instant

interface TaskRepository {
    suspend fun createTaskWithConversation(command: CreateTaskPersistenceCommand): CreateTaskPersistenceResult

    suspend fun listTasks(owner: TaskOwner): List<Task>

    suspend fun findTaskDetail(
        owner: TaskOwner,
        taskId: TaskId,
    ): TaskDetail?

    suspend fun appendUserMessage(command: AppendUserMessageCommand): AppendUserMessageResult

    suspend fun recordAiUnderstandingAudit(command: RecordAiUnderstandingAuditCommand): RecordAiUnderstandingAuditResult

    suspend fun applyUnderstanding(command: ApplyUnderstandingCommand): ApplyUnderstandingResult

    suspend fun createFixturePlanningRun(command: CreateFixturePlanningRunCommand): CreateFixturePlanningRunResult

    suspend fun selectPlan(command: SelectPlanCommand): SelectPlanResult
}

data class CreateTaskPersistenceCommand(
    val owner: TaskOwner,
    val taskId: TaskId,
    val conversationId: ConversationId,
    val creationRequestId: String,
    val goal: String,
    val now: Instant,
)

sealed interface CreateTaskPersistenceResult {
    data class Created(val detail: TaskDetail) : CreateTaskPersistenceResult

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
        val message: ConversationMessage,
        val taskVersion: Long,
    ) : AppendUserMessageResult

    data class Existing(
        val detail: TaskDetail,
        val message: ConversationMessage,
        val taskVersion: Long,
    ) : AppendUserMessageResult

    data object ConflictingMessage : AppendUserMessageResult

    data object TaskNotFound : AppendUserMessageResult
}

data class RecordAiUnderstandingAuditCommand(
    val owner: TaskOwner,
    val taskId: TaskId,
    val taskVersion: Long,
    val aiRequestId: String,
    val eventType: AiUnderstandingAuditEventType,
    val provider: String?,
    val model: String?,
    val promptVersion: String?,
    val providerRequestId: String?,
    val attemptCount: Int?,
    val outcome: String,
    val latencyMs: Long?,
    val failureCategory: String?,
    val now: Instant,
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
    val expectedTaskVersion: Long,
    val messageId: MessageId,
    val aiRequestId: String,
    val constraints: List<ConfirmedConstraintWrite>,
    val assistantMessage: AssistantMessageWrite?,
    val targetState: TaskState,
    val now: Instant,
)

data class ConfirmedConstraintWrite(
    val id: ConstraintId,
    val kind: ConstraintKind,
    val value: ConstraintValue,
    val strength: ConstraintStrength,
)

data class AssistantMessageWrite(
    val id: MessageId,
    val text: String,
)

sealed interface ApplyUnderstandingResult {
    data class Applied(val detail: TaskDetail) : ApplyUnderstandingResult

    data object TaskNotFound : ApplyUnderstandingResult

    data object MessageNotFound : ApplyUnderstandingResult

    data object StaleTaskVersion : ApplyUnderstandingResult
}

data class CreateFixturePlanningRunCommand(
    val owner: TaskOwner,
    val taskId: TaskId,
    val planningRunId: PlanningRunId,
    val clientRequestId: String,
    val plans: List<Plan>,
    val now: Instant,
)

sealed interface CreateFixturePlanningRunResult {
    data class Created(val detail: TaskDetail, val planningRun: PlanningRun) : CreateFixturePlanningRunResult

    data class Existing(val detail: TaskDetail, val planningRun: PlanningRun) : CreateFixturePlanningRunResult

    data object TaskNotFound : CreateFixturePlanningRunResult

    data object InvalidState : CreateFixturePlanningRunResult
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

    data object InvalidState : SelectPlanResult
}
