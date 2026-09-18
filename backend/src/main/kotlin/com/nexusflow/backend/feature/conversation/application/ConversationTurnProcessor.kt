package com.nexusflow.backend.feature.conversation.application

import com.nexusflow.backend.core.identity.ActorContext
import com.nexusflow.backend.feature.conversation.domain.ClaimedResponseRun
import com.nexusflow.backend.feature.conversation.domain.ConversationDetail
import com.nexusflow.backend.feature.conversation.domain.ConversationMessage
import com.nexusflow.backend.feature.conversation.domain.ConversationRepository
import com.nexusflow.backend.feature.conversation.domain.DurationFactPayload
import com.nexusflow.backend.feature.conversation.domain.FactValuePayload
import com.nexusflow.backend.feature.conversation.domain.LocationFactPayload
import com.nexusflow.backend.feature.conversation.domain.MoneyFactPayload
import com.nexusflow.backend.feature.conversation.domain.OpportunityFactsPayload
import com.nexusflow.backend.feature.conversation.domain.OpportunityPayload
import com.nexusflow.backend.feature.conversation.domain.PlanEstimatedCostPayload
import com.nexusflow.backend.feature.conversation.domain.PlanPayload
import com.nexusflow.backend.feature.conversation.domain.PlanSourceRefPayload
import com.nexusflow.backend.feature.conversation.domain.PlanTimelineItemPayload
import com.nexusflow.backend.feature.conversation.domain.ResponseRunResultPayload
import com.nexusflow.backend.feature.conversation.domain.ResponseRunStage
import com.nexusflow.backend.feature.conversation.domain.RequirementEvaluationPayload
import com.nexusflow.backend.feature.conversation.domain.RequirementValuePayload
import com.nexusflow.backend.feature.conversation.domain.RequirementWritePayload
import com.nexusflow.backend.feature.conversation.domain.SourceRefPayload
import com.nexusflow.backend.feature.task.application.ConversationAnswerOutcome
import com.nexusflow.backend.feature.task.application.ConversationAnswerResult
import com.nexusflow.backend.feature.task.application.ConversationAnswerService
import com.nexusflow.backend.feature.task.application.PlanningService
import com.nexusflow.backend.feature.task.application.PlanningTrigger
import com.nexusflow.backend.feature.task.application.StandaloneConversationAnswerRequest
import com.nexusflow.backend.feature.task.application.TaskDependencyUnavailableException
import com.nexusflow.backend.feature.task.domain.ActivityModeValue
import com.nexusflow.backend.feature.task.domain.FactValue
import com.nexusflow.backend.feature.task.domain.CommutePreferenceValue
import com.nexusflow.backend.feature.task.domain.MessageId
import com.nexusflow.backend.feature.task.domain.MessageRole
import com.nexusflow.backend.feature.task.domain.Opportunity
import com.nexusflow.backend.feature.task.domain.Plan
import com.nexusflow.backend.feature.task.domain.Requirement
import com.nexusflow.backend.feature.task.domain.RequirementKind
import com.nexusflow.backend.feature.task.domain.RequirementStrength
import com.nexusflow.backend.feature.task.domain.RequirementValue
import com.nexusflow.backend.feature.task.domain.TaskId
import com.nexusflow.backend.feature.task.domain.TaskDetail
import com.nexusflow.backend.feature.task.domain.TaskRepository
import com.nexusflow.contracts.backendai.common.AiCapabilityException
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
import com.nexusflow.contracts.backendai.understanding.TurnIntent
import com.nexusflow.contracts.backendai.understanding.UnderstandMessageRequest as AiUnderstandMessageRequest
import com.nexusflow.contracts.backendai.understanding.UnderstandMessageResult as AiUnderstandMessageResult
import com.nexusflow.contracts.backendai.understanding.UserMessageUnderstanding
import com.nexusflow.observability.StructuredLogger
import com.nexusflow.observability.logFields
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant as ContractInstant
import java.time.Clock
import java.util.UUID

class ConversationTurnProcessor(
    private val conversationRepository: ConversationRepository,
    private val taskRepository: TaskRepository,
    private val understanding: UserMessageUnderstanding? = null,
    private val conversationAnswerService: ConversationAnswerService? = null,
    private val planningService: PlanningService? = null,
    private val realtimeHub: ResponseRunRealtimeHub? = null,
    private val logger: StructuredLogger? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val uuidFactory: () -> UUID = UUID::randomUUID,
    private val timeZoneId: String = "UTC",
) {
    suspend fun process(claim: ClaimedResponseRun): ResponseRunResultPayload {
        val detail = conversationRepository.findConversationDetailForResponseRun(claim.run.id)
            ?: throw TaskDependencyUnavailableException("Conversation snapshot is unavailable")
        val userMessage = detail.messages.singleOrNull { it.id == claim.run.userMessageId && it.role == MessageRole.User }
            ?: throw TaskDependencyUnavailableException("Conversation user message is unavailable")
        val owner = detail.conversation.owner
        val actor = ActorContext(
            tenantId = owner.tenantId.value.toString(),
            userId = owner.userId.value.toString(),
            scopes = setOf(READ_SCOPE, WRITE_SCOPE),
        )
        if (claim.run.stage == ResponseRunStage.Planning) {
            val taskId = claim.run.expectedTaskId
                ?: throw TaskDependencyUnavailableException("Planning run is missing expected task id")
            val expectedRevision = claim.run.expectedTaskRevision
                ?: throw TaskDependencyUnavailableException("Planning run is missing expected task revision")
            val taskDetail = taskRepository.findTaskDetail(owner, taskId)
                ?: throw TaskDependencyUnavailableException("Planning task snapshot is unavailable")
            if (taskDetail.task.revision != expectedRevision) {
                return planningResultPayload(detail, userMessage, taskDetail, emptyList(), emptyList(), "Superseded")
            }
            val planning = planningService
                ?: throw TaskDependencyUnavailableException("Planning response run processing is not available")
            val computation = planning.computeIfReady(
                actor = actor,
                detail = taskDetail,
                trigger = PlanningTrigger.PlanningInputChanged,
                readToolObserver = claim.readToolObserver(),
            )
            return planningResultPayload(
                detail = detail,
                userMessage = userMessage,
                taskDetail = taskDetail,
                opportunities = computation.opportunities,
                plans = computation.plans,
                outcome = computation.outcome.name,
            )
        }
        val currentTask = taskRepository.findCurrentTaskForConversation(owner, detail.conversation.id)
        val understandingOutcome = understand(detail, userMessage, currentTask)
        return when (understandingOutcome) {
            null -> conversationAnswerResult(actor, detail, userMessage, currentTask, claim).toPayload(detail, userMessage)
            else -> when (understandingOutcome.turnIntent) {
                TurnIntent.Conversation -> conversationAnswerResult(actor, detail, userMessage, currentTask, claim).toPayload(
                    detail,
                    userMessage,
                )
                TurnIntent.Planning -> planningUnderstandingPayload(detail, userMessage, currentTask, understandingOutcome)
            }
        }
    }

    private suspend fun understand(
        detail: ConversationDetail,
        userMessage: ConversationMessage,
        currentTask: TaskDetail?,
    ): AiUnderstandMessageResult? {
        val capability = understanding ?: return null
        logUnderstandingStarted(detail, currentTask)
        val outcome = try {
            capability.understand(detail.toAiContext(userMessage, currentTask))
        } catch (error: CancellationException) {
            throw error
        } catch (_: AiCapabilityException) {
            return null
        }
        logUnderstandingFinished(detail, currentTask, outcome.turnIntent)
        return outcome
    }

    private suspend fun conversationAnswerResult(
        actor: ActorContext,
        detail: ConversationDetail,
        userMessage: ConversationMessage,
        currentTask: TaskDetail?,
        claim: ClaimedResponseRun,
    ): ConversationAnswerResult {
        var streamingStarted = false
        return conversationAnswerService?.answer(
            StandaloneConversationAnswerRequest(
                detail = detail,
                userMessage = userMessage,
                aiRequestId = userMessage.aiRequestId ?: "",
                timeZoneId = timeZoneId,
                referenceTime = clock.instant(),
                assistantMessageId = MessageId(uuidFactory()),
                actorTenantId = actor.tenantId,
                actorUserId = actor.userId,
                taskId = currentTask?.task?.id?.value?.toString(),
                taskRevision = currentTask?.task?.revision,
                readToolObserver = claim.readToolObserver(),
                onAnswerDelta = { delta ->
                    realtimeHub?.let { hub ->
                        if (!streamingStarted) {
                            hub.streamingStarted(claim.run)
                            streamingStarted = true
                        }
                        hub.delta(claim.run, delta)
                    }
                },
            ),
        ) ?: ConversationAnswerResult.aiUnavailable(MessageId(uuidFactory()))
    }

    private fun ClaimedResponseRun.readToolObserver(): ResponseRunReadToolActivityObserver? =
        realtimeHub?.let { hub -> ResponseRunReadToolActivityObserver(hub, run) }

    private fun ConversationAnswerResult.toPayload(
        detail: ConversationDetail,
        userMessage: ConversationMessage,
    ): ResponseRunResultPayload.ConversationAnswer =
        ResponseRunResultPayload.ConversationAnswer(
            conversationId = detail.conversation.id.value.toString(),
            userMessageId = userMessage.id.value.toString(),
            aiRequestId = userMessage.aiRequestId ?: "",
            assistantMessageId = assistantMessage.id.value.toString(),
            text = assistantMessage.text,
            terminalKind = outcome.toTerminalKind().name,
        )

    private fun planningUnderstandingPayload(
        detail: ConversationDetail,
        userMessage: ConversationMessage,
        currentTask: TaskDetail?,
        understanding: AiUnderstandMessageResult,
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
                delta.toRequirementWritePayloadOrNull()
            },
            removedRequirementKinds = understanding.constraintDeltas
                .filter { it.operation == ConstraintDeltaOperation.Remove }
                .map { it.kind.toBackendRequirementKind().name },
            selectedTaskContextKeys = understanding.contextSelection.selectedKeys,
            clarificationText = understanding.clarification.questionDraft?.takeIf { understanding.clarification.needed },
            planningRequested = !understanding.clarification.needed,
        )

    private fun planningResultPayload(
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

    private fun ConversationDetail.toAiContext(
        userMessage: ConversationMessage,
        currentTask: TaskDetail?,
    ): AiUnderstandMessageRequest =
        AiUnderstandMessageRequest(
            aiRequestId = userMessage.aiRequestId ?: "",
            currentMessage = userMessage.content,
            referenceTime = clock.instant().toContractInstant(),
            timeZoneId = timeZoneId,
            activePlanning = currentTask?.activePlanningPayload(),
            optionalContext = emptyList(),
            availableContextDefinitions = emptyList(),
            diagnostics = StructuredModelRequestDiagnostics(),
        )

    private fun TaskDetail.activePlanningPayload(): AiActivePlanningContextPayload =
        AiActivePlanningContextPayload(
            taskId = task.id.value.toString(),
            taskRevision = task.revision,
            goal = task.intent,
            requirements = requirements.map { it.toAiCurrentRequirement() },
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

    private fun ConstraintDeltaProposal.toRequirementWritePayloadOrNull(): RequirementWritePayload? {
        if (operation != ConstraintDeltaOperation.Upsert) return null
        val proposedValue = value ?: return null
        val proposedStrength = strength ?: return null
        return RequirementWritePayload(
            id = uuidFactory().toString(),
            kind = kind.toBackendRequirementKind().name,
            value = proposedValue.toRequirementValuePayload(),
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

    private fun AiRequirementValue.toRequirementValuePayload(): RequirementValuePayload =
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

    private fun CommutePreferenceValue.toAiCommutePreferenceValue(): AiCommutePreferenceValue =
        when (this) {
            CommutePreferenceValue.PreferShorter -> AiCommutePreferenceValue.PreferShorter
        }

    private fun ActivityModeValue.toAiActivityModeValue(): AiActivityModeValue =
        when (this) {
            ActivityModeValue.AtHome -> AiActivityModeValue.AtHome
            ActivityModeValue.OutOfHome -> AiActivityModeValue.OutOfHome
        }

    private fun logUnderstandingStarted(
        detail: ConversationDetail,
        currentTask: TaskDetail?,
    ) {
        logger?.info(
            component = "conversation",
            event = "turn_understanding_started",
            fields = logFields {
                "conversation_id" value detail.conversation.id.value.toString()
                "task_id" value currentTask?.task?.id?.value?.toString()
            },
        )
    }

    private fun logUnderstandingFinished(
        detail: ConversationDetail,
        currentTask: TaskDetail?,
        turnIntent: TurnIntent,
    ) {
        logger?.info(
            component = "conversation",
            event = "turn_understanding_finished",
            fields = logFields {
                "conversation_id" value detail.conversation.id.value.toString()
                "task_id" value currentTask?.task?.id?.value?.toString()
                "turn_intent" value turnIntent.name.toSnakeCase()
            },
        )
    }

    private fun java.time.Instant.toContractInstant(): ContractInstant =
        ContractInstant.fromEpochSeconds(epochSecond, nano.toLong())
}

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

private fun String.toSnakeCase(): String =
    buildString(length + 4) {
        this@toSnakeCase.forEachIndexed { index, character ->
            if (character.isUpperCase() && index > 0) append('_')
            append(character.lowercaseChar())
        }
    }

private const val READ_SCOPE = "orbit.tasks.read"
private const val WRITE_SCOPE = "orbit.tasks.write"
