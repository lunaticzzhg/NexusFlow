package com.nexusflow.backend.feature.conversation.application

import com.nexusflow.backend.feature.conversation.domain.ConversationDetail
import com.nexusflow.backend.feature.conversation.domain.ConversationMessage
import com.nexusflow.backend.feature.responserun.domain.DurationFactPayload
import com.nexusflow.backend.feature.responserun.domain.FactValuePayload
import com.nexusflow.backend.feature.responserun.domain.LocationFactPayload
import com.nexusflow.backend.feature.responserun.domain.MoneyFactPayload
import com.nexusflow.backend.feature.responserun.domain.OpportunityFactsPayload
import com.nexusflow.backend.feature.responserun.domain.OpportunityPayload
import com.nexusflow.backend.feature.responserun.domain.PlanEstimatedCostPayload
import com.nexusflow.backend.feature.responserun.domain.PlanPayload
import com.nexusflow.backend.feature.responserun.domain.PlanSourceRefPayload
import com.nexusflow.backend.feature.responserun.domain.PlanTimelineItemPayload
import com.nexusflow.backend.feature.responserun.domain.ResponseRunResultPayload
import com.nexusflow.backend.feature.responserun.domain.RequirementEvaluationPayload
import com.nexusflow.backend.feature.responserun.domain.RequirementValuePayload
import com.nexusflow.backend.feature.responserun.domain.RequirementWritePayload
import com.nexusflow.backend.feature.responserun.domain.SourceRefPayload
import com.nexusflow.backend.feature.conversation.application.answer.ConversationAnswerOutcome
import com.nexusflow.backend.feature.conversation.application.answer.ConversationAnswerResult
import com.nexusflow.backend.feature.task.domain.ActivityModeValue
import com.nexusflow.backend.feature.task.domain.CommutePreferenceValue
import com.nexusflow.backend.feature.task.domain.FactValue
import com.nexusflow.backend.feature.task.domain.Opportunity
import com.nexusflow.backend.feature.task.domain.Plan
import com.nexusflow.backend.feature.task.domain.Requirement
import com.nexusflow.backend.feature.task.domain.RequirementKind
import com.nexusflow.backend.feature.task.domain.RequirementStrength
import com.nexusflow.backend.feature.task.domain.RequirementValue
import com.nexusflow.backend.feature.task.domain.TaskDetail
import com.nexusflow.contracts.backendai.common.StructuredModelRequestDiagnostics
import com.nexusflow.contracts.backendai.understanding.ActivePlanningContextPayload as AiActivePlanningContextPayload
import com.nexusflow.contracts.backendai.understanding.ActivityModeValue as AiActivityModeValue
import com.nexusflow.contracts.backendai.understanding.CommutePreferenceValue as AiCommutePreferenceValue
import com.nexusflow.contracts.backendai.understanding.ConstraintDeltaOperation
import com.nexusflow.contracts.backendai.understanding.ConstraintDeltaProposal
import com.nexusflow.contracts.backendai.understanding.CurrentRequirement as AiCurrentRequirement
import com.nexusflow.contracts.backendai.understanding.RequirementKind as AiRequirementKind
import com.nexusflow.contracts.backendai.understanding.RequirementStrength as AiRequirementStrength
import com.nexusflow.contracts.backendai.understanding.RequirementValue as AiRequirementValue
import com.nexusflow.contracts.backendai.understanding.UnderstandMessageRequest as AiUnderstandMessageRequest
import com.nexusflow.contracts.backendai.understanding.UnderstandMessageResult as AiUnderstandMessageResult
import kotlinx.datetime.Instant as ContractInstant
import java.time.Clock
import java.util.UUID

internal class ConversationTurnPayloadMapper(
    private val clock: Clock,
    private val uuidFactory: () -> UUID,
) {
    fun conversationAnswerPayload(
        result: ConversationAnswerResult,
        detail: ConversationDetail,
        userMessage: ConversationMessage,
    ): ResponseRunResultPayload.ConversationAnswer =
        ResponseRunResultPayload.ConversationAnswer(
            conversationId = detail.conversation.id.value.toString(),
            userMessageId = userMessage.id.value.toString(),
            aiRequestId = userMessage.aiRequestId ?: "",
            assistantMessageId = result.assistantMessage.id.value.toString(),
            text = result.assistantMessage.text,
            terminalKind = result.outcome.toTerminalKind().name,
        )

    fun planningUnderstandingPayload(
        detail: ConversationDetail,
        userMessage: ConversationMessage,
        currentTask: TaskDetail?,
        understanding: AiUnderstandMessageResult,
        timeZoneId: String,
    ): ResponseRunResultPayload.PlanningUnderstanding =
        ResponseRunResultPayload.PlanningUnderstanding(
            conversationId = detail.conversation.id.value.toString(),
            userMessageId = userMessage.id.value.toString(),
            aiRequestId = userMessage.aiRequestId ?: "",
            taskId = currentTask?.task?.id?.value?.toString(),
            taskCreationRequestId = "conversation:${detail.conversation.id.value}:${userMessage.id.value}",
            intent = understanding.planningGoalPatch?.trim()?.takeIf(String::isNotBlank) ?: currentTask?.task?.intent ?: userMessage.content,
            expectedTaskRevision = currentTask?.task?.revision,
            intentPatch = understanding.planningGoalPatch,
            requirements = understanding.constraintDeltas.mapNotNull { delta ->
                delta.toRequirementWritePayloadOrNull(timeZoneId)
            },
            removedRequirementKinds = understanding.constraintDeltas
                .filter { it.operation == ConstraintDeltaOperation.Remove }
                .map { it.kind.toBackendRequirementKind().name },
            selectedTaskContextKeys = understanding.contextSelection.selectedKeys,
            clarificationText = understanding.clarification.questionDraft?.takeIf { understanding.clarification.needed },
            planningRequested = !understanding.clarification.needed,
        )

    fun planningResultPayload(
        detail: ConversationDetail,
        userMessage: ConversationMessage,
        taskDetail: TaskDetail,
        opportunities: List<Opportunity>,
        plans: List<Plan>,
        outcome: String,
    ): ResponseRunResultPayload.PlanningResult =
        ResponseRunResultPayload.PlanningResult(
            conversationId = detail.conversation.id.value.toString(),
            userMessageId = userMessage.id.value.toString(),
            aiRequestId = userMessage.aiRequestId ?: "",
            taskId = taskDetail.task.id.value.toString(),
            expectedTaskRevision = taskDetail.task.revision,
            outcome = outcome,
            opportunities = opportunities.map { it.toPayload() },
            plans = plans.map { it.toPayload() },
        )

    fun aiContext(
        detail: ConversationDetail,
        userMessage: ConversationMessage,
        currentTask: TaskDetail?,
        referenceTime: java.time.Instant,
        timeZoneId: String,
    ): AiUnderstandMessageRequest =
        AiUnderstandMessageRequest(
            aiRequestId = userMessage.aiRequestId ?: "",
            currentMessage = userMessage.content,
            referenceTime = referenceTime.toContractInstant(),
            timeZoneId = timeZoneId,
            activePlanning = currentTask?.let(::activePlanningPayload),
            optionalContext = emptyList(),
            availableContextDefinitions = emptyList(),
            diagnostics = StructuredModelRequestDiagnostics(),
        )

    internal fun activePlanningPayload(detail: TaskDetail): AiActivePlanningContextPayload =
        AiActivePlanningContextPayload(
            taskId = detail.task.id.value.toString(),
            taskRevision = detail.task.revision,
            goal = detail.task.intent,
            requirements = detail.requirements.map { it.toAiCurrentRequirement() },
        )

    private fun Requirement.toAiCurrentRequirement(): AiCurrentRequirement =
        AiCurrentRequirement(
            kind = kind.toAiRequirementKind(),
            value = value.toAiRequirementValue(),
            strength = strength.toAiRequirementStrength(),
        )

    private fun RequirementKind.toAiRequirementKind(): AiRequirementKind =
        when (this) {
            RequirementKind.TimeWindow -> AiRequirementKind.TimeWindow
            RequirementKind.BudgetLimit -> AiRequirementKind.BudgetLimit
            RequirementKind.CommuteLimit -> AiRequirementKind.CommuteLimit
            RequirementKind.CommutePreference -> AiRequirementKind.CommutePreference
            RequirementKind.Location -> AiRequirementKind.Location
            RequirementKind.ActivityDomain -> AiRequirementKind.ActivityDomain
            RequirementKind.ActivityMode -> AiRequirementKind.ActivityMode
            RequirementKind.Topic -> AiRequirementKind.Topic
            RequirementKind.ExperiencePreference -> AiRequirementKind.ExperiencePreference
        }

    private fun RequirementValue.toAiRequirementValue(): AiRequirementValue =
        when (this) {
            is RequirementValue.TimeWindow -> AiRequirementValue.TimeWindow(
                startAt = startAt?.toContractInstant(),
                endAt = endAt?.toContractInstant(),
                timeZoneId = timeZoneId,
                originalText = originalText,
            )
            is RequirementValue.BudgetLimit -> AiRequirementValue.BudgetLimit(wholeUnits = wholeUnits, currencyCode = currencyCode)
            is RequirementValue.CommuteLimit -> AiRequirementValue.CommuteLimit(maxMinutes = maxMinutes)
            is RequirementValue.CommutePreference -> AiRequirementValue.CommutePreference(value.toAiCommutePreferenceValue())
            is RequirementValue.Location -> AiRequirementValue.Location(text = text)
            is RequirementValue.ActivityDomain -> AiRequirementValue.ActivityDomain(value = value)
            is RequirementValue.ActivityMode -> AiRequirementValue.ActivityMode(value.toAiActivityModeValue())
            is RequirementValue.Topic -> AiRequirementValue.Topic(text = text)
            is RequirementValue.ExperiencePreference -> AiRequirementValue.ExperiencePreference(text = text)
        }

    private fun RequirementStrength.toAiRequirementStrength(): AiRequirementStrength =
        when (this) {
            RequirementStrength.Must -> AiRequirementStrength.Must
            RequirementStrength.Prefer -> AiRequirementStrength.Prefer
        }

    private fun ConstraintDeltaProposal.toRequirementWritePayloadOrNull(timeZoneId: String): RequirementWritePayload? {
        if (operation != ConstraintDeltaOperation.Upsert) return null
        val proposedValue = value ?: return null
        val proposedStrength = strength ?: return null
        return RequirementWritePayload(
            id = uuidFactory().toString(),
            kind = kind.toBackendRequirementKind().name,
            value = proposedValue.toRequirementValuePayload(timeZoneId),
            strength = proposedStrength.toBackendRequirementStrength().name,
        )
    }

    private fun AiRequirementKind.toBackendRequirementKind(): RequirementKind =
        when (this) {
            AiRequirementKind.TimeWindow -> RequirementKind.TimeWindow
            AiRequirementKind.BudgetLimit -> RequirementKind.BudgetLimit
            AiRequirementKind.CommuteLimit -> RequirementKind.CommuteLimit
            AiRequirementKind.CommutePreference -> RequirementKind.CommutePreference
            AiRequirementKind.Location -> RequirementKind.Location
            AiRequirementKind.ActivityDomain -> RequirementKind.ActivityDomain
            AiRequirementKind.ActivityMode -> RequirementKind.ActivityMode
            AiRequirementKind.Topic -> RequirementKind.Topic
            AiRequirementKind.ExperiencePreference -> RequirementKind.ExperiencePreference
        }

    private fun AiRequirementStrength.toBackendRequirementStrength(): RequirementStrength =
        when (this) {
            AiRequirementStrength.Must -> RequirementStrength.Must
            AiRequirementStrength.Prefer -> RequirementStrength.Prefer
        }

    private fun AiRequirementValue.toRequirementValuePayload(timeZoneId: String): RequirementValuePayload =
        when (this) {
            is AiRequirementValue.TimeWindow -> RequirementValuePayload.TimeWindow(
                startAt = startAt?.toString(),
                endAt = endAt?.toString(),
                timeZoneId = timeZoneId,
                originalText = originalText,
            )
            is AiRequirementValue.BudgetLimit -> RequirementValuePayload.BudgetLimit(wholeUnits, currencyCode)
            is AiRequirementValue.CommuteLimit -> RequirementValuePayload.CommuteLimit(maxMinutes)
            is AiRequirementValue.CommutePreference -> RequirementValuePayload.CommutePreference(value.name)
            is AiRequirementValue.Location -> RequirementValuePayload.Location(text)
            is AiRequirementValue.ActivityDomain -> RequirementValuePayload.ActivityDomain(value)
            is AiRequirementValue.ActivityMode -> RequirementValuePayload.ActivityMode(value.name)
            is AiRequirementValue.Topic -> RequirementValuePayload.Topic(text)
            is AiRequirementValue.ExperiencePreference -> RequirementValuePayload.ExperiencePreference(text)
        }

    private fun Opportunity.toPayload(): OpportunityPayload =
        OpportunityPayload(
            id = id.value.toString(),
            provider = provider,
            externalKey = externalKey,
            kind = kind.name,
            title = title,
            facts = OpportunityFactsPayload(
                summary = facts.summary,
                startTime = facts.startTime?.toString(),
                endTime = facts.endTime?.toString(),
                location = facts.location?.let { LocationFactPayload(it.displayName, it.normalizedName) },
                activityMode = facts.activityMode?.name,
                price = facts.price?.let { MoneyFactPayload(it.wholeUnits, it.currencyCode) },
                commute = facts.commute?.let { DurationFactPayload(it.minutes) },
                availability = facts.availability?.name,
                attributes = facts.attributes.mapValues { (_, value) -> value.toPayload() },
            ),
            sources = sources.map { source ->
                SourceRefPayload(
                    label = source.label,
                    uri = source.uri,
                    sourceUpdatedAt = source.sourceUpdatedAt?.toString(),
                    sourceId = source.sourceId,
                    authority = source.authority.name,
                    factKeys = source.factKeys.map { it.name }.sorted(),
                )
            },
            observedAt = observedAt.toString(),
            validUntil = validUntil?.toString(),
        )

    private fun FactValue.toPayload(): FactValuePayload =
        when (this) {
            is FactValue.Text -> FactValuePayload.Text(value)
            is FactValue.Number -> FactValuePayload.Number(value)
            is FactValue.Flag -> FactValuePayload.Flag(value)
        }

    private fun Plan.toPayload(): PlanPayload =
        PlanPayload(
            id = id.value.toString(),
            taskId = taskId.value.toString(),
            revision = revision,
            direction = direction.name,
            title = title,
            summary = summary,
            timeline = timeline.map { item ->
                PlanTimelineItemPayload(
                    title = item.title,
                    startAt = item.startAt?.toString(),
                    endAt = item.endAt?.toString(),
                    location = item.location,
                )
            },
            estimatedCost = estimatedCost?.let { PlanEstimatedCostPayload(it.wholeUnits, it.currencyCode) },
            commuteMinutes = commuteMinutes,
            requirementEvaluations = requirementEvaluations.map { evaluation ->
                RequirementEvaluationPayload(
                    requirementId = evaluation.requirementId.value.toString(),
                    result = evaluation.result.name,
                    explanation = evaluation.explanation,
                )
            },
            tradeoffs = tradeoffs,
            reasons = reasons,
            sourceRefs = sourceRefs.map { source ->
                PlanSourceRefPayload(
                    label = source.label,
                    uri = source.uri,
                    sourceUpdatedAt = source.sourceUpdatedAt?.toString(),
                )
            },
            opportunityRefs = opportunityRefs.map { it.value.toString() },
            validUntil = validUntil?.toString(),
            createdAt = createdAt.toString(),
        )

    private fun ConversationAnswerOutcome.toTerminalKind(): ConversationTurnTerminalKind =
        when (this) {
            ConversationAnswerOutcome.Answered -> ConversationTurnTerminalKind.AssistantMessage
            ConversationAnswerOutcome.Clarification -> ConversationTurnTerminalKind.Clarification
            ConversationAnswerOutcome.ExternalUnavailable,
            ConversationAnswerOutcome.CapabilityUnavailable,
            ConversationAnswerOutcome.AiUnavailable,
            -> ConversationTurnTerminalKind.InformationUnavailable
            ConversationAnswerOutcome.InvalidAiResult,
            ConversationAnswerOutcome.InvalidToolRequest,
            -> ConversationTurnTerminalKind.InvalidTurn
        }

    private fun CommutePreferenceValue.toAiCommutePreferenceValue(): AiCommutePreferenceValue =
        when (this) {
            CommutePreferenceValue.PreferShorter -> AiCommutePreferenceValue.PreferShorter
        }

    private fun ActivityModeValue.toAiActivityModeValue(): AiActivityModeValue =
        when (this) {
            ActivityModeValue.AtHome -> AiActivityModeValue.AtHome
            ActivityModeValue.OutOfHome -> AiActivityModeValue.OutOfHome
        }

    private fun java.time.Instant.toContractInstant(): ContractInstant =
        ContractInstant.fromEpochSeconds(epochSecond, nano.toLong())
}
