package com.nexusflow.backend.feature.task.application

import com.nexusflow.backend.core.identity.ActorContext
import com.nexusflow.backend.feature.research.application.ReadToolCall
import com.nexusflow.backend.feature.research.application.ReadToolCatalog
import com.nexusflow.backend.feature.research.application.ReadToolEvidence
import com.nexusflow.backend.feature.research.application.ReadToolExecution
import com.nexusflow.backend.feature.research.application.ReadToolExecutionContext
import com.nexusflow.backend.feature.research.application.ReadToolExecutionObserver
import com.nexusflow.backend.feature.research.application.ReadToolFactKind
import com.nexusflow.backend.feature.research.application.ReadToolFactValue
import com.nexusflow.backend.feature.research.application.ReadToolExecutor
import com.nexusflow.backend.feature.research.application.ReadToolKey
import com.nexusflow.backend.feature.research.application.ReadToolOutcome
import com.nexusflow.backend.feature.research.application.ReadToolSourceAuthority
import com.nexusflow.backend.feature.research.application.readtool.MovieShowtimesKey
import com.nexusflow.backend.feature.research.application.readtool.MusicEventsKey
import com.nexusflow.backend.feature.research.application.readtool.OutdoorTrailsKey
import com.nexusflow.backend.feature.research.application.readtool.SportsEventsKey
import com.nexusflow.backend.feature.research.application.readtool.SportsFixturesKey
import com.nexusflow.backend.feature.task.domain.ActivityModeValue
import com.nexusflow.backend.feature.task.domain.AvailabilityFact
import com.nexusflow.backend.feature.task.domain.DurationFact
import com.nexusflow.backend.feature.task.domain.FactValue
import com.nexusflow.backend.feature.task.domain.LocationFact
import com.nexusflow.backend.feature.task.domain.MoneyFact
import com.nexusflow.backend.feature.task.domain.Opportunity
import com.nexusflow.backend.feature.task.domain.OpportunityFactKey
import com.nexusflow.backend.feature.task.domain.OpportunityFacts
import com.nexusflow.backend.feature.task.domain.OpportunityId
import com.nexusflow.backend.feature.task.domain.OpportunityKind
import com.nexusflow.backend.feature.task.domain.SourceAuthority
import com.nexusflow.backend.feature.task.domain.SourceRef
import com.nexusflow.backend.feature.task.domain.TaskDetail
import com.nexusflow.contracts.backendai.common.AiCapabilityException
import com.nexusflow.contracts.backendai.conversation.ReadOnlyToolDefinitionPayload
import com.nexusflow.contracts.backendai.planning.PlanningResearchCapability
import com.nexusflow.contracts.backendai.planning.PlanningResearchRequest as AiPlanningResearchRequest
import kotlinx.coroutines.CancellationException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.UUID

internal class OpportunityDiscovery(
    private val planningResearch: PlanningResearchCapability?,
    private val readToolCatalog: ReadToolCatalog,
    private val readToolExecutor: ReadToolExecutor,
    private val timeZoneId: String,
    private val logger: PlanningLogger,
) {
    suspend fun discover(
        actor: ActorContext,
        detail: TaskDetail,
        now: Instant,
        optionalContext: PlanningOptionalContext,
        readToolObserver: ReadToolExecutionObserver?,
    ): PlanningResearchDiscovery {
        val research = planningResearch
            ?: return PlanningResearchDiscovery(outcome = PlanningOutcome.Unavailable)
        val availableReadTools = readToolCatalog.definitions()
        if (availableReadTools.isEmpty()) {
            return PlanningResearchDiscovery(outcome = PlanningOutcome.Unavailable)
        }
        logger.planningResearchStarted(detail, availableReadTools.size)
        val researchResult = try {
            research.research(
                AiPlanningResearchRequest(
                    planningResearchRequestId = "research-${detail.task.id.value}-${detail.task.revision}",
                    taskId = detail.task.id.value.toString(),
                    taskRevision = detail.task.revision,
                    goal = detail.task.intent,
                    requirements = detail.requirements.map { it.toAiPlanningRequirement() },
                    optionalContext = optionalContext.blocks.map { it.toAiPayload() },
                    availableReadTools = availableReadTools.map { definition ->
                        ReadOnlyToolDefinitionPayload(
                            toolKey = definition.key.value,
                            description = definition.description,
                            argumentHint = definition.argumentHint,
                        )
                    },
                    referenceTime = now.toContractInstant(),
                    timeZoneId = timeZoneId,
                    diagnostics = optionalContext.diagnostics,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: AiCapabilityException) {
            return PlanningResearchDiscovery(outcome = PlanningOutcome.Unavailable)
        }

        val offeredKeys = availableReadTools.mapTo(linkedSetOf()) { it.key }
        val calls = researchResult.toolCalls.map { proposal ->
            val key = proposal.toolKey.trim().takeIf(String::isNotBlank)
                ?: return PlanningResearchDiscovery(outcome = PlanningOutcome.Unavailable)
            val readToolKey = ReadToolKey(key)
            if (readToolKey !in offeredKeys) {
                return PlanningResearchDiscovery(outcome = PlanningOutcome.Unavailable)
            }
            ReadToolCall(readToolKey, proposal.arguments)
        }
        if (calls.isEmpty()) {
            logger.planningResearchFinished(detail, emptyList(), 0)
            return PlanningResearchDiscovery(outcome = PlanningOutcome.NoCandidates)
        }

        val executions = try {
            readToolExecutor.execute(
                calls = calls,
                context = ReadToolExecutionContext(
                    referenceTime = now,
                    timeZoneId = timeZoneId,
                    actorTenantId = actor.tenantId,
                    actorUserId = actor.userId,
                    conversationId = null,
                    taskId = detail.task.id.value.toString(),
                ),
                observer = readToolObserver,
            )
        } catch (_: IllegalArgumentException) {
            return PlanningResearchDiscovery(outcome = PlanningOutcome.Unavailable)
        }

        val evidence = executions.flatMap { execution ->
            when (val outcome = execution.outcome) {
                is ReadToolOutcome.Success -> outcome.payload.evidence
                ReadToolOutcome.Empty,
                is ReadToolOutcome.MissingInput,
                is ReadToolOutcome.InvalidArguments,
                is ReadToolOutcome.Unavailable,
                -> emptyList()
            }
        }
        if (evidence.isEmpty()) {
            val outcome = executions.withoutEvidencePlanningOutcome()
            logger.planningResearchFinished(detail, executions, 0)
            return PlanningResearchDiscovery(outcome = outcome)
        }

        val opportunities = evidence.mapIndexedNotNull { index, item ->
            item.toPlanningOpportunityOrNull(
                index = index,
                referenceTime = now,
            )
        }
        logger.planningResearchFinished(detail, executions, opportunities.size)
        return PlanningResearchDiscovery(opportunities = opportunities)
    }
}

private fun List<ReadToolExecution>.withoutEvidencePlanningOutcome(): PlanningOutcome =
    when {
        any { it.outcome is ReadToolOutcome.Unavailable } -> PlanningOutcome.Unavailable
        isNotEmpty() && all { it.outcome == ReadToolOutcome.Empty } -> PlanningOutcome.NoCandidates
        any { it.outcome is ReadToolOutcome.MissingInput } -> PlanningOutcome.NoCandidates
        any { it.outcome is ReadToolOutcome.InvalidArguments } -> PlanningOutcome.Unavailable
        else -> PlanningOutcome.NoCandidates
    }

private fun ReadToolEvidence.toPlanningOpportunityOrNull(
    index: Int,
    referenceTime: Instant,
): Opportunity? {
    val kind = sourceKey.toOpportunityKindOrNull() ?: return null
    val title = textFact(ReadToolFactKind.TITLE)?.takeIf(String::isNotBlank) ?: return null
    val start = timestampFact(ReadToolFactKind.START_TIME)
    val end = timestampFact(ReadToolFactKind.END_TIME)
    val location = textFact(ReadToolFactKind.LOCATION_NAME)?.let { LocationFact(it, it.lowercase()) }
    val price = moneyFact(ReadToolFactKind.PRICE)
    val commute = integerFact(ReadToolFactKind.COMMUTE_MINUTES)?.let { DurationFact(it.toInt()) }
    val availability = textFact(ReadToolFactKind.AVAILABILITY)?.toAvailabilityFactOrNull()
    val activityMode = textFact(ReadToolFactKind.ACTIVITY_MODE)?.toActivityModeValueOrNull()
    val summary = textFact(ReadToolFactKind.SUMMARY)
    return Opportunity(
        id = OpportunityId(UUID.nameUUIDFromBytes("planning-research:$sourceId:$index".toByteArray(StandardCharsets.UTF_8))),
        provider = sourceKey,
        externalKey = sourceId,
        kind = kind,
        title = title.take(MAX_RESEARCH_TITLE_CHARS),
        facts = OpportunityFacts(
            summary = summary?.take(MAX_RESEARCH_SUMMARY_CHARS),
            startTime = start,
            endTime = end,
            location = location,
            activityMode = activityMode,
            price = price,
            commute = commute,
            availability = availability,
            attributes = planningAttributes(kind, title, summary),
        ),
        sources = listOf(
            SourceRef(
                label = sourceKey,
                uri = sourceUrl,
                sourceUpdatedAt = sourceUpdatedAt,
                sourceId = sourceId,
                authority = authority.toPlanningSourceAuthority(),
                factKeys = sourceKey.toOpportunityFactKeys(),
            ),
        ),
        observedAt = referenceTime,
        validUntil = listOfNotNull(start, referenceTime.plus(Duration.ofHours(6)))
            .filter { it.isAfter(referenceTime) }
            .minOrNull(),
    )
}

private fun String.toOpportunityKindOrNull(): OpportunityKind? =
    when (this) {
        MovieShowtimesKey.value -> OpportunityKind.Movies
        OutdoorTrailsKey.value -> OpportunityKind.Outdoor
        SportsFixturesKey.value,
        SportsEventsKey.value,
        -> OpportunityKind.Sports
        MusicEventsKey.value -> OpportunityKind.LiveEvents
        else -> null
    }

private fun String.toOpportunityFactKeys(): Set<OpportunityFactKey> =
    when (this) {
        MovieShowtimesKey.value -> setOf(OpportunityFactKey.MovieShowtime, OpportunityFactKey.Availability)
        MusicEventsKey.value,
        SportsEventsKey.value,
        -> setOf(OpportunityFactKey.LiveEventMetadata, OpportunityFactKey.Availability)
        SportsFixturesKey.value -> setOf(OpportunityFactKey.FixtureStatus, OpportunityFactKey.StartTime)
        OutdoorTrailsKey.value -> setOf(
            OpportunityFactKey.TrailMetadata,
            OpportunityFactKey.Location,
            OpportunityFactKey.Route,
            OpportunityFactKey.Weather,
        )
        else -> setOf(OpportunityFactKey.Summary)
    }

private fun ReadToolEvidence.planningAttributes(
    kind: OpportunityKind,
    title: String,
    summary: String?,
): Map<String, FactValue> {
    val topicTags = (
        listOfNotNull(kind.name, title, sourceKey, summary) +
            listOfNotNull(
                textFact(ReadToolFactKind.HOME_TEAM),
                textFact(ReadToolFactKind.AWAY_TEAM),
                textFact(ReadToolFactKind.COMPETITION),
                textFact(ReadToolFactKind.ARTISTS),
            )
    )
        .flatMap { it.split(',', '/', '|') }
        .map { it.planningTopicToken() }
        .filter(String::isNotBlank)
        .distinct()
    val attributes = mutableMapOf<String, FactValue>()
    if (topicTags.isNotEmpty()) {
        attributes["topics"] = FactValue.Text(topicTags.joinToString(","))
    }
    integerFact(ReadToolFactKind.DISTANCE_METERS)?.let { attributes["distanceMeters"] = FactValue.Number(it) }
    integerFact(ReadToolFactKind.ELEVATION_GAIN_METERS)?.let { attributes["elevationGainMeters"] = FactValue.Number(it) }
    return attributes
}

private fun ReadToolEvidence.textFact(kind: ReadToolFactKind): String? =
    facts.firstOrNull { it.kind == kind }?.value?.let { value ->
        when (value) {
            is ReadToolFactValue.Text -> value.value
            else -> null
        }
    }

private fun ReadToolEvidence.integerFact(kind: ReadToolFactKind): Long? =
    facts.firstOrNull { it.kind == kind }?.value?.let { value ->
        when (value) {
            is ReadToolFactValue.Integer -> value.value
            else -> null
        }
    }

private fun ReadToolEvidence.timestampFact(kind: ReadToolFactKind): Instant? =
    facts.firstOrNull { it.kind == kind }?.value?.let { value ->
        when (value) {
            is ReadToolFactValue.Timestamp -> value.value
            else -> null
        }
    }

private fun ReadToolEvidence.moneyFact(kind: ReadToolFactKind): MoneyFact? =
    facts.firstOrNull { it.kind == kind }?.value?.let { value ->
        when (value) {
            is ReadToolFactValue.Money -> MoneyFact(value.wholeUnits, value.currencyCode)
            else -> null
        }
    }

private fun String.toAvailabilityFactOrNull(): AvailabilityFact? =
    AvailabilityFact.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }

private fun String.toActivityModeValueOrNull(): ActivityModeValue? =
    ActivityModeValue.entries.firstOrNull { it.name.equals(this, ignoreCase = true) }

private fun String.planningTopicToken(): String =
    trim()
        .lowercase()
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

private fun ReadToolSourceAuthority.toPlanningSourceAuthority(): SourceAuthority =
    when (this) {
        ReadToolSourceAuthority.StructuredPrimary -> SourceAuthority.StructuredPrimary
        ReadToolSourceAuthority.StructuredSecondary -> SourceAuthority.StructuredSecondary
        ReadToolSourceAuthority.OfficialWeb -> SourceAuthority.OfficialWeb
        ReadToolSourceAuthority.GeneralWeb -> SourceAuthority.GeneralWeb
    }
