package com.nexusflow.backend.feature.task.domain

import com.nexusflow.backend.feature.conversation.domain.ConversationId
import com.nexusflow.backend.feature.conversation.domain.MessageId
import java.time.Instant

interface TaskRepository {
    suspend fun createLinkedTask(command: CreateLinkedTaskPersistenceCommand): CreateLinkedTaskPersistenceResult

    suspend fun listTaskSummaries(owner: TaskOwner): List<TaskDetail>

    suspend fun findTaskDetail(
        owner: TaskOwner,
        taskId: TaskId,
    ): TaskDetail?

    suspend fun findCurrentTaskForConversation(
        owner: TaskOwner,
        conversationId: ConversationId,
    ): TaskDetail?

    suspend fun listTaskContextKeys(
        owner: TaskOwner,
        taskId: TaskId,
    ): List<String>

    suspend fun applyConversationUnderstanding(command: ApplyConversationUnderstandingCommand): ApplyUnderstandingResult

    suspend fun updateRequirement(command: UpdateRequirementCommand): RequirementMutationResult

    suspend fun deleteRequirement(command: DeleteRequirementCommand): RequirementMutationResult

    suspend fun persistPlans(command: PersistPlansCommand): PersistPlansResult

    suspend fun selectCurrentPlan(command: SelectPlanCommand): SelectPlanResult
}

data class CreateLinkedTaskPersistenceCommand(
    val owner: TaskOwner,
    val conversationId: ConversationId,
    val taskId: TaskId,
    val creationRequestId: String,
    val intent: String,
    val now: Instant,
)

sealed interface CreateLinkedTaskPersistenceResult {
    data class Created(val detail: TaskDetail) : CreateLinkedTaskPersistenceResult

    data class Existing(val detail: TaskDetail) : CreateLinkedTaskPersistenceResult

    data object ConflictingRequest : CreateLinkedTaskPersistenceResult
}

data class ApplyConversationUnderstandingCommand(
    val owner: TaskOwner,
    val taskId: TaskId,
    val expectedTaskRevision: Long,
    val conversationMessageId: MessageId,
    val aiRequestId: String,
    val intentPatch: String?,
    val requirements: List<RequirementWrite>,
    val removedRequirementKinds: List<RequirementKind> = emptyList(),
    val selectedTaskContextKeys: List<String> = emptyList(),
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
