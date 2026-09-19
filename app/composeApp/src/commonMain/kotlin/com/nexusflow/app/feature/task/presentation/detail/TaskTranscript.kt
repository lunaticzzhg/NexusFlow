@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package com.nexusflow.app.feature.task.presentation.detail

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nexusflow.app.core.design.AppSpacing
import com.nexusflow.app.feature.task.domain.PlanId
import com.nexusflow.app.feature.task.domain.PlanningState
import com.nexusflow.app.feature.task.domain.TaskMessage
import com.nexusflow.app.feature.task.domain.TaskPlan
import kotlinx.coroutines.launch
import nexusflow.app.composeapp.generated.resources.Res
import nexusflow.app.composeapp.generated.resources.task_detail_jump_to_latest
import org.jetbrains.compose.resources.stringResource

sealed interface TaskTranscriptItem {
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
        override val key: String = "message-${message.id}"
        override val contentType: String = "message-${message.role}"
    }

    data class PlanningNotice(
        val state: PlanningState,
    ) : TaskTranscriptItem {
        override val key: String = "planning-notice-$state"
        override val contentType: String = "planning-notice"
    }

    data class ActiveResponse(
        val response: ActiveResponseUiState,
    ) : TaskTranscriptItem {
        override val key: String = "active-response-${response.runId.value}"
        override val contentType: String = "active-response"
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

    data class Planning(
        val plans: List<TaskPlan>,
        val selectedPlanId: PlanId?,
        val expiredPlanIds: Set<PlanId>,
    ) : TaskTranscriptItem {
        override val key: String = "planning-${plans.joinToString("-") { it.id.value }}-$selectedPlanId"
        override val contentType: String = "planning"
    }
}

@Composable
internal fun TaskTranscript(
    state: TaskTranscriptUiState,
    operation: TaskDetailOperation,
    onSelectPlan: (PlanId) -> Unit,
    onRetryMessage: () -> Unit,
    onCancelResponseRun: (com.nexusflow.app.feature.task.domain.ResponseRunId) -> Unit,
    onRetryResponseRun: (com.nexusflow.app.feature.task.domain.ResponseRunId) -> Unit,
    onRetryOperation: (TaskDetailRetryTarget) -> Unit,
    bottomObstruction: Dp,
    modifier: Modifier = Modifier,
) {
    val scrollState = remember { TaskTranscriptScrollState() }
    val listState = scrollState.listState
    val scope = rememberCoroutineScope()
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    val items = state.items
    val isNearBottom by remember(listState) {
        derivedStateOf { listState.isNearBottom() }
    }
    LaunchedEffect(isUserDragging, isNearBottom) {
        when {
            isUserDragging -> scrollState.pauseAutoFollow()
            isNearBottom -> scrollState.resumeAutoFollow()
        }
    }
    LaunchedEffect(state.conversationId, items, state.followSignal) {
        when (scrollState.nextCommand(items, state.followSignal)) {
            TaskTranscriptScrollCommand.FollowLatest -> listState.animateScrollToItem(bottomAnchorIndex(items))
            TaskTranscriptScrollCommand.None -> Unit
        }
    }
    LaunchedEffect(state.conversationId, bottomObstruction) {
        if (scrollState.autoFollowEnabled && !isUserDragging && items.isNotEmpty()) {
            listState.scrollToItem(bottomAnchorIndex(items))
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
                        TaskMessageBubble(
                            message = item.message,
                        )

                    is TaskTranscriptItem.ActiveResponse ->
                        ActiveResponseBubble(
                            response = item.response,
                            onCancel = onCancelResponseRun,
                            onRetry = onRetryResponseRun,
                        )

                    is TaskTranscriptItem.PlanningNotice ->
                        PlanningNoticeBanner(item.state)

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
                            plans = item.plans,
                            selectedPlanId = item.selectedPlanId,
                            operation = operation,
                            expiredPlanIds = item.expiredPlanIds,
                            onSelectPlan = onSelectPlan,
                        )
                }
            }
            item(key = "input-spacer", contentType = "input-spacer") {
                Spacer(Modifier.fillMaxWidth().height(bottomObstruction))
            }
            item(key = "bottom-anchor", contentType = "bottom-anchor") {
                Spacer(Modifier.fillMaxWidth().height(BottomAnchorHeight))
            }
        }
        if (!isNearBottom) {
            JumpToLatestButton(
                onClick = {
                    scrollState.resumeAutoFollow()
                    if (items.isNotEmpty()) {
                        scope.launch { listState.animateScrollToItem(bottomAnchorIndex(items)) }
                    }
                },
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = bottomObstruction + AppSpacing.medium),
            )
        }
    }
}

private fun LazyListState.isNearBottom(): Boolean {
    val totalItemsCount = layoutInfo.totalItemsCount
    if (totalItemsCount == 0) return true
    val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return false
    return lastVisibleItemIndex >= totalItemsCount - NEAR_BOTTOM_ITEM_THRESHOLD
}

private fun bottomAnchorIndex(items: List<TaskTranscriptItem>): Int = items.size + 1

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
private val BottomAnchorHeight = 1.dp
