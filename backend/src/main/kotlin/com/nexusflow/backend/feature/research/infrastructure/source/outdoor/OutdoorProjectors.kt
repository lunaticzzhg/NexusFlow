package com.nexusflow.backend.feature.research.infrastructure.source.outdoor

import com.nexusflow.backend.feature.task.domain.OpportunityFactKey
import com.nexusflow.backend.feature.task.domain.SourceAuthority
import com.nexusflow.backend.feature.task.domain.SourceRef
import com.nexusflow.backend.feature.task.domain.source.GeoPoint
import com.nexusflow.backend.feature.task.domain.source.PlaceCandidate
import com.nexusflow.backend.feature.task.domain.source.RouteFact
import com.nexusflow.backend.feature.task.domain.source.TrailCandidate
import com.nexusflow.backend.feature.task.domain.source.WeatherFact
import java.time.Instant
import kotlin.math.roundToInt

internal fun OverpassElementDto.toTrailCandidate(observedAt: Instant): TrailCandidate {
    val osmType = type?.boundedId() ?: throw IllegalArgumentException("Overpass element type missing")
    val osmId = id ?: throw IllegalArgumentException("Overpass element id missing")
    val name = tags["name"].boundedText(MAX_NAME_CHARS) ?: throw IllegalArgumentException("Overpass trail name missing")
    val point = center?.toGeoPoint() ?: GeoPoint.from(lat, lon)
    val routeType = tags["route"].boundedText(MAX_TYPE_CHARS)
        ?: tags["natural"].boundedText(MAX_TYPE_CHARS)
        ?: tags["tourism"].boundedText(MAX_TYPE_CHARS)
    return TrailCandidate(
        externalTrailId = "$osmType:$osmId",
        name = name,
        routeType = routeType,
        distanceMeters = tags["distance"].toMetersOrNull(),
        elevationGainMeters = tags["ele"]?.toIntOrNull(),
        startLocation = point,
        summary = routeType,
        publicUrl = tags["website"].boundedUrl() ?: "https://www.openstreetmap.org/$osmType/$osmId",
        sources = listOf(
            SourceRef(
                label = "OpenStreetMap Overpass",
                uri = "https://www.openstreetmap.org/$osmType/$osmId",
                sourceUpdatedAt = observedAt,
                sourceId = "overpass",
                authority = SourceAuthority.StructuredPrimary,
                factKeys = buildSet {
                    add(OpportunityFactKey.Title)
                    add(OpportunityFactKey.TrailMetadata)
                    if (point != null) add(OpportunityFactKey.Location)
                },
            ),
        ),
    )
}

internal fun TrailSplitsTrailDto.toTrailCandidate(observedAt: Instant): TrailCandidate {
    val props = properties ?: throw IllegalArgumentException("TrailSplits trail properties missing")
    val trailId = props.osmRelationId?.takeIf { it > 0 }?.toString()
        ?: throw IllegalArgumentException("TrailSplits trail id missing")
    val title = props.name.boundedText(MAX_NAME_CHARS) ?: throw IllegalArgumentException("TrailSplits trail name missing")
    val point = geometry?.coordinates.toGeoPointFromLonLat()
        ?: throw IllegalArgumentException("TrailSplits trail point missing")
    val distance = props.distanceMeters ?: props.distanceKilometers?.times(1000.0)
    val publicUrl = "https://www.openstreetmap.org/relation/$trailId"
    return TrailCandidate(
        externalTrailId = trailId,
        name = title,
        routeType = props.routeType.boundedText(MAX_TYPE_CHARS),
        distanceMeters = distance?.takeIf { it.isFinite() && it >= 0 }?.roundToInt(),
        elevationGainMeters = null,
        startLocation = point,
        summary = props.routeType.boundedText(MAX_TYPE_CHARS),
        publicUrl = publicUrl,
        sources = listOf(
            SourceRef(
                label = "TrailSplits",
                uri = publicUrl,
                sourceUpdatedAt = observedAt,
                sourceId = "trailsplits",
                authority = SourceAuthority.StructuredSecondary,
                factKeys = buildSet {
                    add(OpportunityFactKey.Title)
                    add(OpportunityFactKey.TrailMetadata)
                    if (point != null) add(OpportunityFactKey.Location)
                },
            ),
        ),
    )
}

internal fun OpenRouteServiceFeatureDto.toPlaceCandidate(observedAt: Instant): PlaceCandidate {
    val label = properties?.label.boundedText(MAX_LOCATION_CHARS)
        ?: properties?.name.boundedText(MAX_LOCATION_CHARS)
        ?: throw IllegalArgumentException("OpenRouteService geocode label missing")
    val point = geometry?.coordinates.toGeoPointFromLonLat()
        ?: throw IllegalArgumentException("OpenRouteService geocode point missing")
    val placeId = properties?.id?.boundedId() ?: label
    return PlaceCandidate(
        externalPlaceId = placeId,
        displayName = label,
        point = point,
        publicUrl = null,
        sources = listOf(
            SourceRef(
                label = "openrouteservice Geocoding",
                uri = null,
                sourceUpdatedAt = observedAt,
                sourceId = "openrouteservice-geocode",
                authority = SourceAuthority.StructuredPrimary,
                factKeys = setOf(OpportunityFactKey.PlaceLookup, OpportunityFactKey.Location),
            ),
        ),
    )
}

internal fun NominatimPlaceDto.toPlaceCandidate(observedAt: Instant): PlaceCandidate {
    val label = displayName.boundedText(MAX_LOCATION_CHARS) ?: throw IllegalArgumentException("Nominatim label missing")
    val point = GeoPoint.from(lat?.toDoubleOrNull(), lon?.toDoubleOrNull())
        ?: throw IllegalArgumentException("Nominatim point missing")
    val id = listOfNotNull(osmType, osmId?.toString()).joinToString(":").ifBlank { label }
    return PlaceCandidate(
        externalPlaceId = id,
        displayName = label,
        point = point,
        publicUrl = osmType?.let { type -> osmId?.let { "https://www.openstreetmap.org/$type/$it" } },
        sources = listOf(
            SourceRef(
                label = "Nominatim",
                uri = null,
                sourceUpdatedAt = observedAt,
                sourceId = "nominatim",
                authority = SourceAuthority.StructuredSecondary,
                factKeys = setOf(OpportunityFactKey.PlaceLookup, OpportunityFactKey.Location),
            ),
        ),
    )
}

internal fun RouteSummaryDto.toRouteFact(
    observedAt: Instant,
    sourceId: String,
    label: String,
    authority: SourceAuthority,
): RouteFact {
    require(distance != null && distance.isFinite() && distance >= 0.0) { "$sourceId route distance missing" }
    require(duration != null && duration.isFinite() && duration >= 0.0) { "$sourceId route duration missing" }
    val durationMinutes = duration?.takeIf { it >= 0.0 }?.secondsToMinutes()
    return RouteFact(
        distanceMeters = distance?.takeIf { it >= 0.0 }?.roundToInt(),
        durationMinutes = durationMinutes,
        commuteMinutes = durationMinutes,
        source = SourceRef(
            label = label,
            uri = null,
            sourceUpdatedAt = observedAt,
            sourceId = sourceId,
            authority = authority,
            factKeys = setOf(OpportunityFactKey.Route),
        ),
    )
}

internal fun TrailSplitsRouteDto.toRouteFact(observedAt: Instant): RouteFact {
    require(distance != null && distance.isFinite() && distance >= 0.0) { "TrailSplits route distance missing" }
    require(duration != null && duration.isFinite() && duration >= 0.0) { "TrailSplits route duration missing" }
    val durationMinutes = duration.secondsToMinutes()
    return RouteFact(
        distanceMeters = distance.roundToInt(),
        durationMinutes = durationMinutes,
        commuteMinutes = durationMinutes,
        source = SourceRef(
            label = "TrailSplits",
            uri = null,
            sourceUpdatedAt = observedAt,
            sourceId = "trailsplits",
            authority = SourceAuthority.StructuredSecondary,
            factKeys = setOf(OpportunityFactKey.Route),
        ),
    )
}

internal fun OpenMeteoForecastResponse.toWeatherFactOrNull(observedAt: Instant): WeatherFact? {
    val currentTemperature = current?.temperatureCelsius ?: current_weather?.temperature
    val currentWind = current?.windSpeedKph ?: current_weather?.windspeed
    val code = current?.weatherCode ?: current_weather?.weathercode
    if (currentTemperature == null && currentWind == null && code == null && current?.precipitationProbability == null) {
        return null
    }
    return WeatherFact(
        summary = code?.let { "weather_code:$it" },
        temperatureCelsius = currentTemperature?.roundToLongOrNull(),
        precipitationProbabilityPercent = current?.precipitationProbability?.roundToLongOrNull(),
        windSpeedKph = currentWind?.roundToLongOrNull(),
        source = SourceRef(
            label = "Open-Meteo",
            uri = null,
            sourceUpdatedAt = observedAt,
            sourceId = "open-meteo",
            authority = SourceAuthority.StructuredSecondary,
            factKeys = setOf(OpportunityFactKey.Weather),
        ),
    )
}

internal fun MetNoForecastResponse.toWeatherFactOrNull(observedAt: Instant): WeatherFact? {
    val entry = properties?.timeseries?.firstOrNull()?.data
        ?: return null
    val details = entry.instant?.details
    val summary = entry.nextOneHour?.summary?.symbolCode.boundedText(MAX_TYPE_CHARS)
    if (details?.airTemperature == null && details?.windSpeed == null && summary == null) {
        return null
    }
    return WeatherFact(
        summary = summary,
        temperatureCelsius = details?.airTemperature?.roundToLongOrNull(),
        precipitationProbabilityPercent = null,
        windSpeedKph = details?.windSpeed?.roundToLongOrNull(),
        source = SourceRef(
            label = "MET Norway",
            uri = null,
            sourceUpdatedAt = observedAt,
            sourceId = "met-no",
            authority = SourceAuthority.StructuredPrimary,
            factKeys = setOf(OpportunityFactKey.Weather),
        ),
    )
}

private fun OverpassCenterDto.toGeoPoint(): GeoPoint? = GeoPoint.from(lat, lon)

private fun GeoPoint.Companion.from(
    latitude: Double?,
    longitude: Double?,
): GeoPoint? =
    if (latitude != null && longitude != null && latitude in -90.0..90.0 && longitude in -180.0..180.0) {
        GeoPoint(latitude, longitude)
    } else {
        null
    }

private fun List<Double>?.toGeoPointFromLonLat(): GeoPoint? =
    if (this != null && size >= 2) GeoPoint.from(latitude = this[1], longitude = this[0]) else null

private fun String?.boundedText(maxChars: Int): String? =
    this?.trim()?.take(maxChars)?.takeIf(String::isNotBlank)

private fun String?.boundedId(): String? =
    boundedText(MAX_ID_CHARS)

private fun String?.boundedUrl(): String? =
    this?.trim()?.take(MAX_URL_CHARS)?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

private fun String?.toMetersOrNull(): Int? =
    this?.filter { it.isDigit() || it == '.' }
        ?.toDoubleOrNull()
        ?.takeIf { it >= 0.0 }
        ?.roundToInt()

private fun Double.secondsToMinutes(): Int =
    (this / 60.0).roundToInt().coerceAtLeast(0)

private fun Double.roundToLongOrNull(): Long? =
    takeIf { it.isFinite() }?.roundToInt()?.toLong()

private const val MAX_ID_CHARS = 160
private const val MAX_NAME_CHARS = 180
private const val MAX_TYPE_CHARS = 80
private const val MAX_LOCATION_CHARS = 220
private const val MAX_URL_CHARS = 2_048
