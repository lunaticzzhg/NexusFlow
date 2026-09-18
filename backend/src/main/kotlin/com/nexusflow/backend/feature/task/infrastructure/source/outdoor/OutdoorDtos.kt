package com.nexusflow.backend.feature.task.infrastructure.source.outdoor

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
    val trails: List<TrailSplitsTrailDto> = emptyList(),
)

@Serializable
internal data class TrailSplitsTrailDto(
    val id: String? = null,
    val name: String? = null,
    val type: String? = null,
    val distanceMeters: Int? = null,
    val elevationGainMeters: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val url: String? = null,
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
internal data class RouteFeatureCollectionDto(
    val features: List<RouteFeatureDto> = emptyList(),
)

@Serializable
internal data class RouteFeatureDto(
    val properties: RoutePropertiesDto? = null,
)

@Serializable
internal data class RoutePropertiesDto(
    val summary: RouteSummaryDto? = null,
)

@Serializable
internal data class RouteSummaryDto(
    val distance: Double? = null,
    val duration: Double? = null,
)

@Serializable
internal data class TrailSplitsRouteResponse(
    val routes: List<TrailSplitsRouteDto> = emptyList(),
)

@Serializable
internal data class TrailSplitsRouteDto(
    val distanceMeters: Int? = null,
    val durationSeconds: Int? = null,
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
