package com.nexusflow.app.feature.task.presentation.detail

import com.nexusflow.app.feature.task.domain.PlanId
import com.nexusflow.app.feature.task.domain.RequirementId
import com.nexusflow.app.feature.task.domain.TaskDetail

data class TaskDetailUiState(
    val content: TaskDetailContent = TaskDetailContent.Uninitialized,
)

sealed interface TaskDetailContent {
    data object Uninitialized : TaskDetailContent

    data object Loading : TaskDetailContent

    data class Success(
        val detail: TaskDetail,
        val draft: String = "",
        val operation: TaskDetailOperation = TaskDetailOperation.Idle,
        val pendingMessage: PendingTaskMessage? = null,
        val failedMessage: PendingTaskMessage? = null,
        val operationFailure: TaskDetailOperationFailure? = null,
        val expiredPlanIds: Set<PlanId> = emptySet(),
    ) : TaskDetailContent

    data object Failure : TaskDetailContent
}

data class PendingTaskMessage(
    val clientMessageId: String,
    val text: String,
)

sealed interface TaskDetailOperation {
    data object Idle : TaskDetailOperation

    data class SendingMessage(
        val clientMessageId: String,
    ) : TaskDetailOperation

    data class RemovingRequirement(
        val requirementId: RequirementId,
    ) : TaskDetailOperation

    data class SelectingPlan(
        val planId: PlanId,
    ) : TaskDetailOperation
}

data class TaskDetailOperationFailure(
    val reason: TaskDetailFailureReason,
    val retryTarget: TaskDetailRetryTarget?,
)

enum class TaskDetailFailureReason {
    MessageSendFailed,
    RequirementMutationFailed,
    SelectionConflict,
    SelectionFailed,
}

sealed interface TaskDetailRetryTarget {
    data class SelectPlan(
        val planId: PlanId,
    ) : TaskDetailRetryTarget
}

sealed interface TaskDetailAction {
    data object Load : TaskDetailAction

    data object RetryLoad : TaskDetailAction

    data class DraftChanged(
        val text: String,
    ) : TaskDetailAction

    data object SendMessage : TaskDetailAction

    data object RetryMessage : TaskDetailAction

    data class RemoveRequirement(
        val requirementId: RequirementId,
    ) : TaskDetailAction

    data class SelectPlan(
        val planId: PlanId,
    ) : TaskDetailAction

    data class RetryOperation(
        val target: TaskDetailRetryTarget,
    ) : TaskDetailAction
}
