@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package com.nexusflow.app.feature.task.presentation.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.nexusflow.app.core.design.AppSpacing
import com.nexusflow.app.core.design.feedback.AppErrorState
import com.nexusflow.app.core.design.feedback.AppFullScreenLoading
import com.nexusflow.app.feature.task.domain.ConversationDetail
import com.nexusflow.app.feature.task.domain.PlanId
import nexusflow.app.composeapp.generated.resources.Res
import nexusflow.app.composeapp.generated.resources.task_detail_unavailable_body
import nexusflow.app.composeapp.generated.resources.task_detail_unavailable_title
import nexusflow.app.composeapp.generated.resources.task_retry
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun TaskDetailRoute(
    detailIdentity: TaskDetailIdentity,
    onBackHome: () -> Unit,
    viewModel: TaskDetailViewModel = koinViewModel(parameters = { parametersOf(detailIdentity) }),
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
        onCancelResponseRun = { viewModel.onAction(TaskDetailAction.CancelResponseRun(it)) },
        onRetryResponseRun = { viewModel.onAction(TaskDetailAction.RetryResponseRun(it)) },
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
    onCancelResponseRun: (com.nexusflow.app.feature.task.domain.ResponseRunId) -> Unit,
    onRetryResponseRun: (com.nexusflow.app.feature.task.domain.ResponseRunId) -> Unit,
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
                screen = content.screen,
                operation = content.operation,
                onBackHome = onBackHome,
                onDraftChanged = onDraftChanged,
                onSendMessage = onSendMessage,
                onRetryMessage = onRetryMessage,
                onCancelResponseRun = onCancelResponseRun,
                onRetryResponseRun = onRetryResponseRun,
                onSelectPlan = onSelectPlan,
                onRetryOperation = onRetryOperation,
                modifier = modifier,
            )
    }
}

@Composable
private fun TaskDetailSnapshot(
    detail: ConversationDetail,
    screen: TaskDetailScreenUiState,
    operation: TaskDetailOperation,
    onBackHome: () -> Unit,
    onDraftChanged: (String) -> Unit,
    onSendMessage: () -> Unit,
    onRetryMessage: () -> Unit,
    onCancelResponseRun: (com.nexusflow.app.feature.task.domain.ResponseRunId) -> Unit,
    onRetryResponseRun: (com.nexusflow.app.feature.task.domain.ResponseRunId) -> Unit,
    onSelectPlan: (PlanId) -> Unit,
    onRetryOperation: (TaskDetailRetryTarget) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        TaskHeaderSection(
            detail = detail,
            onBackHome = onBackHome,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(AppSpacing.page),
        )
        TaskChatSurface(
            detail = detail,
            screen = screen,
            operation = operation,
            onDraftChanged = onDraftChanged,
            onSendMessage = onSendMessage,
            onRetryMessage = onRetryMessage,
            onCancelResponseRun = onCancelResponseRun,
            onRetryResponseRun = onRetryResponseRun,
            onSelectPlan = onSelectPlan,
            onRetryOperation = onRetryOperation,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
    }
}

@Composable
private fun TaskChatSurface(
    detail: ConversationDetail,
    screen: TaskDetailScreenUiState,
    operation: TaskDetailOperation,
    onDraftChanged: (String) -> Unit,
    onSendMessage: () -> Unit,
    onRetryMessage: () -> Unit,
    onCancelResponseRun: (com.nexusflow.app.feature.task.domain.ResponseRunId) -> Unit,
    onRetryResponseRun: (com.nexusflow.app.feature.task.domain.ResponseRunId) -> Unit,
    onSelectPlan: (PlanId) -> Unit,
    onRetryOperation: (TaskDetailRetryTarget) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val keyboardInsetPx = WindowInsets.ime.getBottom(density)
    val safeBottomPx = WindowInsets.navigationBars.getBottom(density)
    val inputTopPadding = AppSpacing.medium
    val inputTopPaddingPx = with(density) { inputTopPadding.roundToPx() }
    val inputBottomPadding = with(density) { (keyboardInsetPx + safeBottomPx).toDp() }
    var inputContentHeightPx by remember { mutableStateOf(0) }
    val bottomObstruction =
        with(density) {
            (
                inputContentHeightPx.coerceAtLeast(0) +
                    inputTopPaddingPx.coerceAtLeast(0) +
                    keyboardInsetPx.coerceAtLeast(0) +
                    safeBottomPx.coerceAtLeast(0)
            ).toDp()
        }
    Box(modifier = modifier) {
        TaskTranscript(
            state = screen.transcript,
            operation = operation,
            onSelectPlan = onSelectPlan,
            onRetryMessage = onRetryMessage,
            onCancelResponseRun = onCancelResponseRun,
            onRetryResponseRun = onRetryResponseRun,
            onRetryOperation = onRetryOperation,
            bottomObstruction = bottomObstruction,
            modifier = Modifier.fillMaxSize(),
        )
        TaskInputOverlay(
            composer = screen.composer,
            placeholder = detail.currentTask.composerPlaceholder(),
            topPadding = inputTopPadding,
            bottomPadding = inputBottomPadding,
            onContentHeightChanged = { heightPx -> inputContentHeightPx = heightPx },
            onDraftChanged = onDraftChanged,
            onSendMessage = onSendMessage,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun TaskInputOverlay(
    composer: TaskComposerUiState,
    placeholder: String,
    topPadding: Dp,
    bottomPadding: Dp,
    onContentHeightChanged: (Int) -> Unit,
    onDraftChanged: (String) -> Unit,
    onSendMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(top = topPadding, bottom = bottomPadding)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(horizontal = AppSpacing.page),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { size -> onContentHeightChanged(size.height) },
        ) {
            TaskComposer(
                state = composer,
                placeholder = placeholder,
                onDraftChanged = onDraftChanged,
                onSendMessage = onSendMessage,
            )
        }
    }
}
