@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package com.nexusflow.app.feature.task.presentation.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.nexusflow.app.core.design.AppSpacing
import com.nexusflow.app.core.design.feedback.AppErrorState
import com.nexusflow.app.core.design.feedback.AppFullScreenLoading
import com.nexusflow.app.feature.task.domain.PlanId
import com.nexusflow.app.feature.task.domain.TaskDetail
import com.nexusflow.app.feature.task.domain.TaskId
import nexusflow.app.composeapp.generated.resources.Res
import nexusflow.app.composeapp.generated.resources.task_detail_unavailable_body
import nexusflow.app.composeapp.generated.resources.task_detail_unavailable_title
import nexusflow.app.composeapp.generated.resources.task_retry
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun TaskDetailRoute(
    taskId: String,
    onBackHome: () -> Unit,
    viewModel: TaskDetailViewModel = koinViewModel(parameters = { parametersOf(TaskId(taskId)) }),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) {
        viewModel.onAction(TaskDetailAction.Load)
    }
    TaskDetailContent(
        state = state,
        onBackHome = onBackHome,
        onRetry = { viewModel.onAction(TaskDetailAction.RetryLoad) },
        onDraftChanged = { viewModel.onAction(TaskDetailAction.DraftChanged(it)) },
        onSendMessage = { viewModel.onAction(TaskDetailAction.SendMessage) },
        onRetryMessage = { viewModel.onAction(TaskDetailAction.RetryMessage) },
        onSelectPlan = { viewModel.onAction(TaskDetailAction.SelectPlan(it)) },
        onRetryOperation = { viewModel.onAction(TaskDetailAction.RetryOperation(it)) },
    )
}

@Composable
fun TaskDetailContent(
    state: TaskDetailUiState,
    onBackHome: () -> Unit,
    onRetry: () -> Unit,
    onDraftChanged: (String) -> Unit,
    onSendMessage: () -> Unit,
    onRetryMessage: () -> Unit,
    onSelectPlan: (PlanId) -> Unit,
    onRetryOperation: (TaskDetailRetryTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val content = state.content) {
        TaskDetailContent.Uninitialized,
        TaskDetailContent.Loading,
        -> AppFullScreenLoading(modifier)
        TaskDetailContent.Failure ->
            AppErrorState(
                title = stringResource(Res.string.task_detail_unavailable_title),
                description = stringResource(Res.string.task_detail_unavailable_body),
                actionLabel = stringResource(Res.string.task_retry),
                onAction = onRetry,
                modifier = modifier.fillMaxSize().padding(AppSpacing.page),
            )
        is TaskDetailContent.Success ->
            TaskDetailSnapshot(
                detail = content.detail,
                draft = content.draft,
                operation = content.operation,
                pendingMessage = content.pendingMessage,
                failedMessage = content.failedMessage,
                operationFailure = content.operationFailure,
                expiredPlanIds = content.expiredPlanIds,
                onBackHome = onBackHome,
                onDraftChanged = onDraftChanged,
                onSendMessage = onSendMessage,
                onRetryMessage = onRetryMessage,
                onSelectPlan = onSelectPlan,
                onRetryOperation = onRetryOperation,
                modifier = modifier,
            )
    }
}

@Composable
private fun TaskDetailSnapshot(
    detail: TaskDetail,
    draft: String,
    operation: TaskDetailOperation,
    pendingMessage: PendingTaskMessage?,
    failedMessage: PendingTaskMessage?,
    operationFailure: TaskDetailOperationFailure?,
    expiredPlanIds: Set<PlanId>,
    onBackHome: () -> Unit,
    onDraftChanged: (String) -> Unit,
    onSendMessage: () -> Unit,
    onRetryMessage: () -> Unit,
    onSelectPlan: (PlanId) -> Unit,
    onRetryOperation: (TaskDetailRetryTarget) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().imePadding(),
    ) {
        TaskHeaderSection(
            detail = detail,
            onBackHome = onBackHome,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.page),
        )
        TaskTranscript(
            detail = detail,
            operation = operation,
            pendingMessage = pendingMessage,
            failedMessage = failedMessage,
            operationFailure = operationFailure,
            expiredPlanIds = expiredPlanIds,
            onSelectPlan = onSelectPlan,
            onRetryMessage = onRetryMessage,
            onRetryOperation = onRetryOperation,
            modifier = Modifier.weight(1f),
        )
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(horizontal = AppSpacing.page, vertical = AppSpacing.medium),
        ) {
            TaskComposer(
                draft = draft,
                placeholder = detail.composerPlaceholder(),
                operation = operation,
                onDraftChanged = onDraftChanged,
                onSendMessage = onSendMessage,
            )
        }
    }
}
