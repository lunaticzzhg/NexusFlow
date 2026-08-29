@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package com.nexusflow.app.feature.task.presentation.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.nexusflow.app.core.design.AppSpacing
import com.nexusflow.app.core.design.feedback.AppErrorState
import com.nexusflow.app.core.design.feedback.AppFullScreenLoading
import com.nexusflow.app.feature.task.domain.ConstraintKind
import com.nexusflow.app.feature.task.domain.ConstraintStrength
import com.nexusflow.app.feature.task.domain.ConstraintValue
import com.nexusflow.app.feature.task.domain.MessageRole
import com.nexusflow.app.feature.task.domain.PlanId
import com.nexusflow.app.feature.task.domain.TaskConstraint
import com.nexusflow.app.feature.task.domain.TaskDetail
import com.nexusflow.app.feature.task.domain.TaskId
import com.nexusflow.app.feature.task.domain.TaskMessage
import com.nexusflow.app.feature.task.domain.TaskPlan
import com.nexusflow.app.feature.task.domain.TaskState
import nexusflow.app.composeapp.generated.resources.Res
import nexusflow.app.composeapp.generated.resources.task_constraint_activity_domain
import nexusflow.app.composeapp.generated.resources.task_constraint_budget_limit
import nexusflow.app.composeapp.generated.resources.task_constraint_commute_limit
import nexusflow.app.composeapp.generated.resources.task_constraint_experience_preference
import nexusflow.app.composeapp.generated.resources.task_constraint_hard
import nexusflow.app.composeapp.generated.resources.task_constraint_location
import nexusflow.app.composeapp.generated.resources.task_constraint_soft
import nexusflow.app.composeapp.generated.resources.task_constraint_time_window
import nexusflow.app.composeapp.generated.resources.task_constraint_topic
import nexusflow.app.composeapp.generated.resources.task_detail_back_home
import nexusflow.app.composeapp.generated.resources.task_detail_constraints
import nexusflow.app.composeapp.generated.resources.task_detail_current_goal
import nexusflow.app.composeapp.generated.resources.task_detail_empty_constraints
import nexusflow.app.composeapp.generated.resources.task_detail_empty_messages
import nexusflow.app.composeapp.generated.resources.task_detail_empty_plans
import nexusflow.app.composeapp.generated.resources.task_detail_generate_fixture_plan
import nexusflow.app.composeapp.generated.resources.task_detail_plan_selected
import nexusflow.app.composeapp.generated.resources.task_detail_plans
import nexusflow.app.composeapp.generated.resources.task_detail_recent_interaction
import nexusflow.app.composeapp.generated.resources.task_detail_select_plan
import nexusflow.app.composeapp.generated.resources.task_detail_unavailable_body
import nexusflow.app.composeapp.generated.resources.task_detail_unavailable_title
import nexusflow.app.composeapp.generated.resources.task_message_assistant
import nexusflow.app.composeapp.generated.resources.task_message_user
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
import org.koin.core.parameter.parametersOf

@Composable
fun TaskDetailRoute(
    taskId: String,
    showFixturePlanning: Boolean,
    onBackHome: () -> Unit,
    viewModel: TaskDetailViewModel = koinViewModel(parameters = { parametersOf(TaskId(taskId)) }),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) {
        viewModel.onAction(TaskDetailAction.Load)
    }
    TaskDetailContent(
        state = state,
        showFixturePlanning = showFixturePlanning,
        onBackHome = onBackHome,
        onRetry = { viewModel.onAction(TaskDetailAction.Retry) },
        onGenerateFixturePlan = { viewModel.onAction(TaskDetailAction.GenerateFixturePlan) },
        onSelectPlan = { viewModel.onAction(TaskDetailAction.SelectPlan(it)) },
    )
}

@Composable
fun TaskDetailContent(
    state: TaskDetailUiState,
    showFixturePlanning: Boolean,
    onBackHome: () -> Unit,
    onRetry: () -> Unit,
    onGenerateFixturePlan: () -> Unit,
    onSelectPlan: (PlanId) -> Unit,
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
                planning = content.planning,
                selectingPlanId = content.selectingPlanId,
                showFixturePlanning = showFixturePlanning,
                onBackHome = onBackHome,
                onGenerateFixturePlan = onGenerateFixturePlan,
                onSelectPlan = onSelectPlan,
                modifier = modifier,
            )
    }
}

@Composable
private fun TaskDetailSnapshot(
    detail: TaskDetail,
    planning: TaskDetailOperation,
    selectingPlanId: PlanId?,
    showFixturePlanning: Boolean,
    onBackHome: () -> Unit,
    onGenerateFixturePlan: () -> Unit,
    onSelectPlan: (PlanId) -> Unit,
    modifier: Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(AppSpacing.page),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.large),
    ) {
        TextButton(onClick = onBackHome) {
            Text(stringResource(Res.string.task_detail_back_home))
        }
        Text(detail.title, style = MaterialTheme.typography.displaySmall)
        Text(
            text = taskStateLabel(detail.state),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.secondary,
        )
        Text(
            text = stringResource(Res.string.task_detail_current_goal, detail.currentGoal),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (showFixturePlanning && detail.state == TaskState.Planning) {
            Button(
                onClick = onGenerateFixturePlan,
                enabled = planning !is TaskDetailOperation.Loading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.task_detail_generate_fixture_plan))
            }
        }
        ConstraintSection(detail.constraints)
        MessageSection(detail.messages)
        PlanSection(
            plans = detail.plans,
            selectedPlanId = detail.selectedPlanId,
            selectingPlanId = selectingPlanId,
            showFixturePlanning = showFixturePlanning,
            onSelectPlan = onSelectPlan,
        )
    }
}

@Composable
private fun ConstraintSection(constraints: List<TaskConstraint>) {
    Section(title = stringResource(Res.string.task_detail_constraints)) {
        if (constraints.isEmpty()) {
            Text(stringResource(Res.string.task_detail_empty_constraints), style = MaterialTheme.typography.bodyMedium)
        } else {
            constraints.forEach { constraint ->
                Text(
                    text = "${constraint.kind.label()}: ${constraint.value.label()} (${constraint.strength.label()})",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun MessageSection(messages: List<TaskMessage>) {
    Section(title = stringResource(Res.string.task_detail_recent_interaction)) {
        if (messages.isEmpty()) {
            Text(stringResource(Res.string.task_detail_empty_messages), style = MaterialTheme.typography.bodyMedium)
        } else {
            messages.takeLast(4).forEach { message ->
                Text("${message.role.label()}: ${message.content}", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PlanSection(
    plans: List<TaskPlan>,
    selectedPlanId: PlanId?,
    selectingPlanId: PlanId?,
    showFixturePlanning: Boolean,
    onSelectPlan: (PlanId) -> Unit,
) {
    Section(title = stringResource(Res.string.task_detail_plans)) {
        if (plans.isEmpty()) {
            Text(stringResource(Res.string.task_detail_empty_plans), style = MaterialTheme.typography.bodyMedium)
        } else {
            plans.forEach { plan ->
                PlanCard(
                    plan = plan,
                    isSelected = selectedPlanId == plan.id,
                    isSelecting = selectingPlanId == plan.id,
                    showFixturePlanning = showFixturePlanning,
                    onSelectPlan = onSelectPlan,
                )
            }
        }
    }
}

@Composable
private fun PlanCard(
    plan: TaskPlan,
    isSelected: Boolean,
    isSelecting: Boolean,
    showFixturePlanning: Boolean,
    onSelectPlan: (PlanId) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(AppSpacing.medium),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.small),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                Text(plan.title, style = MaterialTheme.typography.titleMedium)
                if (isSelected) Text(stringResource(Res.string.task_detail_plan_selected), style = MaterialTheme.typography.labelMedium)
            }
            Text(plan.summary, style = MaterialTheme.typography.bodyMedium)
            plan.timeline.forEach { item ->
                Text(
                    text = listOfNotNull(item.title, item.location).joinToString(" - "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (showFixturePlanning) {
                Button(
                    onClick = { onSelectPlan(plan.id) },
                    enabled = !isSelected && !isSelecting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.task_detail_select_plan))
                }
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        content()
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

@Composable
private fun ConstraintKind.label(): String =
    when (this) {
        ConstraintKind.TimeWindow -> stringResource(Res.string.task_constraint_time_window)
        ConstraintKind.BudgetLimit -> stringResource(Res.string.task_constraint_budget_limit)
        ConstraintKind.CommuteLimit -> stringResource(Res.string.task_constraint_commute_limit)
        ConstraintKind.Location -> stringResource(Res.string.task_constraint_location)
        ConstraintKind.ActivityDomain -> stringResource(Res.string.task_constraint_activity_domain)
        ConstraintKind.Topic -> stringResource(Res.string.task_constraint_topic)
        ConstraintKind.ExperiencePreference -> stringResource(Res.string.task_constraint_experience_preference)
    }

private fun ConstraintValue.label(): String =
    when (this) {
        is ConstraintValue.TimeWindow -> originalText
        is ConstraintValue.BudgetLimit -> listOfNotNull(wholeUnits.toString(), currencyCode).joinToString(" ")
        is ConstraintValue.CommuteLimit -> "$maxMinutes min"
        is ConstraintValue.Text -> value
    }

@Composable
private fun ConstraintStrength.label(): String =
    when (this) {
        ConstraintStrength.Hard -> stringResource(Res.string.task_constraint_hard)
        ConstraintStrength.Soft -> stringResource(Res.string.task_constraint_soft)
    }

@Composable
private fun MessageRole.label(): String =
    when (this) {
        MessageRole.User -> stringResource(Res.string.task_message_user)
        MessageRole.Assistant -> stringResource(Res.string.task_message_assistant)
    }
