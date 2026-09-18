package com.nexusflow.backend.feature.task.domain.source

import com.nexusflow.backend.feature.task.domain.SourceRef
import java.time.LocalDate

interface TrailDiscoverySource {
    suspend fun search(query: TrailDiscoveryQuery): List<TrailCandidate>
}

data class TrailDiscoveryQuery(
    val keyword: String,
    val near: String? = null,
    val center: GeoPoint? = null,
    val origin: GeoPoint? = null,
    val radiusMeters: Int = 25_000,
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null,
) {
    init {
        require(keyword.isNotBlank()) { "trail discovery keyword must not be blank" }
        require(radiusMeters in 1..50_000) { "trail discovery radius must be between 1 and 50000 meters" }
    }
}

data class TrailCandidate(
    val externalTrailId: String,
    val name: String,
    val routeType: String?,
    val distanceMeters: Int?,
    val elevationGainMeters: Int?,
    val startLocation: GeoPoint?,
    val summary: String?,
    val publicUrl: String?,
    val sources: List<SourceRef>,
)

interface PlaceLookupSource {
    suspend fun find(query: PlaceLookupQuery): List<PlaceCandidate>
}

data class PlaceLookupQuery(
    val text: String,
    val near: String? = null,
) {
    init {
        require(text.isNotBlank()) { "place lookup text must not be blank" }
    }
}

data class PlaceCandidate(
    val externalPlaceId: String,
    val displayName: String,
    val point: GeoPoint,
    val publicUrl: String?,
    val sources: List<SourceRef>,
)

interface RouteSource {
    suspend fun route(query: RouteQuery): RouteFact?
}

data class RouteQuery(
    val destination: GeoPoint,
    val origin: GeoPoint? = null,
    val profile: RouteProfile = RouteProfile.Hiking,
)

enum class RouteProfile {
    Hiking,
}

data class RouteFact(
    val distanceMeters: Int?,
    val durationMinutes: Int?,
    val commuteMinutes: Int?,
    val source: SourceRef,
)

interface WeatherSource {
    suspend fun forecast(query: WeatherQuery): WeatherFact?
}

data class WeatherQuery(
    val point: GeoPoint,
    val dateFrom: LocalDate? = null,
    val dateTo: LocalDate? = null,
)

data class WeatherFact(
    val summary: String?,
    val temperatureCelsius: Long?,
    val precipitationProbabilityPercent: Long?,
    val windSpeedKph: Long?,
    val source: SourceRef,
)

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude in -90.0..90.0) { "latitude is out of range" }
        require(longitude in -180.0..180.0) { "longitude is out of range" }
    }

    companion object
}
