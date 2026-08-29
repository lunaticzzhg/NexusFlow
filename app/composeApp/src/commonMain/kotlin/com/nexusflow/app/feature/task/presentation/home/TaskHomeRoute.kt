@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package com.nexusflow.app.feature.task.presentation.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.nexusflow.app.core.design.AppSpacing
import com.nexusflow.app.core.design.feedback.AppErrorState
import com.nexusflow.app.core.design.feedback.AppFullScreenLoading
import com.nexusflow.app.feature.task.domain.TaskId
import com.nexusflow.app.feature.task.domain.TaskState
import com.nexusflow.app.feature.task.domain.TaskSummary
import nexusflow.app.composeapp.generated.resources.Res
import nexusflow.app.composeapp.generated.resources.task_home_create
import nexusflow.app.composeapp.generated.resources.task_home_empty_body
import nexusflow.app.composeapp.generated.resources.task_home_empty_title
import nexusflow.app.composeapp.generated.resources.task_home_heading
import nexusflow.app.composeapp.generated.resources.task_home_latest
import nexusflow.app.composeapp.generated.resources.task_home_subtitle
import nexusflow.app.composeapp.generated.resources.task_home_title
import nexusflow.app.composeapp.generated.resources.task_home_unavailable_body
import nexusflow.app.composeapp.generated.resources.task_home_unavailable_title
import nexusflow.app.composeapp.generated.resources.task_retry
import nexusflow.app.composeapp.generated.resources.task_state_cancelled
import nexusflow.app.composeapp.generated.resources.task_state_collecting_constraints
import nexusflow.app.composeapp.generated.resources.task_state_completed
import nexusflow.app.composeapp.generated.resources.task_state_draft
import nexusflow.app.composeapp.generated.resources.task_state_executing
import nexusflow.app.composeapp.generated.resources.task_state_needs_attention
import nexusflow.app.composeapp.generated.resources.task_state_planning
import nexusflow.app.composeapp.generated.resources.task_state_waiting_for_approval
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun TaskHomeRoute(
    onOpenCreate: () -> Unit,
    onOpenTask: (String) -> Unit,
    viewModel: TaskHomeViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) {
        viewModel.onAction(TaskHomeAction.Load)
    }
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is TaskHomeEffect.OpenTask -> onOpenTask(effect.taskId.value)
            }
        }
    }
    TaskHomeContent(
        state = state,
        onRetry = { viewModel.onAction(TaskHomeAction.Retry) },
        onOpenCreate = onOpenCreate,
        onOpenTask = { viewModel.onAction(TaskHomeAction.OpenTask(it)) },
    )
}

@Composable
fun TaskHomeContent(
    state: TaskHomeUiState,
    onRetry: () -> Unit,
    onOpenCreate: () -> Unit,
    onOpenTask: (TaskId) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val content = state.content) {
        TaskHomeContent.Uninitialized,
        TaskHomeContent.Loading,
        -> AppFullScreenLoading(modifier)
        TaskHomeContent.Failure ->
            AppErrorState(
                title = stringResource(Res.string.task_home_unavailable_title),
                description = stringResource(Res.string.task_home_unavailable_body),
                actionLabel = stringResource(Res.string.task_retry),
                onAction = onRetry,
                modifier = modifier.fillMaxSize().padding(AppSpacing.page),
            )
        TaskHomeContent.Empty ->
            TaskHomeEmpty(
                onOpenCreate = onOpenCreate,
                modifier = modifier,
            )
        is TaskHomeContent.Success ->
            TaskHomeSuccess(
                summaries = content.summaries,
                onOpenCreate = onOpenCreate,
                onOpenTask = onOpenTask,
                modifier = modifier,
            )
    }
}

@Composable
private fun TaskHomeSuccess(
    summaries: List<TaskSummary>,
    onOpenCreate: () -> Unit,
    onOpenTask: (TaskId) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AppSpacing.page),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.large),
    ) {
        Text(
            text = stringResource(Res.string.task_home_heading),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(Res.string.task_home_title), style = MaterialTheme.typography.displaySmall)
        Text(
            text = stringResource(Res.string.task_home_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(Res.string.task_home_latest), style = MaterialTheme.typography.titleLarge)
        summaries.forEach { summary ->
            TaskSummaryCard(summary = summary, onOpenTask = onOpenTask)
        }
        Button(onClick = onOpenCreate, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.task_home_create))
        }
    }
}

@Composable
private fun TaskHomeEmpty(
    onOpenCreate: () -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(AppSpacing.page),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.medium),
    ) {
        Text(stringResource(Res.string.task_home_title), style = MaterialTheme.typography.displaySmall)
        Text(stringResource(Res.string.task_home_empty_title), style = MaterialTheme.typography.titleLarge)
        Text(
            text = stringResource(Res.string.task_home_empty_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onOpenCreate, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.task_home_create))
        }
    }
}

@Composable
private fun TaskSummaryCard(
    summary: TaskSummary,
    onOpenTask: (TaskId) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpenTask(summary.id) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.large),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.small),
        ) {
            Text(
                text = taskStateLabel(summary.state),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
            Text(summary.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = summary.currentGoal,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun taskStateLabel(state: TaskState): String =
    when (state) {
        TaskState.Draft -> stringResource(Res.string.task_state_draft)
        TaskState.CollectingConstraints -> stringResource(Res.string.task_state_collecting_constraints)
        TaskState.Planning -> stringResource(Res.string.task_state_planning)
        TaskState.WaitingForApproval -> stringResource(Res.string.task_state_waiting_for_approval)
        TaskState.Executing -> stringResource(Res.string.task_state_executing)
        TaskState.NeedsAttention -> stringResource(Res.string.task_state_needs_attention)
        TaskState.Completed -> stringResource(Res.string.task_state_completed)
        TaskState.Cancelled -> stringResource(Res.string.task_state_cancelled)
    }
