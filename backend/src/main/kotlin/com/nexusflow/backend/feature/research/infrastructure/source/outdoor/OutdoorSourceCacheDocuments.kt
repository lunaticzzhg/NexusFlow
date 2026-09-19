package com.nexusflow.backend.feature.research.infrastructure.source.outdoor

import com.nexusflow.backend.feature.task.domain.source.GeoPoint
import com.nexusflow.backend.feature.task.domain.source.PlaceCandidate
import com.nexusflow.backend.feature.task.domain.source.RouteFact
import com.nexusflow.backend.feature.task.domain.source.TrailCandidate
import com.nexusflow.backend.feature.task.domain.source.WeatherFact
import com.nexusflow.backend.feature.research.infrastructure.source.CachedSourceRefDocument
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
internal data class GeoPointDocument(
    val latitude: Double,
    val longitude: Double,
) {
    fun toDomain(): GeoPoint = GeoPoint(latitude, longitude)

    companion object {
        fun from(point: GeoPoint): GeoPointDocument = GeoPointDocument(point.latitude, point.longitude)
    }
}

@Serializable
internal data class TrailCandidateCacheDocument(
    val externalTrailId: String,
    val name: String,
    val routeType: String?,
    val distanceMeters: Int?,
    val elevationGainMeters: Int?,
    val startLocation: GeoPointDocument?,
    val summary: String?,
    val publicUrl: String?,
    val sources: List<CachedSourceRefDocument>,
) {
    fun toDomain(): TrailCandidate =
        TrailCandidate(
            externalTrailId = externalTrailId,
            name = name,
            routeType = routeType,
            distanceMeters = distanceMeters,
            elevationGainMeters = elevationGainMeters,
            startLocation = startLocation?.toDomain(),
            summary = summary,
            publicUrl = publicUrl,
            sources = sources.map(CachedSourceRefDocument::toDomain),
        )

    companion object {
        fun from(candidate: TrailCandidate): TrailCandidateCacheDocument =
            TrailCandidateCacheDocument(
                externalTrailId = candidate.externalTrailId,
                name = candidate.name,
                routeType = candidate.routeType,
                distanceMeters = candidate.distanceMeters,
                elevationGainMeters = candidate.elevationGainMeters,
                startLocation = candidate.startLocation?.let(GeoPointDocument::from),
                summary = candidate.summary,
                publicUrl = candidate.publicUrl,
                sources = candidate.sources.map(CachedSourceRefDocument::from),
            )
    }
}

@Serializable
internal data class PlaceCandidateCacheDocument(
    val externalPlaceId: String,
    val displayName: String,
    val point: GeoPointDocument,
    val publicUrl: String?,
    val sources: List<CachedSourceRefDocument>,
) {
    fun toDomain(): PlaceCandidate =
        PlaceCandidate(
            externalPlaceId = externalPlaceId,
            displayName = displayName,
            point = point.toDomain(),
            publicUrl = publicUrl,
            sources = sources.map(CachedSourceRefDocument::toDomain),
        )

    companion object {
        fun from(candidate: PlaceCandidate): PlaceCandidateCacheDocument =
            PlaceCandidateCacheDocument(
                externalPlaceId = candidate.externalPlaceId,
                displayName = candidate.displayName,
                point = GeoPointDocument.from(candidate.point),
                publicUrl = candidate.publicUrl,
                sources = candidate.sources.map(CachedSourceRefDocument::from),
            )
    }
}

@Serializable
internal data class RouteFactCacheDocument(
    val distanceMeters: Int?,
    val durationMinutes: Int?,
    val commuteMinutes: Int?,
    val source: CachedSourceRefDocument,
) {
    fun toDomain(): RouteFact =
        RouteFact(
            distanceMeters = distanceMeters,
            durationMinutes = durationMinutes,
            commuteMinutes = commuteMinutes,
            source = source.toDomain(),
        )

    companion object {
        fun from(fact: RouteFact): RouteFactCacheDocument =
            RouteFactCacheDocument(
                distanceMeters = fact.distanceMeters,
                durationMinutes = fact.durationMinutes,
                commuteMinutes = fact.commuteMinutes,
                source = CachedSourceRefDocument.from(fact.source),
            )
    }
}

@Serializable
internal data class WeatherFactCacheDocument(
    val summary: String?,
    val temperatureCelsius: Long?,
    val precipitationProbabilityPercent: Long?,
    val windSpeedKph: Long?,
    val source: CachedSourceRefDocument,
) {
    fun toDomain(): WeatherFact =
        WeatherFact(
            summary = summary,
            temperatureCelsius = temperatureCelsius,
            precipitationProbabilityPercent = precipitationProbabilityPercent,
            windSpeedKph = windSpeedKph,
            source = source.toDomain(),
        )

    companion object {
        fun from(fact: WeatherFact): WeatherFactCacheDocument =
            WeatherFactCacheDocument(
                summary = fact.summary,
                temperatureCelsius = fact.temperatureCelsius,
                precipitationProbabilityPercent = fact.precipitationProbabilityPercent,
                windSpeedKph = fact.windSpeedKph,
                source = CachedSourceRefDocument.from(fact.source),
            )
    }
}

@Serializable
internal data class MetNoWeatherCacheDocument(
    val value: WeatherFactCacheDocument?,
    val expiresAt: String,
    val lastModified: String?,
) {
    fun toDomain(): WeatherFact? = value?.toDomain()

    companion object {
        fun from(
            fact: WeatherFact?,
            expiresAt: Instant,
            lastModified: String?,
        ): MetNoWeatherCacheDocument =
            MetNoWeatherCacheDocument(
                value = fact?.let(WeatherFactCacheDocument::from),
                expiresAt = expiresAt.cacheText(),
                lastModified = lastModified,
            )
    }
}

internal fun Instant.cacheText(): String = toString()
