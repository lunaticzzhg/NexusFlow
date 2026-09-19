package com.nexusflow.backend.feature.research.application.source

import com.nexusflow.backend.core.external.ExternalSourceException
import com.nexusflow.backend.feature.task.application.TaskDependencyUnavailableException
import com.nexusflow.backend.feature.task.domain.ActivityModeValue
import com.nexusflow.backend.feature.task.domain.DurationFact
import com.nexusflow.backend.feature.task.domain.FactValue
import com.nexusflow.backend.feature.task.domain.LocationFact
import com.nexusflow.backend.feature.task.domain.Opportunity
import com.nexusflow.backend.feature.task.domain.OpportunityFacts
import com.nexusflow.backend.feature.task.domain.OpportunityId
import com.nexusflow.backend.feature.task.domain.OpportunityKind
import com.nexusflow.backend.feature.task.domain.SourceRef
import com.nexusflow.backend.feature.task.domain.source.PlaceLookupQuery
import com.nexusflow.backend.feature.task.domain.source.PlaceLookupSource
import com.nexusflow.backend.feature.task.domain.source.PlaceCandidate
import com.nexusflow.backend.feature.task.domain.source.RouteFact
import com.nexusflow.backend.feature.task.domain.source.RouteQuery
import com.nexusflow.backend.feature.task.domain.source.RouteSource
import com.nexusflow.backend.feature.task.domain.source.TrailCandidate
import com.nexusflow.backend.feature.task.domain.source.TrailDiscoveryQuery
import com.nexusflow.backend.feature.task.domain.source.TrailDiscoverySource
import com.nexusflow.backend.feature.task.domain.source.WeatherFact
import com.nexusflow.backend.feature.task.domain.source.WeatherQuery
import com.nexusflow.backend.feature.task.domain.source.WeatherSource
import com.nexusflow.backend.feature.task.domain.source.WebDiscoverySource
import com.nexusflow.backend.feature.task.domain.source.WebSearchQuery
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class OutdoorAcquirer(
    private val trailPrimary: TrailDiscoverySource?,
    private val trailSecondary: TrailDiscoverySource?,
    private val placePrimary: PlaceLookupSource?,
    private val placeSecondary: PlaceLookupSource?,
    private val routePrimary: RouteSource?,
    private val routeSecondary: RouteSource?,
    private val weatherPrimary: WeatherSource?,
    private val weatherSecondary: WeatherSource?,
    private val webDiscoverySource: WebDiscoverySource?,
) {
    suspend fun acquire(
        query: TrailDiscoveryQuery,
        referenceTime: Instant,
    ): List<Opportunity> {
        val searchCenter = query.center ?: findSearchCenter(query.near, mutableListOf())?.point
        val trailQuery = query.copy(center = searchCenter)
        val trailOutcomes = mutableListOf<SourceOutcome>()
        val primaryTrails = trailPrimary?.searchSafely(trailQuery, trailOutcomes).orEmpty()
        val sufficientPrimary = primaryTrails.filter { it.isSufficient() }
        val trails =
            if (sufficientPrimary.isNotEmpty()) {
                sufficientPrimary
            } else {
                mergeTrails(primaryTrails, trailSecondary?.searchSafely(trailQuery, trailOutcomes).orEmpty())
                    .filter { it.isSufficient() }
            }

        if (trails.isEmpty()) {
            runDiscoveryOnlyWebSearch(trailQuery)
            if (trailPrimary == null && trailSecondary == null && webDiscoverySource == null) {
                throw TaskDependencyUnavailableException("Outdoor trail source chain is not configured")
            }
            if (trailPrimary == null && trailSecondary == null) {
                throw TaskDependencyUnavailableException("Outdoor trail vertical source chain is not configured")
            }
            if (trailOutcomes.isNotEmpty() && trailOutcomes.all { it == SourceOutcome.TechnicalFailure }) {
                throw TaskDependencyUnavailableException("Outdoor trail source chain is temporarily unavailable")
            }
            return emptyList()
        }

        return trails.map { trail ->
            val place = if (trail.startLocation == null) findPlace(trail.name, query.near, mutableListOf()) else null
            val point = trail.startLocation ?: place?.point
            val route = point?.let { destination ->
                query.origin?.let { origin ->
                    findRoute(RouteQuery(destination = destination, origin = origin), mutableListOf())
                }
            }
            val weather = point?.let { findWeather(WeatherQuery(point = it, dateFrom = query.dateFrom, dateTo = query.dateTo)) }
            trail.toOpportunity(
                referenceTime = referenceTime,
                dateFrom = query.dateFrom,
                dateTo = query.dateTo,
                place = place,
                route = route,
                weather = weather,
            )
        }
    }

    private suspend fun findSearchCenter(
        near: String?,
        outcomes: MutableList<SourceOutcome>,
    ): PlaceCandidate? =
        near
            ?.takeIf(String::isNotBlank)
            ?.let { location ->
                placePrimary.searchPlaces(location, null, outcomes).firstOrNull()
                    ?: placeSecondary.searchPlaces(location, null, outcomes).firstOrNull()
            }

    private suspend fun TrailDiscoverySource.searchSafely(
        query: TrailDiscoveryQuery,
        outcomes: MutableList<SourceOutcome>,
    ): List<TrailCandidate>? =
        try {
            search(query).also { candidates ->
                outcomes += if (candidates.isEmpty()) SourceOutcome.SuccessEmpty else SourceOutcome.SuccessWithResults
            }
        } catch (_: ExternalSourceException) {
            outcomes += SourceOutcome.TechnicalFailure
            null
        }

    private suspend fun findPlace(
        trailName: String,
        near: String?,
        outcomes: MutableList<SourceOutcome>,
    ) = placePrimary.searchPlaces(trailName, near, outcomes).firstOrNull()
        ?: placeSecondary.searchPlaces(trailName, near, outcomes).firstOrNull()

    private suspend fun PlaceLookupSource?.searchPlaces(
        trailName: String,
        near: String?,
        outcomes: MutableList<SourceOutcome>,
    ) = this?.let { source ->
        try {
            source.find(PlaceLookupQuery(text = trailName, near = near)).also { candidates ->
                outcomes += if (candidates.isEmpty()) SourceOutcome.SuccessEmpty else SourceOutcome.SuccessWithResults
            }
        } catch (_: ExternalSourceException) {
            outcomes += SourceOutcome.TechnicalFailure
            emptyList()
        }
    }.orEmpty()

    private suspend fun findRoute(
        query: RouteQuery,
        outcomes: MutableList<SourceOutcome>,
    ): RouteFact? =
        routePrimary.routeSafely(query, outcomes)
            ?: routeSecondary.routeSafely(query, outcomes)

    private suspend fun RouteSource?.routeSafely(
        query: RouteQuery,
        outcomes: MutableList<SourceOutcome>,
    ): RouteFact? =
        this?.let { source ->
            try {
                source.route(query).also { fact ->
                    outcomes += if (fact == null) SourceOutcome.SuccessEmpty else SourceOutcome.SuccessWithResults
                }
            } catch (_: ExternalSourceException) {
                outcomes += SourceOutcome.TechnicalFailure
                null
            }
        }

    private suspend fun findWeather(query: WeatherQuery): WeatherFact? =
        weatherPrimary.weatherSafely(query)
            ?: weatherSecondary.weatherSafely(query)

    private suspend fun WeatherSource?.weatherSafely(query: WeatherQuery): WeatherFact? =
        this?.let { source ->
            try {
                source.forecast(query)
            } catch (_: ExternalSourceException) {
                null
            }
        }

    private suspend fun runDiscoveryOnlyWebSearch(query: TrailDiscoveryQuery) {
        try {
            webDiscoverySource?.search(WebSearchQuery(query.toWebQuery(), maxResults = 5))
        } catch (_: ExternalSourceException) {
            // Web discovery is not an authoritative Outdoor source in Slice 4.
        }
    }

    private fun mergeTrails(
        primaryTrails: List<TrailCandidate>,
        secondaryTrails: List<TrailCandidate>,
    ): List<TrailCandidate> {
        if (primaryTrails.isEmpty()) return secondaryTrails
        if (secondaryTrails.isEmpty()) return primaryTrails
        val secondaryByName = secondaryTrails.associateBy { it.name.normalizedIdentity() }
        return primaryTrails.map { primary ->
            val secondary = secondaryByName[primary.name.normalizedIdentity()] ?: return@map primary
            primary.copy(
                routeType = primary.routeType ?: secondary.routeType,
                distanceMeters = primary.distanceMeters ?: secondary.distanceMeters,
                elevationGainMeters = primary.elevationGainMeters ?: secondary.elevationGainMeters,
                startLocation = primary.startLocation ?: secondary.startLocation,
                summary = primary.summary ?: secondary.summary,
                publicUrl = primary.publicUrl ?: secondary.publicUrl,
                sources = primary.sources + secondary.sources,
            )
        } + secondaryTrails.filter { secondary ->
            primaryTrails.none { it.name.normalizedIdentity() == secondary.name.normalizedIdentity() }
        }
    }

    private fun TrailCandidate.isSufficient(): Boolean =
        name.isNotBlank() && sources.isNotEmpty()

    private fun TrailCandidate.toOpportunity(
        referenceTime: Instant,
        dateFrom: LocalDate?,
        dateTo: LocalDate?,
        place: PlaceCandidate?,
        route: RouteFact?,
        weather: WeatherFact?,
    ): Opportunity {
        val startsAt = dateFrom?.atStartOfDay()?.plusHours(DEFAULT_START_HOUR)?.toInstant(ZoneOffset.UTC)
            ?: referenceTime.plus(Duration.ofDays(1))
        val end = dateTo?.atStartOfDay()?.toInstant(ZoneOffset.UTC)
            ?.takeIf { it.isAfter(startsAt) }
            ?: startsAt.plus(Duration.ofHours(DEFAULT_DURATION_HOURS))
        val locationName = place?.displayName ?: name
        return Opportunity(
            id = OpportunityId(UUID.nameUUIDFromBytes("outdoor:$externalTrailId".toByteArray(StandardCharsets.UTF_8))),
            provider = sources.firstOrNull()?.sourceId ?: "outdoor",
            externalKey = externalTrailId,
            kind = OpportunityKind.Outdoor,
            title = name,
            facts = OpportunityFacts(
                summary = summary ?: routeType,
                startTime = startsAt,
                endTime = end,
                location = LocationFact(locationName, locationName.lowercase()),
                activityMode = ActivityModeValue.OutOfHome,
                price = null,
                commute = route?.commuteMinutes?.let(::DurationFact),
                availability = null,
                attributes = outdoorAttributes(route, weather),
            ),
            sources = (sources + place.sourcesOrEmpty() + route.sourceOrEmpty() + weather.sourceOrEmpty()).distinct(),
            observedAt = referenceTime,
            validUntil = minOf(startsAt, referenceTime.plus(Duration.ofHours(6))),
        )
    }

    private fun TrailCandidate.outdoorAttributes(
        route: RouteFact?,
        weather: WeatherFact?,
    ): Map<String, FactValue> =
        buildMap {
            put("topics", FactValue.Text(listOfNotNull("outdoor", "hiking", "trail", routeType, name).joinToString(",")))
            put("locations", FactValue.Text(name))
            distanceMeters?.let { put("distanceMeters", FactValue.Number(it.toLong())) }
            elevationGainMeters?.let { put("elevationGainMeters", FactValue.Number(it.toLong())) }
            route?.distanceMeters?.let { put("routeDistanceMeters", FactValue.Number(it.toLong())) }
            route?.durationMinutes?.let { put("routeDurationMinutes", FactValue.Number(it.toLong())) }
            weather?.summary?.let { put("weatherSummary", FactValue.Text(it)) }
            weather?.temperatureCelsius?.let { put("temperatureCelsius", FactValue.Number(it)) }
            weather?.precipitationProbabilityPercent?.let { put("precipitationProbabilityPercent", FactValue.Number(it)) }
            weather?.windSpeedKph?.let { put("windSpeedKph", FactValue.Number(it)) }
        }

    private fun RouteFact?.sourceOrEmpty(): List<SourceRef> =
        this?.let { listOf(it.source) }.orEmpty()

    private fun PlaceCandidate?.sourcesOrEmpty(): List<SourceRef> =
        this?.sources.orEmpty()

    private fun WeatherFact?.sourceOrEmpty(): List<SourceRef> =
        this?.let { listOf(it.source) }.orEmpty()

    private fun TrailDiscoveryQuery.toWebQuery(): String =
        listOfNotNull(keyword, near, dateFrom?.toString(), dateTo?.toString(), "official trail hiking park notice")
            .joinToString(" ")

    private fun String.normalizedIdentity(): String =
        lowercase().replace(Regex("\\s+"), " ").trim()

    private enum class SourceOutcome {
        SuccessWithResults,
        SuccessEmpty,
        TechnicalFailure,
    }

    private companion object {
        const val DEFAULT_START_HOUR = 9L
        const val DEFAULT_DURATION_HOURS = 4L
    }
}
