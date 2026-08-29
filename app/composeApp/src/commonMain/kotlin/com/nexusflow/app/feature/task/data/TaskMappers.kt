package com.nexusflow.app.feature.task.data

import com.nexusflow.app.feature.task.domain.ConstraintId
import com.nexusflow.app.feature.task.domain.ConstraintKind
import com.nexusflow.app.feature.task.domain.ConstraintStrength
import com.nexusflow.app.feature.task.domain.ConstraintValue
import com.nexusflow.app.feature.task.domain.MessageRole
import com.nexusflow.app.feature.task.domain.PlanEstimatedCost
import com.nexusflow.app.feature.task.domain.PlanId
import com.nexusflow.app.feature.task.domain.PlanTimelineItem
import com.nexusflow.app.feature.task.domain.TaskConstraint
import com.nexusflow.app.feature.task.domain.TaskDetail
import com.nexusflow.app.feature.task.domain.TaskId
import com.nexusflow.app.feature.task.domain.TaskMessage
import com.nexusflow.app.feature.task.domain.TaskPlan
import com.nexusflow.app.feature.task.domain.TaskState
import com.nexusflow.app.feature.task.domain.TaskSummary
import com.nexusflow.contracts.api.ConstraintResponse
import com.nexusflow.contracts.api.ConstraintValueResponse
import com.nexusflow.contracts.api.ConversationMessageResponse
import com.nexusflow.contracts.api.PlanEstimatedCostResponse
import com.nexusflow.contracts.api.PlanResponse
import com.nexusflow.contracts.api.PlanTimelineItemResponse
import com.nexusflow.contracts.api.TaskDetailResponse
import com.nexusflow.contracts.api.TaskSummaryResponse

internal fun TaskSummaryResponse.toDomain(): TaskSummary =
    TaskSummary(
        id = TaskId(id),
        title = title,
        currentGoal = currentGoal,
        state = state.toDomain(),
    )

internal fun TaskDetailResponse.toDomain(): TaskDetail =
    TaskDetail(
        id = TaskId(id),
        title = title,
        currentGoal = currentGoal,
        state = state.toDomain(),
        version = version,
        constraints = constraints.map { it.toDomain() },
        messages = messages.map { it.toDomain() },
        plans = plans.map { it.toDomain() },
        selectedPlanId = selectedPlanId?.let(::PlanId),
    )

private fun com.nexusflow.contracts.api.TaskState.toDomain(): TaskState =
    when (this) {
        com.nexusflow.contracts.api.TaskState.Draft -> TaskState.Draft
        com.nexusflow.contracts.api.TaskState.CollectingConstraints -> TaskState.CollectingConstraints
        com.nexusflow.contracts.api.TaskState.Planning -> TaskState.Planning
        com.nexusflow.contracts.api.TaskState.WaitingForApproval -> TaskState.WaitingForApproval
        com.nexusflow.contracts.api.TaskState.Executing -> TaskState.Executing
        com.nexusflow.contracts.api.TaskState.NeedsAttention -> TaskState.NeedsAttention
        com.nexusflow.contracts.api.TaskState.Completed -> TaskState.Completed
        com.nexusflow.contracts.api.TaskState.Cancelled -> TaskState.Cancelled
    }

private fun ConstraintResponse.toDomain(): TaskConstraint =
    TaskConstraint(
        id = ConstraintId(id),
        kind = kind.toDomain(),
        value = value.toDomain(),
        strength = strength.toDomain(),
    )

private fun com.nexusflow.contracts.api.ConstraintKind.toDomain(): ConstraintKind =
    when (this) {
        com.nexusflow.contracts.api.ConstraintKind.TimeWindow -> ConstraintKind.TimeWindow
        com.nexusflow.contracts.api.ConstraintKind.BudgetLimit -> ConstraintKind.BudgetLimit
        com.nexusflow.contracts.api.ConstraintKind.CommuteLimit -> ConstraintKind.CommuteLimit
        com.nexusflow.contracts.api.ConstraintKind.Location -> ConstraintKind.Location
        com.nexusflow.contracts.api.ConstraintKind.ActivityDomain -> ConstraintKind.ActivityDomain
        com.nexusflow.contracts.api.ConstraintKind.Topic -> ConstraintKind.Topic
        com.nexusflow.contracts.api.ConstraintKind.ExperiencePreference -> ConstraintKind.ExperiencePreference
    }

private fun ConstraintValueResponse.toDomain(): ConstraintValue =
    when (this) {
        is ConstraintValueResponse.TimeWindow ->
            ConstraintValue.TimeWindow(
                originalText = originalText,
                timeZoneId = timeZoneId,
            )
        is ConstraintValueResponse.BudgetLimit -> ConstraintValue.BudgetLimit(wholeUnits = wholeUnits, currencyCode = currencyCode)
        is ConstraintValueResponse.CommuteLimit -> ConstraintValue.CommuteLimit(maxMinutes = maxMinutes)
        is ConstraintValueResponse.Location -> ConstraintValue.Text(text)
        is ConstraintValueResponse.ActivityDomain -> ConstraintValue.Text(value)
        is ConstraintValueResponse.Topic -> ConstraintValue.Text(text)
        is ConstraintValueResponse.ExperiencePreference -> ConstraintValue.Text(text)
    }

private fun com.nexusflow.contracts.api.ConstraintStrength.toDomain(): ConstraintStrength =
    when (this) {
        com.nexusflow.contracts.api.ConstraintStrength.Hard -> ConstraintStrength.Hard
        com.nexusflow.contracts.api.ConstraintStrength.Soft -> ConstraintStrength.Soft
    }

private fun ConversationMessageResponse.toDomain(): TaskMessage =
    TaskMessage(
        role =
            when (role) {
                com.nexusflow.contracts.api.MessageRole.User -> MessageRole.User
                com.nexusflow.contracts.api.MessageRole.Assistant -> MessageRole.Assistant
            },
        content = content,
    )

internal fun PlanResponse.toDomain(): TaskPlan =
    TaskPlan(
        id = PlanId(id),
        title = title,
        summary = summary,
        timeline = timeline.map { it.toDomain() },
        estimatedCost = estimatedCost?.toDomain(),
        commuteMinutes = commuteMinutes,
        tradeoffs = tradeoffs,
        reasons = reasons,
    )

private fun PlanTimelineItemResponse.toDomain(): PlanTimelineItem = PlanTimelineItem(title = title, location = location)

private fun PlanEstimatedCostResponse.toDomain(): PlanEstimatedCost =
    PlanEstimatedCost(wholeUnits = wholeUnits, currencyCode = currencyCode)
