package com.nexusflow.backend.feature.research.infrastructure.source.outdoor

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class OverpassResponse(
    val elements: List<OverpassElementDto> = emptyList(),
)

@Serializable
internal data class OverpassElementDto(
    val type: String? = null,
    val id: Long? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val center: OverpassCenterDto? = null,
    val tags: Map<String, String> = emptyMap(),
)

@Serializable
internal data class OverpassCenterDto(
    val lat: Double? = null,
    val lon: Double? = null,
)

@Serializable
internal data class TrailSplitsTrailSearchResponse(
    val features: List<TrailSplitsTrailDto>?,
)

@Serializable
internal data class TrailSplitsTrailDto(
    val properties: TrailSplitsTrailPropertiesDto? = null,
    val geometry: TrailSplitsGeometryDto? = null,
)

@Serializable
internal data class TrailSplitsTrailPropertiesDto(
    @SerialName("osm_relation_id") val osmRelationId: Long? = null,
    val name: String? = null,
    @SerialName("route_type") val routeType: String? = null,
    @SerialName("distance_m") val distanceMeters: Double? = null,
    @SerialName("distance_km") val distanceKilometers: Double? = null,
)

@Serializable
internal data class TrailSplitsGeometryDto(
    val coordinates: List<Double> = emptyList(),
)

@Serializable
internal data class OpenRouteServiceGeocodeResponse(
    val features: List<OpenRouteServiceFeatureDto> = emptyList(),
)

@Serializable
internal data class OpenRouteServiceFeatureDto(
    val properties: OpenRouteServicePropertiesDto? = null,
    val geometry: OpenRouteServiceGeometryDto? = null,
)

@Serializable
internal data class OpenRouteServicePropertiesDto(
    val id: String? = null,
    val label: String? = null,
    val name: String? = null,
)

@Serializable
internal data class OpenRouteServiceGeometryDto(
    val coordinates: List<Double> = emptyList(),
)

@Serializable
internal data class NominatimPlaceDto(
    @SerialName("osm_type")
    val osmType: String? = null,
    @SerialName("osm_id")
    val osmId: Long? = null,
    @SerialName("display_name")
    val displayName: String? = null,
    val lat: String? = null,
    val lon: String? = null,
)

@Serializable
internal data class OpenRouteServiceRouteResponse(
    val routes: List<OpenRouteServiceRouteDto>?,
)

@Serializable
internal data class OpenRouteServiceRouteDto(
    val summary: RouteSummaryDto? = null,
)

@Serializable
internal data class RouteSummaryDto(
    val distance: Double? = null,
    val duration: Double? = null,
)

@Serializable
internal data class TrailSplitsRouteResponse(
    val routes: List<TrailSplitsRouteDto>?,
)

@Serializable
internal data class TrailSplitsRouteDto(
    val distance: Double? = null,
    val duration: Double? = null,
)

@Serializable
internal data class OpenMeteoForecastResponse(
    val current: OpenMeteoCurrentDto? = null,
    val current_weather: OpenMeteoCurrentWeatherDto? = null,
    val hourly: JsonElement? = null,
)

@Serializable
internal data class OpenMeteoCurrentDto(
    @SerialName("temperature_2m")
    val temperatureCelsius: Double? = null,
    @SerialName("precipitation_probability")
    val precipitationProbability: Double? = null,
    @SerialName("wind_speed_10m")
    val windSpeedKph: Double? = null,
    @SerialName("weather_code")
    val weatherCode: Int? = null,
)

@Serializable
internal data class OpenMeteoCurrentWeatherDto(
    val temperature: Double? = null,
    val windspeed: Double? = null,
    val weathercode: Int? = null,
)

@Serializable
internal data class MetNoForecastResponse(
    val properties: MetNoPropertiesDto? = null,
)

@Serializable
internal data class MetNoPropertiesDto(
    val timeseries: List<MetNoTimeSeriesDto> = emptyList(),
)

@Serializable
internal data class MetNoTimeSeriesDto(
    val data: MetNoDataDto? = null,
)

@Serializable
internal data class MetNoDataDto(
    val instant: MetNoInstantDto? = null,
    @SerialName("next_1_hours")
    val nextOneHour: MetNoNextHoursDto? = null,
)

@Serializable
internal data class MetNoInstantDto(
    val details: MetNoInstantDetailsDto? = null,
)

@Serializable
internal data class MetNoInstantDetailsDto(
    @SerialName("air_temperature")
    val airTemperature: Double? = null,
    @SerialName("wind_speed")
    val windSpeed: Double? = null,
)

@Serializable
internal data class MetNoNextHoursDto(
    val summary: MetNoSummaryDto? = null,
    val details: MetNoNextHoursDetailsDto? = null,
)

@Serializable
internal data class MetNoSummaryDto(
    @SerialName("symbol_code")
    val symbolCode: String? = null,
)

@Serializable
internal data class MetNoNextHoursDetailsDto(
    @SerialName("precipitation_amount")
    val precipitationAmount: Double? = null,
)
