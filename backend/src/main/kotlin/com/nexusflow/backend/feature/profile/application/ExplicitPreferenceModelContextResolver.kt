package com.nexusflow.backend.feature.profile.application

import com.nexusflow.ai.provider.StructuredModelCapability
import com.nexusflow.backend.core.aicontext.ModelContextDefinition
import com.nexusflow.backend.core.aicontext.ModelContextKey
import com.nexusflow.backend.core.aicontext.ModelContextLifecycle
import com.nexusflow.backend.core.aicontext.ModelContextPriority
import com.nexusflow.backend.core.aicontext.ModelContextProvenance
import com.nexusflow.backend.core.aicontext.ModelContextResolveRequest
import com.nexusflow.backend.core.aicontext.ModelContextResolver
import com.nexusflow.backend.core.aicontext.ModelContextTrust
import com.nexusflow.backend.core.aicontext.ResolvedModelContextBlock
import com.nexusflow.backend.feature.profile.domain.ExplicitPreference
import com.nexusflow.backend.feature.profile.domain.ExplicitPreferenceRepository
import com.nexusflow.backend.feature.task.domain.ActivityModeValue
import com.nexusflow.backend.feature.task.domain.CommutePreferenceValue
import com.nexusflow.backend.feature.task.domain.RequirementKind
import com.nexusflow.backend.feature.task.domain.RequirementValue
import com.nexusflow.backend.feature.task.domain.TaskOwner
import com.nexusflow.backend.feature.task.domain.TenantId
import com.nexusflow.backend.feature.task.domain.UserId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.util.UUID

class ExplicitPreferenceModelContextResolver(
    private val repository: ExplicitPreferenceRepository,
    private val json: Json = Json {
        encodeDefaults = false
        explicitNulls = false
    },
) : ModelContextResolver {
    override val definitions: List<ModelContextDefinition> = preferenceContextDefinitions

    override suspend fun resolve(
        request: ModelContextResolveRequest,
        keys: Set<ModelContextKey>,
    ): List<ResolvedModelContextBlock> {
        val requestedDefinitions = definitionsByKey.filterKeys { it in keys && it !in request.shadowedKeys }
        if (requestedDefinitions.isEmpty()) return emptyList()

        val preferencesByKind = repository.listForOwner(request.actor.taskOwner())
            .filter { it.kind in requestedDefinitions.values.mapTo(mutableSetOf()) { definition -> definition.kind } }
            .groupBy { it.kind }
            .mapValues { (_, preferences) -> preferences.latestDeterministic() }

        return requestedDefinitions.entries.mapNotNull { (key, definition) ->
            val preference = preferencesByKind[definition.kind] ?: return@mapNotNull null
            val content = preference.value.toContextContent(definition.kind) ?: return@mapNotNull null
            ResolvedModelContextBlock(
                key = key,
                trust = ModelContextTrust.UserProfile,
                content = content,
                provenance = ModelContextProvenance(
                    source = "ExplicitPreferenceRepository",
                    sourceVersion = preference.id.value.toString(),
                ),
                priority = definition.modelDefinition.priority,
            )
        }.sortedBy { it.key.value }
    }

    private fun RequirementValue.toContextContent(kind: RequirementKind) =
        when (kind) {
            RequirementKind.TimeWindow -> (this as? RequirementValue.TimeWindow)?.let {
                json.encodeToJsonElement(
                    TimeWindowPreferencePayload(
                        startAt = it.startAt?.toString(),
                        endAt = it.endAt?.toString(),
                        timeZoneId = it.timeZoneId,
                        text = it.originalText,
                    ),
                )
                    .jsonObject
            }
            RequirementKind.BudgetLimit -> (this as? RequirementValue.BudgetLimit)?.let {
                json.encodeToJsonElement(BudgetLimitPreferencePayload(it.wholeUnits, it.currencyCode)).jsonObject
            }
            RequirementKind.CommuteLimit -> (this as? RequirementValue.CommuteLimit)?.let {
                json.encodeToJsonElement(CommuteLimitPreferencePayload(it.maxMinutes)).jsonObject
            }
            RequirementKind.CommutePreference -> (this as? RequirementValue.CommutePreference)?.let {
                json.encodeToJsonElement(CommuteModePreferencePayload(it.value.toPayloadValue())).jsonObject
            }
            RequirementKind.Location -> (this as? RequirementValue.Location)?.let {
                json.encodeToJsonElement(TextPreferencePayload(it.text)).jsonObject
            }
            RequirementKind.ActivityDomain -> (this as? RequirementValue.ActivityDomain)?.let {
                json.encodeToJsonElement(ValuePreferencePayload(it.value)).jsonObject
            }
            RequirementKind.ActivityMode -> (this as? RequirementValue.ActivityMode)?.let {
                json.encodeToJsonElement(ActivityModePreferencePayload(it.value.toPayloadValue())).jsonObject
            }
            RequirementKind.Topic -> (this as? RequirementValue.Topic)?.let {
                json.encodeToJsonElement(TextPreferencePayload(it.text)).jsonObject
            }
            RequirementKind.ExperiencePreference -> (this as? RequirementValue.ExperiencePreference)?.let {
                json.encodeToJsonElement(TextPreferencePayload(it.text)).jsonObject
            }
        }

    private fun CommutePreferenceValue.toPayloadValue(): String =
        when (this) {
            CommutePreferenceValue.PreferShorter -> "prefer_shorter"
        }

    private fun ActivityModeValue.toPayloadValue(): String =
        when (this) {
            ActivityModeValue.AtHome -> "at_home"
            ActivityModeValue.OutOfHome -> "out_of_home"
        }

    private fun List<ExplicitPreference>.latestDeterministic(): ExplicitPreference =
        maxWith(compareBy<ExplicitPreference> { it.updatedAt }.thenBy { it.id.value.toString() })

    private fun com.nexusflow.backend.core.identity.ActorContext.taskOwner(): TaskOwner =
        TaskOwner(
            tenantId = TenantId(UUID.fromString(tenantId)),
            userId = UserId(UUID.fromString(userId)),
        )

    @Serializable
    private data class TimeWindowPreferencePayload(
        val startAt: String?,
        val endAt: String?,
        val timeZoneId: String,
        val text: String,
    )

    @Serializable
    private data class BudgetLimitPreferencePayload(
        val wholeUnits: Long,
        val currencyCode: String?,
    )

    @Serializable
    private data class CommuteLimitPreferencePayload(
        val maxMinutes: Int,
    )

    @Serializable
    private data class CommuteModePreferencePayload(
        val preference: String,
    )

    @Serializable
    private data class ActivityModePreferencePayload(
        val mode: String,
    )

    @Serializable
    private data class TextPreferencePayload(
        val text: String,
    )

    @Serializable
    private data class ValuePreferencePayload(
        val value: String,
    )
}

private data class PreferenceContextDefinition(
    val kind: RequirementKind,
    val modelDefinition: ModelContextDefinition,
)

private val preferenceContextDefinitionsByKind: List<PreferenceContextDefinition> =
    listOf(
        preferenceDefinition(
            kind = RequirementKind.TimeWindow,
            key = "profile.preference.time_window",
            description = "The user's usual preferred time window.",
            selectionHint = "Select when timing may affect plan suitability.",
        ),
        preferenceDefinition(
            kind = RequirementKind.BudgetLimit,
            key = "profile.preference.budget_limit",
            description = "The user's usual budget limit.",
            selectionHint = "Select when cost may affect plan suitability.",
        ),
        preferenceDefinition(
            kind = RequirementKind.CommuteLimit,
            key = "profile.preference.commute_limit",
            description = "The user's usual acceptable one-way commute duration.",
            selectionHint = "Select when travel time may affect plan suitability.",
        ),
        preferenceDefinition(
            kind = RequirementKind.CommutePreference,
            key = "profile.preference.commute_mode",
            description = "The user's usual commute preference.",
            selectionHint = "Select when commute tradeoffs may affect plan suitability.",
        ),
        preferenceDefinition(
            kind = RequirementKind.Location,
            key = "profile.preference.location",
            description = "The user's usual relevant location preference.",
            selectionHint = "Select when place or neighborhood may affect plan suitability.",
        ),
        preferenceDefinition(
            kind = RequirementKind.ActivityDomain,
            key = "profile.preference.activity_domain",
            description = "The user's usual activity domain preference.",
            selectionHint = "Select when the type of activity may affect plan suitability.",
        ),
        preferenceDefinition(
            kind = RequirementKind.ActivityMode,
            key = "profile.preference.activity_mode",
            description = "The user's usual at-home or out-of-home activity preference.",
            selectionHint = "Select when venue mode may affect plan suitability.",
        ),
        preferenceDefinition(
            kind = RequirementKind.Topic,
            key = "profile.preference.topic",
            description = "The user's usual topic preference.",
            selectionHint = "Select when topical interest may affect plan suitability.",
        ),
        preferenceDefinition(
            kind = RequirementKind.ExperiencePreference,
            key = "profile.preference.experience",
            description = "The user's usual experience preference.",
            selectionHint = "Select when the style of experience may affect plan suitability.",
        ),
    )

private val preferenceContextDefinitions = preferenceContextDefinitionsByKind.map { it.modelDefinition }
private val definitionsByKey = preferenceContextDefinitionsByKind.associateBy { it.modelDefinition.key }

private fun preferenceDefinition(
    kind: RequirementKind,
    key: String,
    description: String,
    selectionHint: String,
): PreferenceContextDefinition =
    PreferenceContextDefinition(
        kind = kind,
        modelDefinition = ModelContextDefinition(
            key = ModelContextKey(key),
            description = description,
            selectionHint = selectionHint,
            lifecycle = ModelContextLifecycle.Task,
            priority = ModelContextPriority.Normal,
            maxContentChars = 512,
            schemaVersion = 1,
            allowedCapabilities = setOf(
                StructuredModelCapability.UserMessageUnderstanding,
                StructuredModelCapability.PlanComposition,
            ),
        ),
    )
