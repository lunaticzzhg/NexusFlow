@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package com.nexusflow.app.feature.task.presentation.detail

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nexusflow.app.core.design.AppSpacing
import com.nexusflow.app.feature.task.domain.PlanId
import com.nexusflow.app.feature.task.domain.TaskDetail
import com.nexusflow.app.feature.task.domain.TaskMessage
import kotlinx.coroutines.launch
import nexusflow.app.composeapp.generated.resources.Res
import nexusflow.app.composeapp.generated.resources.task_detail_jump_to_latest
import org.jetbrains.compose.resources.stringResource

internal sealed interface TaskTranscriptItem {
    val key: String
    val contentType: String

    data class OperationFailure(
        val failure: TaskDetailOperationFailure,
    ) : TaskTranscriptItem {
        override val key: String = "operation-failure-${failure.reason}-${failure.retryTarget}"
        override val contentType: String = "operation-failure"
    }

    data class Message(
        val message: TaskMessage,
        val index: Int,
    ) : TaskTranscriptItem {
        override val key: String = "message-$index-${message.role}-${message.content.hashCode()}"
        override val contentType: String = "message-${message.role}"
    }

    data class PendingMessage(
        val message: PendingTaskMessage,
    ) : TaskTranscriptItem {
        override val key: String = "pending-message-${message.clientMessageId}"
        override val contentType: String = "pending-message"
    }

    data class FailedMessage(
        val message: PendingTaskMessage,
    ) : TaskTranscriptItem {
        override val key: String = "failed-message-${message.clientMessageId}"
        override val contentType: String = "failed-message"
    }

    data object Planning : TaskTranscriptItem {
        override val key: String = "planning"
        override val contentType: String = "planning"
    }
}

@Composable
internal fun TaskTranscript(
    detail: TaskDetail,
    operation: TaskDetailOperation,
    pendingMessage: PendingTaskMessage?,
    failedMessage: PendingTaskMessage?,
    operationFailure: TaskDetailOperationFailure?,
    expiredPlanIds: Set<PlanId>,
    onSelectPlan: (PlanId) -> Unit,
    onRetryMessage: () -> Unit,
    onRetryOperation: (TaskDetailRetryTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var autoFollow by remember { mutableStateOf(true) }
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    val items =
        remember(detail, pendingMessage, failedMessage, operation, operationFailure) {
            detail.toTranscriptItems(
                pendingMessage = pendingMessage,
                failedMessage = failedMessage,
                operation = operation,
                operationFailure = operationFailure,
            )
        }
    val isNearBottom by remember(listState) {
        derivedStateOf { listState.isNearBottom() }
    }
    LaunchedEffect(isUserDragging, isNearBottom) {
        when {
            isNearBottom -> autoFollow = true
            isUserDragging -> autoFollow = false
        }
    }
    LaunchedEffect(items.lastOrNull()?.key, items.size, autoFollow) {
        if (autoFollow && items.isNotEmpty()) {
            listState.animateScrollToItem(items.lastIndex)
        }
    }
    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = AppSpacing.page, vertical = AppSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.large),
        ) {
            items(
                items = items,
                key = TaskTranscriptItem::key,
                contentType = TaskTranscriptItem::contentType,
            ) { item ->
                when (item) {
                    is TaskTranscriptItem.OperationFailure ->
                        OperationFailureBanner(
                            failure = item.failure,
                            onRetryOperation = onRetryOperation,
                        )

                    is TaskTranscriptItem.Message ->
                        TaskMessageBubble(item.message)

                    is TaskTranscriptItem.PendingMessage ->
                        PendingTaskMessageBubble(
                            message = item.message,
                            isSending = operation == TaskDetailOperation.SendingMessage(item.message.clientMessageId),
                            isFailed = false,
                            onRetryMessage = onRetryMessage,
                        )

                    is TaskTranscriptItem.FailedMessage ->
                        PendingTaskMessageBubble(
                            message = item.message,
                            isSending = false,
                            isFailed = true,
                            onRetryMessage = onRetryMessage,
                        )

                    is TaskTranscriptItem.Planning ->
                        PlanningSection(
                            plans = detail.plans,
                            selectedPlanId = detail.selectedPlanId,
                            operation = operation,
                            expiredPlanIds = expiredPlanIds,
                            onSelectPlan = onSelectPlan,
                        )
                }
            }
        }
        if (!isNearBottom) {
            JumpToLatestButton(
                onClick = {
                    autoFollow = true
                    if (items.isNotEmpty()) {
                        scope.launch { listState.animateScrollToItem(items.lastIndex) }
                    }
                },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = AppSpacing.medium),
            )
        }
    }
}

private fun TaskDetail.toTranscriptItems(
    pendingMessage: PendingTaskMessage?,
    failedMessage: PendingTaskMessage?,
    operation: TaskDetailOperation,
    operationFailure: TaskDetailOperationFailure?,
): List<TaskTranscriptItem> =
    buildList {
        operationFailure
            ?.takeUnless { it.reason == TaskDetailFailureReason.MessageSendFailed }
            ?.let { add(TaskTranscriptItem.OperationFailure(it)) }
        messages.forEachIndexed { index, message ->
            add(TaskTranscriptItem.Message(message = message, index = index))
        }
        pendingMessage?.let { add(TaskTranscriptItem.PendingMessage(it)) }
        failedMessage?.let { add(TaskTranscriptItem.FailedMessage(it)) }
        if (plans.isNotEmpty()) {
            add(TaskTranscriptItem.Planning)
        }
    }

private fun LazyListState.isNearBottom(): Boolean {
    val totalItemsCount = layoutInfo.totalItemsCount
    if (totalItemsCount == 0) return true
    val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return false
    return lastVisibleItemIndex >= totalItemsCount - NEAR_BOTTOM_ITEM_THRESHOLD
}

@Composable
private fun JumpToLatestButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.size(48.dp),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
        shadowElevation = 2.dp,
    ) {
        IconButton(onClick = onClick) {
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = stringResource(Res.string.task_detail_jump_to_latest),
            )
        }
    }
}

private const val NEAR_BOTTOM_ITEM_THRESHOLD = 2
