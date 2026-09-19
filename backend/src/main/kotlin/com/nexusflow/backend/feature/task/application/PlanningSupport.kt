package com.nexusflow.backend.feature.task.application

import com.nexusflow.backend.core.aicontext.ModelContextAssemblyDiagnostics
import com.nexusflow.backend.core.aicontext.ModelContextBlock
import com.nexusflow.backend.core.aicontext.ModelContextTrust
import com.nexusflow.backend.core.identity.ActorContext
import com.nexusflow.backend.feature.task.domain.AvailabilityFact
import com.nexusflow.backend.feature.task.domain.Opportunity
import com.nexusflow.backend.feature.task.domain.OpportunityId
import com.nexusflow.backend.feature.task.domain.PlanId
import com.nexusflow.backend.feature.task.domain.PlanValidationFailure
import com.nexusflow.backend.feature.task.domain.Requirement
import com.nexusflow.backend.feature.task.domain.RequirementStrength
import com.nexusflow.backend.feature.task.domain.RequirementValue
import com.nexusflow.backend.feature.task.domain.TaskId
import com.nexusflow.backend.feature.task.domain.TaskOwner
import com.nexusflow.backend.feature.task.domain.TenantId
import com.nexusflow.backend.feature.task.domain.UserId
import com.nexusflow.backend.feature.task.domain.isFeasibilityFailure
import com.nexusflow.contracts.backendai.common.ModelContextBlockPayload as AiModelContextBlockPayload
import com.nexusflow.contracts.backendai.common.ModelContextTrustPayload as AiModelContextTrustPayload
import com.nexusflow.contracts.backendai.common.StructuredModelRequestDiagnostics
import com.nexusflow.contracts.backendai.planning.PlanningRequirement as AiPlanningRequirement
import com.nexusflow.contracts.backendai.planning.PlanningRequirementStrength as AiPlanningRequirementStrength
import kotlinx.datetime.Instant as ContractInstant
import java.time.Instant
import java.util.UUID

internal const val PLANNING_WRITE_SCOPE = "orbit.tasks.write"
internal const val PLANNING_LOG_COMPONENT = "planning"
internal const val MAX_AI_SOURCE_REFS = 5
internal const val MAX_RESEARCH_TITLE_CHARS = 120
internal const val MAX_RESEARCH_SUMMARY_CHARS = 700

internal fun Throwable.safeFailureCategory(): String =
    (this::class.simpleName ?: "Throwable").toSnakeCase()

internal fun List<PlanValidationFailure>.toPlanningOutcome(): PlanningOutcome =
    if (isNotEmpty() && all { it.code.isFeasibilityFailure }) {
        PlanningOutcome.NoFeasiblePlan
    } else {
        PlanningOutcome.Unavailable
    }

internal val PlanningOutcome.logValue: String
    get() = name.toSnakeCase()

internal val PlanningTrigger.logValue: String
    get() = name.toSnakeCase()

internal val PlanningDecision.logValue: String
    get() = name.toSnakeCase()

internal fun String.toSnakeCase(): String =
    buildString(length + 4) {
        this@toSnakeCase.forEachIndexed { index, character ->
            if (character.isUpperCase() && index > 0) append('_')
            append(character.lowercaseChar())
        }
    }

internal fun ActorContext.toPlanningTaskOwner(): TaskOwner =
    TaskOwner(
        tenantId = TenantId(tenantId.toUuid("tenantId")),
        userId = UserId(userId.toUuid("userId")),
    )

internal fun ActorContext.requirePlanningScope(scope: String) {
    if (!hasScope(scope)) throw MissingTaskScopeException()
}

internal fun String.toPlanningTaskId(): TaskId = TaskId(toUuid("taskId"))

internal fun String.toPlanningPlanId(): PlanId = PlanId(toUuid("planId"))

internal fun String.toPlanningOpportunityId(): OpportunityId =
    try {
        OpportunityId(UUID.fromString(this))
    } catch (_: IllegalArgumentException) {
        throw TaskDependencyUnavailableException("Planning result is temporarily unavailable")
    }

private fun String.toUuid(fieldName: String): UUID =
    try {
        UUID.fromString(this)
    } catch (_: IllegalArgumentException) {
        throw InvalidTaskRequestException("$fieldName is invalid")
    }

internal fun List<Opportunity>.filterVerified(now: Instant): List<Opportunity> =
    filter { opportunity ->
        opportunity.facts.availability != AvailabilityFact.Unavailable &&
            opportunity.validUntil?.isAfter(now) == true &&
            opportunity.sources.isNotEmpty()
    }

internal fun Requirement.toAiPlanningRequirement(): AiPlanningRequirement =
    AiPlanningRequirement(
        id = id.value.toString(),
        kind = kind.name,
        valueSummary = value.summary(),
        strength = when (strength) {
            RequirementStrength.Must -> AiPlanningRequirementStrength.Must
            RequirementStrength.Prefer -> AiPlanningRequirementStrength.Prefer
        },
    )

private fun RequirementValue.summary(): String =
    when (this) {
        is RequirementValue.TimeWindow -> originalText
        is RequirementValue.BudgetLimit -> listOfNotNull(wholeUnits.toString(), currencyCode).joinToString(" ")
        is RequirementValue.CommuteLimit -> "$maxMinutes minutes"
        is RequirementValue.CommutePreference -> value.name
        is RequirementValue.Location -> text
        is RequirementValue.ActivityDomain -> value
        is RequirementValue.ActivityMode -> value.name
        is RequirementValue.Topic -> text
        is RequirementValue.ExperiencePreference -> text
    }

internal fun ModelContextBlock.toAiPayload(): AiModelContextBlockPayload =
    AiModelContextBlockPayload(
        key = key,
        trust = trust.toAiPayload(),
        content = content,
    )

internal fun ModelContextAssemblyDiagnostics.toAiRequestDiagnostics(): StructuredModelRequestDiagnostics =
    StructuredModelRequestDiagnostics(
        selectedContextKeyCount = selectedContextKeyCount,
        resolvedContextBlockCount = resolvedContextBlockCount,
        includedContextBlockCount = includedContextBlockCount,
        omittedContextBlockCount = omittedContextBlockCount,
        optionalContextSerializedChars = optionalContextSerializedChars,
    )

private fun ModelContextTrust.toAiPayload(): AiModelContextTrustPayload =
    AiModelContextTrustPayload.valueOf(name)

internal fun Instant.toContractInstant(): ContractInstant =
    ContractInstant.fromEpochSeconds(epochSecond, nano.toLong())
