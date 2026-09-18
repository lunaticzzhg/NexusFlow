@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package com.nexusflow.app.feature.task.presentation.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.nexusflow.app.core.design.AppSpacing
import com.nexusflow.app.feature.task.domain.ActivityModeValue
import com.nexusflow.app.feature.task.domain.ConversationDetail
import com.nexusflow.app.feature.task.domain.MessageRole
import com.nexusflow.app.feature.task.domain.PlanDirection
import com.nexusflow.app.feature.task.domain.PlanId
import com.nexusflow.app.feature.task.domain.PlanningState
import com.nexusflow.app.feature.task.domain.RequirementKind
import com.nexusflow.app.feature.task.domain.RequirementSource
import com.nexusflow.app.feature.task.domain.RequirementStrength
import com.nexusflow.app.feature.task.domain.RequirementValue
import com.nexusflow.app.feature.task.domain.ResponseRunActivityKind
import com.nexusflow.app.feature.task.domain.ResponseRunId
import com.nexusflow.app.feature.task.domain.TaskDetail
import com.nexusflow.app.feature.task.domain.TaskMessage
import com.nexusflow.app.feature.task.domain.TaskPlan
import com.nexusflow.app.feature.task.domain.TaskRequirement
import nexusflow.app.composeapp.generated.resources.Res
import nexusflow.app.composeapp.generated.resources.task_detail_conversation_title
import nexusflow.app.composeapp.generated.resources.task_detail_empty_requirements
import nexusflow.app.composeapp.generated.resources.task_detail_message_failed
import nexusflow.app.composeapp.generated.resources.task_detail_message_retry
import nexusflow.app.composeapp.generated.resources.task_detail_operation_requirement_failed
import nexusflow.app.composeapp.generated.resources.task_detail_operation_selection_conflict
import nexusflow.app.composeapp.generated.resources.task_detail_operation_selection_failed
import nexusflow.app.composeapp.generated.resources.task_detail_plan_select
import nexusflow.app.composeapp.generated.resources.task_detail_plan_selected
import nexusflow.app.composeapp.generated.resources.task_detail_plan_valid_until
import nexusflow.app.composeapp.generated.resources.task_detail_planning_no_candidates
import nexusflow.app.composeapp.generated.resources.task_detail_planning_no_feasible_plan
import nexusflow.app.composeapp.generated.resources.task_detail_planning_unavailable
import nexusflow.app.composeapp.generated.resources.task_detail_requirements
import nexusflow.app.composeapp.generated.resources.task_detail_response_cancel
import nexusflow.app.composeapp.generated.resources.task_detail_response_cancelled
import nexusflow.app.composeapp.generated.resources.task_detail_response_failed
import nexusflow.app.composeapp.generated.resources.task_detail_response_processing
import nexusflow.app.composeapp.generated.resources.task_detail_response_retry
import nexusflow.app.composeapp.generated.resources.task_detail_response_timed_out
import nexusflow.app.composeapp.generated.resources.task_detail_response_tool_movie
import nexusflow.app.composeapp.generated.resources.task_detail_response_tool_music
import nexusflow.app.composeapp.generated.resources.task_detail_response_tool_place
import nexusflow.app.composeapp.generated.resources.task_detail_response_tool_route
import nexusflow.app.composeapp.generated.resources.task_detail_response_tool_sports
import nexusflow.app.composeapp.generated.resources.task_detail_response_tool_weather
import nexusflow.app.composeapp.generated.resources.task_detail_response_tool_web
import nexusflow.app.composeapp.generated.resources.task_detail_send
import nexusflow.app.composeapp.generated.resources.task_detail_send_hint
import nexusflow.app.composeapp.generated.resources.task_requirement_activity_domain
import nexusflow.app.composeapp.generated.resources.task_requirement_activity_mode
import nexusflow.app.composeapp.generated.resources.task_requirement_budget_limit
import nexusflow.app.composeapp.generated.resources.task_requirement_commute_limit
import nexusflow.app.composeapp.generated.resources.task_requirement_commute_minutes
import nexusflow.app.composeapp.generated.resources.task_requirement_commute_preference
import nexusflow.app.composeapp.generated.resources.task_requirement_experience_preference
import nexusflow.app.composeapp.generated.resources.task_requirement_location
import nexusflow.app.composeapp.generated.resources.task_requirement_must
import nexusflow.app.composeapp.generated.resources.task_requirement_prefer
import nexusflow.app.composeapp.generated.resources.task_requirement_source_system_derived
import nexusflow.app.composeapp.generated.resources.task_requirement_source_user_explicit
import nexusflow.app.composeapp.generated.resources.task_requirement_time_window
import nexusflow.app.composeapp.generated.resources.task_requirement_topic
import nexusflow.app.composeapp.generated.resources.task_requirement_value_at_home
import nexusflow.app.composeapp.generated.resources.task_requirement_value_out_of_home
import nexusflow.app.composeapp.generated.resources.task_requirement_value_prefer_shorter
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun TaskHeaderSection(
    detail: ConversationDetail,
    onBackHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title =
        detail.currentTask?.intent
            ?: detail.messages.firstOrNull { it.role == MessageRole.User }?.content
            ?: stringResource(Res.string.task_detail_conversation_title)
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(AppSpacing.medium),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBackHome) {
                Icon(imageVector = Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
        }
        detail.currentTask?.let { RequirementStrip(it.requirements) }
    }
}

@Composable
private fun RequirementStrip(requirements: List<TaskRequirement>) {
    if (requirements.isEmpty()) {
        Text(
            text = stringResource(Res.string.task_detail_empty_requirements),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
        Text(stringResource(Res.string.task_detail_requirements), style = MaterialTheme.typography.titleMedium)
        requirements.forEach { requirement ->
            RequirementRow(requirement)
        }
    }
}

@Composable
private fun RequirementRow(requirement: TaskRequirement) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(AppSpacing.medium),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(requirement.kind.label(), style = MaterialTheme.typography.labelMedium)
                Text(requirement.value.label(), style = MaterialTheme.typography.bodyMedium)
            }
            AssistChip(onClick = {}, label = { Text(requirement.strength.label()) })
            Text(
                text = requirement.source.label(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun OperationFailureBanner(
    failure: TaskDetailOperationFailure,
    onRetryOperation: (TaskDetailRetryTarget) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(AppSpacing.medium),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = failure.reason.label(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            failure.retryTarget?.let { retryTarget ->
                Button(onClick = { onRetryOperation(retryTarget) }) {
                    Text(stringResource(Res.string.task_detail_message_retry))
                }
            }
        }
    }
}

@Composable
internal fun TaskMessageBubble(message: TaskMessage) {
    val isUser = message.role == MessageRole.User
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(AppSpacing.small),
        ) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color =
                    if (isUser) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                modifier = Modifier.widthIn(max = 520.dp),
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(AppSpacing.medium),
                )
            }
        }
    }
}

@Composable
internal fun ActiveResponseBubble(
    response: ActiveResponseUiState,
    onCancel: (ResponseRunId) -> Unit,
    onRetry: (ResponseRunId) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.widthIn(max = 520.dp),
        ) {
            Column(
                modifier = Modifier.padding(AppSpacing.medium),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.small),
            ) {
                val partial = response.partialText.takeIf { it.isNotBlank() }
                Text(
                    text = partial ?: response.status.label(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                response.activities
                    .filter { it.kind != ResponseRunActivityKind.Thinking && it.completedAt == null }
                    .forEach { activity ->
                        Text(
                            text = activity.kind.label(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                    if (response.canCancel) {
                        Button(onClick = { onCancel(response.runId) }) {
                            Text(stringResource(Res.string.task_detail_response_cancel))
                        }
                    }
                    if (response.canRetry) {
                        Button(onClick = { onRetry(response.runId) }) {
                            Text(stringResource(Res.string.task_detail_response_retry))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlanningNoticeBanner(state: PlanningState) {
    val message =
        when (state) {
            PlanningState.NoCandidates -> stringResource(Res.string.task_detail_planning_no_candidates)
            PlanningState.NoFeasiblePlan -> stringResource(Res.string.task_detail_planning_no_feasible_plan)
            PlanningState.Unavailable -> stringResource(Res.string.task_detail_planning_unavailable)
            PlanningState.Idle,
            PlanningState.Ready,
            -> return
        }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(AppSpacing.medium),
        )
    }
}

@Composable
internal fun PendingTaskMessageBubble(
    message: PendingTaskMessage,
    isSending: Boolean,
    isFailed: Boolean,
    onRetryMessage: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.End,
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.small),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = if (isSending) 0.72f else 1f),
            modifier = Modifier.widthIn(max = 520.dp),
        ) {
            Text(
                text = message.text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(AppSpacing.medium),
            )
        }
        if (isFailed) {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)) {
                Text(
                    text = stringResource(Res.string.task_detail_message_failed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = onRetryMessage) {
                    Text(stringResource(Res.string.task_detail_message_retry))
                }
            }
        }
    }
}

@Composable
internal fun TaskComposer(
    draft: String,
    placeholder: String,
    operation: TaskDetailOperation,
    onDraftChanged: (String) -> Unit,
    onSendMessage: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.small),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChanged,
            placeholder = { Text(placeholder) },
            enabled = operation == TaskDetailOperation.Idle,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSendMessage() }),
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onSendMessage,
            enabled = draft.isNotBlank() && operation == TaskDetailOperation.Idle,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.Send,
                contentDescription = stringResource(Res.string.task_detail_send),
            )
        }
    }
}

@Composable
internal fun PlanningSection(
    plans: List<TaskPlan>,
    selectedPlanId: PlanId?,
    operation: TaskDetailOperation,
    expiredPlanIds: Set<PlanId>,
    onSelectPlan: (PlanId) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)) {
        plans.forEach { plan ->
            PlanCard(
                plan = plan,
                isSelected = plan.id == selectedPlanId,
                isExpired = plan.id in expiredPlanIds,
                isSelecting = operation == TaskDetailOperation.SelectingPlan(plan.id),
                onSelectPlan = onSelectPlan,
            )
        }
    }
}

@Composable
private fun PlanCard(
    plan: TaskPlan,
    isSelected: Boolean,
    isExpired: Boolean,
    isSelecting: Boolean,
    onSelectPlan: (PlanId) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border =
            if (isSelected) {
                BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
            } else {
                null
            },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(AppSpacing.large),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.small),
        ) {
            Text(plan.direction.label(), style = MaterialTheme.typography.labelMedium)
            Text(plan.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = plan.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            plan.timeline.forEach { item ->
                Text(
                    text = listOfNotNull(item.title, item.location).joinToString(" - "),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            plan.validUntil?.let {
                Text(
                    text = stringResource(Res.string.task_detail_plan_valid_until, it.toString()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isSelected) {
                Text(
                    text = stringResource(Res.string.task_detail_plan_selected),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Button(
                    onClick = { onSelectPlan(plan.id) },
                    enabled = !isSelecting && !isExpired,
                ) {
                    Text(stringResource(Res.string.task_detail_plan_select))
                }
            }
        }
    }
}

@Composable
internal fun TaskDetail?.composerPlaceholder(): String =
    if (this == null || requirements.isEmpty()) {
        stringResource(Res.string.task_detail_send_hint)
    } else {
        stringResource(Res.string.task_detail_send_hint)
    }

@Composable
private fun TaskDetailFailureReason.label(): String =
    when (this) {
        TaskDetailFailureReason.MessageSendFailed -> stringResource(Res.string.task_detail_message_failed)
        TaskDetailFailureReason.RequirementMutationFailed ->
            stringResource(Res.string.task_detail_operation_requirement_failed)
        TaskDetailFailureReason.SelectionConflict -> stringResource(Res.string.task_detail_operation_selection_conflict)
        TaskDetailFailureReason.SelectionFailed -> stringResource(Res.string.task_detail_operation_selection_failed)
    }

@Composable
private fun PlanDirection.label(): String =
    when (this) {
        PlanDirection.BestMatch -> "最合适"
        PlanDirection.MoreRelaxed -> "更轻松"
        PlanDirection.NewExperience -> "新体验"
    }

@Composable
private fun RequirementKind.label(): String =
    when (this) {
        RequirementKind.TimeWindow -> stringResource(Res.string.task_requirement_time_window)
        RequirementKind.BudgetLimit -> stringResource(Res.string.task_requirement_budget_limit)
        RequirementKind.CommuteLimit -> stringResource(Res.string.task_requirement_commute_limit)
        RequirementKind.CommutePreference -> stringResource(Res.string.task_requirement_commute_preference)
        RequirementKind.Location -> stringResource(Res.string.task_requirement_location)
        RequirementKind.ActivityDomain -> stringResource(Res.string.task_requirement_activity_domain)
        RequirementKind.ActivityMode -> stringResource(Res.string.task_requirement_activity_mode)
        RequirementKind.Topic -> stringResource(Res.string.task_requirement_topic)
        RequirementKind.ExperiencePreference -> stringResource(Res.string.task_requirement_experience_preference)
    }

@Composable
private fun RequirementValue.label(): String =
    when (this) {
        is RequirementValue.TimeWindow -> originalText
        is RequirementValue.BudgetLimit -> listOfNotNull(wholeUnits.toString(), currencyCode).joinToString(" ")
        is RequirementValue.CommuteLimit -> stringResource(Res.string.task_requirement_commute_minutes, maxMinutes)
        is RequirementValue.CommutePreference -> stringResource(Res.string.task_requirement_value_prefer_shorter)
        is RequirementValue.ActivityMode ->
            when (value) {
                ActivityModeValue.AtHome -> stringResource(Res.string.task_requirement_value_at_home)
                ActivityModeValue.OutOfHome -> stringResource(Res.string.task_requirement_value_out_of_home)
            }
        is RequirementValue.Text -> value
    }

@Composable
private fun RequirementStrength.label(): String =
    when (this) {
        RequirementStrength.Must -> stringResource(Res.string.task_requirement_must)
        RequirementStrength.Prefer -> stringResource(Res.string.task_requirement_prefer)
    }

@Composable
private fun RequirementSource.label(): String =
    when (this) {
        RequirementSource.UserExplicit -> stringResource(Res.string.task_requirement_source_user_explicit)
        RequirementSource.SystemDerived -> stringResource(Res.string.task_requirement_source_system_derived)
    }

@Composable
private fun ActiveResponseStatus.label(): String =
    when (this) {
        ActiveResponseStatus.Queued,
        ActiveResponseStatus.Thinking,
        -> stringResource(Res.string.task_detail_response_processing)
        ActiveResponseStatus.Streaming -> stringResource(Res.string.task_detail_response_processing)
        ActiveResponseStatus.Failed -> stringResource(Res.string.task_detail_response_failed)
        ActiveResponseStatus.Cancelled -> stringResource(Res.string.task_detail_response_cancelled)
        ActiveResponseStatus.TimedOut -> stringResource(Res.string.task_detail_response_timed_out)
    }

@Composable
private fun ResponseRunActivityKind.label(): String =
    when (this) {
        ResponseRunActivityKind.Thinking -> stringResource(Res.string.task_detail_response_processing)
        ResponseRunActivityKind.Weather -> stringResource(Res.string.task_detail_response_tool_weather)
        ResponseRunActivityKind.PlaceSearch -> stringResource(Res.string.task_detail_response_tool_place)
        ResponseRunActivityKind.Route -> stringResource(Res.string.task_detail_response_tool_route)
        ResponseRunActivityKind.Movie -> stringResource(Res.string.task_detail_response_tool_movie)
        ResponseRunActivityKind.Sports -> stringResource(Res.string.task_detail_response_tool_sports)
        ResponseRunActivityKind.Music -> stringResource(Res.string.task_detail_response_tool_music)
        ResponseRunActivityKind.Web -> stringResource(Res.string.task_detail_response_tool_web)
        ResponseRunActivityKind.OtherResearch -> stringResource(Res.string.task_detail_response_tool_web)
    }
