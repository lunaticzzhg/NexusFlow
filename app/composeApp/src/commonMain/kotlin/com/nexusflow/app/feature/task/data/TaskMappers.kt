package com.nexusflow.app.feature.task.data

import com.nexusflow.app.feature.task.domain.ActivityModeValue
import com.nexusflow.app.feature.task.domain.CommutePreferenceValue
import com.nexusflow.app.feature.task.domain.ConversationDetail
import com.nexusflow.app.feature.task.domain.ConversationId
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
import com.nexusflow.app.feature.task.domain.ResponseRun
import com.nexusflow.app.feature.task.domain.ResponseRunActivity
import com.nexusflow.app.feature.task.domain.ResponseRunActivityKind
import com.nexusflow.app.feature.task.domain.ResponseRunFailureCategory
import com.nexusflow.app.feature.task.domain.ResponseRunId
import com.nexusflow.app.feature.task.domain.ResponseRunSnapshot
import com.nexusflow.app.feature.task.domain.ResponseRunStage
import com.nexusflow.app.feature.task.domain.ResponseRunStatus
import com.nexusflow.app.feature.task.domain.TaskDetail
import com.nexusflow.app.feature.task.domain.TaskId
import com.nexusflow.app.feature.task.domain.TaskMessage
import com.nexusflow.app.feature.task.domain.TaskPlan
import com.nexusflow.app.feature.task.domain.TaskRequirement
import com.nexusflow.app.feature.task.domain.TaskSummary
import com.nexusflow.contracts.appbackend.conversation.ConversationCurrentTaskResponse
import com.nexusflow.contracts.appbackend.conversation.ConversationDetailResponse
import com.nexusflow.contracts.appbackend.conversation.ConversationMessageResponse
import com.nexusflow.contracts.appbackend.conversation.CreateConversationResponse
import com.nexusflow.contracts.appbackend.conversation.ResponseRunActivityKindResponse
import com.nexusflow.contracts.appbackend.conversation.ResponseRunActivityResponse
import com.nexusflow.contracts.appbackend.conversation.ResponseRunFailureCategoryResponse
import com.nexusflow.contracts.appbackend.conversation.ResponseRunResponse
import com.nexusflow.contracts.appbackend.conversation.ResponseRunSnapshotResponse
import com.nexusflow.contracts.appbackend.conversation.ResponseRunStageResponse
import com.nexusflow.contracts.appbackend.conversation.ResponseRunStatusResponse
import com.nexusflow.contracts.appbackend.conversation.SendConversationMessageResponse
import com.nexusflow.contracts.appbackend.plan.PlanEstimatedCostResponse
import com.nexusflow.contracts.appbackend.plan.PlanResponse
import com.nexusflow.contracts.appbackend.plan.PlanSourceRefResponse
import com.nexusflow.contracts.appbackend.plan.PlanTimelineItemResponse
import com.nexusflow.contracts.appbackend.plan.RequirementEvaluationResponse
import com.nexusflow.contracts.appbackend.task.PlanningStatus
import com.nexusflow.contracts.appbackend.task.RequirementResponse
import com.nexusflow.contracts.appbackend.task.RequirementSummaryResponse
import com.nexusflow.contracts.appbackend.task.RequirementValueResponse
import com.nexusflow.contracts.appbackend.task.TaskDetailResponse
import com.nexusflow.contracts.appbackend.task.TaskSummaryResponse
import com.nexusflow.contracts.appbackend.plan.RequirementEvaluationResult as ContractRequirementEvaluationResult
import com.nexusflow.contracts.appbackend.task.RequirementKind as ContractRequirementKind

internal fun TaskSummaryResponse.toDomain(): TaskSummary =
    TaskSummary(
        id = TaskId(id),
        intent = intent,
        requirements = requirements.map { it.toDomain() },
        selectedPlanId = selectedPlanId?.let(::PlanId),
        conversationId = conversationId?.let(::ConversationId),
    )

internal fun TaskDetailResponse.toDomain(): TaskDetail =
    TaskDetail(
        id = TaskId(task.id),
        intent = task.intent,
        revision = task.revision,
        requirements = requirements.map { it.toDomain() },
        messages = emptyList(),
        plans = plans.map { it.toDomain() },
        selectedPlanId = task.selectedPlanId?.let(::PlanId),
        planningState = planning.status.toDomain(),
    )

internal fun CreateConversationResponse.toDomain(): ConversationDetail =
    ConversationDetail(
        id = ConversationId(conversation.id),
        messages = conversation.messages.map { it.toDomain() },
        responseRuns = conversation.responseRuns.map { it.toDomain() },
        currentTask = currentTask?.toDomain(),
    )

internal fun ConversationDetailResponse.toDomain(): ConversationDetail =
    ConversationDetail(
        id = ConversationId(conversation.id),
        messages = conversation.messages.map { it.toDomain() },
        responseRuns = conversation.responseRuns.map { it.toDomain() },
        currentTask = currentTask?.toDomain(),
    )

internal fun SendConversationMessageResponse.toDomain(): ConversationDetail =
    ConversationDetail(
        id = ConversationId(conversation.id),
        messages = conversation.messages.map { it.toDomain() },
        responseRuns = conversation.responseRuns.map { it.toDomain() },
        currentTask = currentTask?.toDomain(),
    )

internal fun ResponseRunSnapshotResponse.toDomain(): ResponseRunSnapshot =
    ResponseRunSnapshot(
        run = run.toDomain(),
        conversation =
            ConversationDetail(
                id = ConversationId(conversation.id),
                messages = conversation.messages.map { it.toDomain() },
                responseRuns = conversation.responseRuns.map { it.toDomain() },
                currentTask = currentTask?.toDomain(),
            ),
        streamAttempt = streamAttempt,
        lastSeq = lastSeq,
        partialText = partialText,
        activities = activities.map { it.toDomain() },
        realtimeSnapshotAvailable = realtimeSnapshotAvailable,
    )

private fun ConversationCurrentTaskResponse.toDomain(): TaskDetail =
    TaskDetail(
        id = TaskId(task.id),
        intent = task.intent,
        revision = task.revision,
        requirements = requirements.map { it.toDomain() },
        messages = emptyList(),
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

private fun CommutePreferenceValue.toContract(): com.nexusflow.contracts.appbackend.task.CommutePreferenceValue =
    when (this) {
        CommutePreferenceValue.PreferShorter -> com.nexusflow.contracts.appbackend.task.CommutePreferenceValue.PreferShorter
    }

private fun com.nexusflow.contracts.appbackend.task.CommutePreferenceValue.toDomain(): CommutePreferenceValue =
    when (this) {
        com.nexusflow.contracts.appbackend.task.CommutePreferenceValue.PreferShorter -> CommutePreferenceValue.PreferShorter
    }

private fun ActivityModeValue.toContract(): com.nexusflow.contracts.appbackend.task.ActivityModeValue =
    when (this) {
        ActivityModeValue.AtHome -> com.nexusflow.contracts.appbackend.task.ActivityModeValue.AtHome
        ActivityModeValue.OutOfHome -> com.nexusflow.contracts.appbackend.task.ActivityModeValue.OutOfHome
    }

private fun com.nexusflow.contracts.appbackend.task.ActivityModeValue.toDomain(): ActivityModeValue =
    when (this) {
        com.nexusflow.contracts.appbackend.task.ActivityModeValue.AtHome -> ActivityModeValue.AtHome
        com.nexusflow.contracts.appbackend.task.ActivityModeValue.OutOfHome -> ActivityModeValue.OutOfHome
    }

internal fun RequirementStrength.toContract(): com.nexusflow.contracts.appbackend.task.RequirementStrength =
    when (this) {
        RequirementStrength.Must -> com.nexusflow.contracts.appbackend.task.RequirementStrength.Must
        RequirementStrength.Prefer -> com.nexusflow.contracts.appbackend.task.RequirementStrength.Prefer
    }

private fun com.nexusflow.contracts.appbackend.task.RequirementStrength.toDomain(): RequirementStrength =
    when (this) {
        com.nexusflow.contracts.appbackend.task.RequirementStrength.Must -> RequirementStrength.Must
        com.nexusflow.contracts.appbackend.task.RequirementStrength.Prefer -> RequirementStrength.Prefer
    }

private fun com.nexusflow.contracts.appbackend.task.RequirementSource.toDomain(): RequirementSource =
    when (this) {
        com.nexusflow.contracts.appbackend.task.RequirementSource.UserExplicit -> RequirementSource.UserExplicit
        com.nexusflow.contracts.appbackend.task.RequirementSource.SystemDerived -> RequirementSource.SystemDerived
    }

private fun ConversationMessageResponse.toDomain(): TaskMessage =
    TaskMessage(
        id = id,
        role =
            when (role) {
                com.nexusflow.contracts.appbackend.task.MessageRole.User -> MessageRole.User
                com.nexusflow.contracts.appbackend.task.MessageRole.Assistant -> MessageRole.Assistant
            },
        content = content,
        clientMessageId = clientMessageId,
        turnIndex = turnIndex,
        understoodAt = understoodAt,
    )

internal fun ResponseRunResponse.toDomain(): ResponseRun =
    ResponseRun(
        id = ResponseRunId(id),
        userMessageId = userMessageId,
        turnIndex = turnIndex,
        status = status.toDomain(),
        stage = stage.toDomain(),
        attempt = attempt,
        retryable = retryable,
        assistantMessageId = assistantMessageId,
        failureCategory = failureCategory?.toDomain(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
    )

private fun ResponseRunStatusResponse.toDomain(): ResponseRunStatus =
    when (this) {
        ResponseRunStatusResponse.Queued -> ResponseRunStatus.Queued
        ResponseRunStatusResponse.Processing -> ResponseRunStatus.Processing
        ResponseRunStatusResponse.Streaming -> ResponseRunStatus.Streaming
        ResponseRunStatusResponse.Completed -> ResponseRunStatus.Completed
        ResponseRunStatusResponse.FailedRetryable -> ResponseRunStatus.FailedRetryable
        ResponseRunStatusResponse.Failed -> ResponseRunStatus.Failed
        ResponseRunStatusResponse.TimedOut -> ResponseRunStatus.TimedOut
        ResponseRunStatusResponse.Cancelled -> ResponseRunStatus.Cancelled
    }

private fun ResponseRunStageResponse.toDomain(): ResponseRunStage =
    when (this) {
        ResponseRunStageResponse.Turn -> ResponseRunStage.Turn
        ResponseRunStageResponse.Planning -> ResponseRunStage.Planning
    }

private fun ResponseRunFailureCategoryResponse.toDomain(): ResponseRunFailureCategory =
    when (this) {
        ResponseRunFailureCategoryResponse.ProviderTemporary -> ResponseRunFailureCategory.ProviderTemporary
        ResponseRunFailureCategoryResponse.AiInvalidResult -> ResponseRunFailureCategory.AiInvalidResult
        ResponseRunFailureCategoryResponse.WorkerLost -> ResponseRunFailureCategory.WorkerLost
        ResponseRunFailureCategoryResponse.RunTimeout -> ResponseRunFailureCategory.RunTimeout
        ResponseRunFailureCategoryResponse.InternalInvariant -> ResponseRunFailureCategory.InternalInvariant
    }

private fun ResponseRunActivityResponse.toDomain(): ResponseRunActivity =
    ResponseRunActivity(
        id = id,
        kind = kind.toDomain(),
        message = message,
        startedAt = startedAt,
        completedAt = completedAt,
        failed = failed,
    )

private fun ResponseRunActivityKindResponse.toDomain(): ResponseRunActivityKind =
    when (this) {
        ResponseRunActivityKindResponse.Thinking -> ResponseRunActivityKind.Thinking
        ResponseRunActivityKindResponse.Weather -> ResponseRunActivityKind.Weather
        ResponseRunActivityKindResponse.PlaceSearch -> ResponseRunActivityKind.PlaceSearch
        ResponseRunActivityKindResponse.Route -> ResponseRunActivityKind.Route
        ResponseRunActivityKindResponse.Movie -> ResponseRunActivityKind.Movie
        ResponseRunActivityKindResponse.Sports -> ResponseRunActivityKind.Sports
        ResponseRunActivityKindResponse.Music -> ResponseRunActivityKind.Music
        ResponseRunActivityKindResponse.Web -> ResponseRunActivityKind.Web
        ResponseRunActivityKindResponse.OtherResearch -> ResponseRunActivityKind.OtherResearch
    }

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

private fun com.nexusflow.contracts.appbackend.plan.PlanDirection.toDomain(): PlanDirection =
    when (this) {
        com.nexusflow.contracts.appbackend.plan.PlanDirection.BestMatch -> PlanDirection.BestMatch
        com.nexusflow.contracts.appbackend.plan.PlanDirection.MoreRelaxed -> PlanDirection.MoreRelaxed
        com.nexusflow.contracts.appbackend.plan.PlanDirection.NewExperience -> PlanDirection.NewExperience
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
                ContractRequirementEvaluationResult.Satisfied -> RequirementEvaluationResult.Satisfied
                ContractRequirementEvaluationResult.NotApplicable -> RequirementEvaluationResult.NotApplicable
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
        PlanningStatus.Ready -> PlanningState.Ready
        PlanningStatus.NoCandidates -> PlanningState.NoCandidates
        PlanningStatus.NoFeasiblePlan -> PlanningState.NoFeasiblePlan
        PlanningStatus.Unavailable -> PlanningState.Unavailable
    }
