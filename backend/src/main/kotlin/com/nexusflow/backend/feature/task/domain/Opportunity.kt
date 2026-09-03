package com.nexusflow.backend.feature.task.domain

import java.time.Instant
import java.util.Locale

interface OpportunityProvider {
    fun discover(request: OpportunityRequest): List<Opportunity>
}

data class OpportunityRequest(
    val task: Task,
    val requirements: List<Requirement>,
    val referenceTime: Instant,
)

data class Opportunity(
    val id: OpportunityId,
    val provider: String,
    val externalKey: String,
    val kind: OpportunityKind,
    val title: String,
    val facts: OpportunityFacts,
    val sources: List<SourceRef>,
    val observedAt: Instant,
    val validUntil: Instant?,
)

enum class OpportunityKind {
    Sports,
    Movies,
}

data class OpportunityFacts(
    val summary: String?,
    val startTime: Instant?,
    val endTime: Instant?,
    val location: LocationFact?,
    val activityMode: ActivityModeValue?,
    val price: MoneyFact?,
    val commute: DurationFact?,
    val availability: AvailabilityFact?,
    val attributes: Map<String, FactValue> = emptyMap(),
)

data class LocationFact(
    val displayName: String,
    val normalizedName: String,
)

data class MoneyFact(
    val wholeUnits: Long,
    val currencyCode: String?,
)

data class DurationFact(
    val minutes: Int,
)

enum class AvailabilityFact {
    Available,
    Limited,
    Unavailable,
}

sealed interface FactValue {
    data class Text(val value: String) : FactValue
    data class Number(val value: Long) : FactValue
    data class Flag(val value: Boolean) : FactValue
}

data class SourceRef(
    val label: String,
    val uri: String?,
    val sourceUpdatedAt: Instant?,
)

internal fun String.normalizedPlanningToken(): String =
    trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

internal fun OpportunityKind.matchesDomainText(text: String): Boolean =
    text.normalizedPlanningToken() in planningDomainTokens()

private fun OpportunityKind.planningDomainTokens(): Set<String> =
    when (this) {
        OpportunityKind.Sports -> setOf("sports", "sport", "football", "soccer", "match")
        OpportunityKind.Movies -> setOf("movies", "movie", "cinema", "film")
    }
