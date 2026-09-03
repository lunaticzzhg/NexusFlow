package com.nexusflow.app.feature.task.data

import com.nexusflow.app.feature.task.domain.ActivityModeValue
import com.nexusflow.app.feature.task.domain.CommutePreferenceValue
import com.nexusflow.app.feature.task.domain.MessageRole
import com.nexusflow.app.feature.task.domain.PlanDirection
import com.nexusflow.app.feature.task.domain.PlanEstimatedCost
import com.nexusflow.app.feature.task.domain.PlanId
import com.nexusflow.app.feature.task.domain.PlanSourceRef
import com.nexusflow.app.feature.task.domain.PlanTimelineItem
import com.nexusflow.app.feature.task.domain.PlanningState
import com.nexusflow.app.feature.task.domain.RequirementEvaluation
import com.nexusflow.app.feature.task.domain.RequirementEvaluationResult
import com.nexusflow.app.feature.task.domain.RequirementId
import com.nexusflow.app.feature.task.domain.RequirementKind
import com.nexusflow.app.feature.task.domain.RequirementSource
import com.nexusflow.app.feature.task.domain.RequirementStrength
import com.nexusflow.app.feature.task.domain.RequirementSummary
import com.nexusflow.app.feature.task.domain.RequirementValue
import com.nexusflow.app.feature.task.domain.TaskDetail
import com.nexusflow.app.feature.task.domain.TaskId
import com.nexusflow.app.feature.task.domain.TaskMessage
import com.nexusflow.app.feature.task.domain.TaskPlan
import com.nexusflow.app.feature.task.domain.TaskRequirement
import com.nexusflow.app.feature.task.domain.TaskSummary
import com.nexusflow.contracts.api.PlanEstimatedCostResponse
import com.nexusflow.contracts.api.PlanResponse
import com.nexusflow.contracts.api.PlanSourceRefResponse
import com.nexusflow.contracts.api.PlanTimelineItemResponse
import com.nexusflow.contracts.api.PlanningStatus
import com.nexusflow.contracts.api.RequirementEvaluationResponse
import com.nexusflow.contracts.api.RequirementResponse
import com.nexusflow.contracts.api.RequirementSummaryResponse
import com.nexusflow.contracts.api.RequirementValueResponse
import com.nexusflow.contracts.api.TaskDetailResponse
import com.nexusflow.contracts.api.TaskMessageResponse
import com.nexusflow.contracts.api.TaskSummaryResponse
import com.nexusflow.contracts.api.RequirementKind as ContractRequirementKind

internal fun TaskSummaryResponse.toDomain(): TaskSummary =
    TaskSummary(
        id = TaskId(id),
        intent = intent,
        requirements = requirements.map { it.toDomain() },
        selectedPlanId = selectedPlanId?.let(::PlanId),
    )

internal fun TaskDetailResponse.toDomain(): TaskDetail =
    TaskDetail(
        id = TaskId(task.id),
        intent = task.intent,
        revision = task.revision,
        requirements = requirements.map { it.toDomain() },
        messages = messages.map { it.toDomain() },
        plans = plans.map { it.toDomain() },
        selectedPlanId = task.selectedPlanId?.let(::PlanId),
        planningState = planning.status.toDomain(),
    )

private fun RequirementSummaryResponse.toDomain(): RequirementSummary =
    RequirementSummary(
        id = RequirementId(id),
        label = label,
        strength = strength.toDomain(),
    )

private fun RequirementResponse.toDomain(): TaskRequirement =
    TaskRequirement(
        id = RequirementId(id),
        kind = kind.toDomain(),
        value = value.toDomain(),
        strength = strength.toDomain(),
        source = source.toDomain(),
    )

internal fun RequirementKind.toContract(): ContractRequirementKind =
    when (this) {
        RequirementKind.TimeWindow -> ContractRequirementKind.TimeWindow
        RequirementKind.BudgetLimit -> ContractRequirementKind.BudgetLimit
        RequirementKind.CommuteLimit -> ContractRequirementKind.CommuteLimit
        RequirementKind.CommutePreference -> ContractRequirementKind.CommutePreference
        RequirementKind.Location -> ContractRequirementKind.Location
        RequirementKind.ActivityDomain -> ContractRequirementKind.ActivityDomain
        RequirementKind.ActivityMode -> ContractRequirementKind.ActivityMode
        RequirementKind.Topic -> ContractRequirementKind.Topic
        RequirementKind.ExperiencePreference -> ContractRequirementKind.ExperiencePreference
    }

private fun ContractRequirementKind.toDomain(): RequirementKind =
    when (this) {
        ContractRequirementKind.TimeWindow -> RequirementKind.TimeWindow
        ContractRequirementKind.BudgetLimit -> RequirementKind.BudgetLimit
        ContractRequirementKind.CommuteLimit -> RequirementKind.CommuteLimit
        ContractRequirementKind.CommutePreference -> RequirementKind.CommutePreference
        ContractRequirementKind.Location -> RequirementKind.Location
        ContractRequirementKind.ActivityDomain -> RequirementKind.ActivityDomain
        ContractRequirementKind.ActivityMode -> RequirementKind.ActivityMode
        ContractRequirementKind.Topic -> RequirementKind.Topic
        ContractRequirementKind.ExperiencePreference -> RequirementKind.ExperiencePreference
    }

internal fun RequirementValue.toContract(kind: RequirementKind): RequirementValueResponse =
    when (this) {
        is RequirementValue.TimeWindow ->
            RequirementValueResponse.TimeWindow(
                originalText = originalText,
                timeZoneId = timeZoneId,
            )
        is RequirementValue.BudgetLimit -> RequirementValueResponse.BudgetLimit(wholeUnits = wholeUnits, currencyCode = currencyCode)
        is RequirementValue.CommuteLimit -> RequirementValueResponse.CommuteLimit(maxMinutes = maxMinutes)
        is RequirementValue.CommutePreference -> RequirementValueResponse.CommutePreference(value.toContract())
        is RequirementValue.ActivityMode -> RequirementValueResponse.ActivityMode(value.toContract())
        is RequirementValue.Text ->
            when (kind) {
                RequirementKind.Location -> RequirementValueResponse.Location(value)
                RequirementKind.ActivityDomain -> RequirementValueResponse.ActivityDomain(value)
                RequirementKind.Topic -> RequirementValueResponse.Topic(value)
                RequirementKind.ExperiencePreference -> RequirementValueResponse.ExperiencePreference(value)
                RequirementKind.TimeWindow,
                RequirementKind.BudgetLimit,
                RequirementKind.CommuteLimit,
                RequirementKind.CommutePreference,
                RequirementKind.ActivityMode,
                -> RequirementValueResponse.ExperiencePreference(value)
            }
    }

private fun RequirementValueResponse.toDomain(): RequirementValue =
    when (this) {
        is RequirementValueResponse.TimeWindow ->
            RequirementValue.TimeWindow(originalText = originalText, timeZoneId = timeZoneId)
        is RequirementValueResponse.BudgetLimit -> RequirementValue.BudgetLimit(wholeUnits = wholeUnits, currencyCode = currencyCode)
        is RequirementValueResponse.CommuteLimit -> RequirementValue.CommuteLimit(maxMinutes = maxMinutes)
        is RequirementValueResponse.CommutePreference -> RequirementValue.CommutePreference(value.toDomain())
        is RequirementValueResponse.ActivityMode -> RequirementValue.ActivityMode(value.toDomain())
        is RequirementValueResponse.Location -> RequirementValue.Text(text)
        is RequirementValueResponse.ActivityDomain -> RequirementValue.Text(value)
        is RequirementValueResponse.Topic -> RequirementValue.Text(text)
        is RequirementValueResponse.ExperiencePreference -> RequirementValue.Text(text)
    }

private fun CommutePreferenceValue.toContract(): com.nexusflow.contracts.api.CommutePreferenceValue =
    when (this) {
        CommutePreferenceValue.PreferShorter -> com.nexusflow.contracts.api.CommutePreferenceValue.PreferShorter
    }

private fun com.nexusflow.contracts.api.CommutePreferenceValue.toDomain(): CommutePreferenceValue =
    when (this) {
        com.nexusflow.contracts.api.CommutePreferenceValue.PreferShorter -> CommutePreferenceValue.PreferShorter
    }

private fun ActivityModeValue.toContract(): com.nexusflow.contracts.api.ActivityModeValue =
    when (this) {
        ActivityModeValue.AtHome -> com.nexusflow.contracts.api.ActivityModeValue.AtHome
        ActivityModeValue.OutOfHome -> com.nexusflow.contracts.api.ActivityModeValue.OutOfHome
    }

private fun com.nexusflow.contracts.api.ActivityModeValue.toDomain(): ActivityModeValue =
    when (this) {
        com.nexusflow.contracts.api.ActivityModeValue.AtHome -> ActivityModeValue.AtHome
        com.nexusflow.contracts.api.ActivityModeValue.OutOfHome -> ActivityModeValue.OutOfHome
    }

internal fun RequirementStrength.toContract(): com.nexusflow.contracts.api.RequirementStrength =
    when (this) {
        RequirementStrength.Must -> com.nexusflow.contracts.api.RequirementStrength.Must
        RequirementStrength.Prefer -> com.nexusflow.contracts.api.RequirementStrength.Prefer
    }

private fun com.nexusflow.contracts.api.RequirementStrength.toDomain(): RequirementStrength =
    when (this) {
        com.nexusflow.contracts.api.RequirementStrength.Must -> RequirementStrength.Must
        com.nexusflow.contracts.api.RequirementStrength.Prefer -> RequirementStrength.Prefer
    }

private fun com.nexusflow.contracts.api.RequirementSource.toDomain(): RequirementSource =
    when (this) {
        com.nexusflow.contracts.api.RequirementSource.UserExplicit -> RequirementSource.UserExplicit
        com.nexusflow.contracts.api.RequirementSource.SystemDerived -> RequirementSource.SystemDerived
    }

private fun TaskMessageResponse.toDomain(): TaskMessage =
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
        revision = revision,
        direction = direction.toDomain(),
        title = title,
        summary = summary,
        timeline = timeline.map { it.toDomain() },
        estimatedCost = estimatedCost?.toDomain(),
        commuteMinutes = commuteMinutes,
        requirementEvaluations = requirementEvaluations.map { it.toDomain() },
        tradeoffs = tradeoffs,
        reasons = reasons,
        sourceRefs = sourceRefs.map { it.toDomain() },
        opportunityRefs = opportunityRefs,
        validUntil = validUntil,
    )

private fun com.nexusflow.contracts.api.PlanDirection.toDomain(): PlanDirection =
    when (this) {
        com.nexusflow.contracts.api.PlanDirection.BestMatch -> PlanDirection.BestMatch
        com.nexusflow.contracts.api.PlanDirection.MoreRelaxed -> PlanDirection.MoreRelaxed
        com.nexusflow.contracts.api.PlanDirection.NewExperience -> PlanDirection.NewExperience
    }

private fun PlanTimelineItemResponse.toDomain(): PlanTimelineItem =
    PlanTimelineItem(
        title = title,
        startAt = startAt,
        endAt = endAt,
        location = location,
    )

private fun PlanEstimatedCostResponse.toDomain(): PlanEstimatedCost =
    PlanEstimatedCost(wholeUnits = wholeUnits, currencyCode = currencyCode)

private fun RequirementEvaluationResponse.toDomain(): RequirementEvaluation =
    RequirementEvaluation(
        requirementId = RequirementId(requirementId),
        result =
            when (result) {
                com.nexusflow.contracts.api.RequirementEvaluationResult.Satisfied -> RequirementEvaluationResult.Satisfied
                com.nexusflow.contracts.api.RequirementEvaluationResult.NotApplicable -> RequirementEvaluationResult.NotApplicable
            },
        explanation = explanation,
    )

private fun PlanSourceRefResponse.toDomain(): PlanSourceRef =
    PlanSourceRef(
        label = label,
        sourceUpdatedAt = sourceUpdatedAt,
        uri = uri,
    )

private fun PlanningStatus.toDomain(): PlanningState =
    when (this) {
        PlanningStatus.Idle -> PlanningState.Idle
    }
