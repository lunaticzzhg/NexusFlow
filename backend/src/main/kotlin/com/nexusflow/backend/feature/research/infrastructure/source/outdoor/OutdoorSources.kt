package com.nexusflow.backend.feature.research.infrastructure.source.outdoor

import com.nexusflow.backend.core.config.MetNoRuntimeConfig
import com.nexusflow.backend.core.config.NominatimRuntimeConfig
import com.nexusflow.backend.core.config.OpenMeteoRuntimeConfig
import com.nexusflow.backend.core.config.OpenRouteServiceRuntimeConfig
import com.nexusflow.backend.core.config.OverpassRuntimeConfig
import com.nexusflow.backend.core.config.TrailSplitsRuntimeConfig
import com.nexusflow.backend.core.external.ExternalSourceHttpClient
import com.nexusflow.backend.core.external.ExternalSourceUnavailableException
import com.nexusflow.backend.core.external.SourceCacheCodec
import com.nexusflow.backend.core.external.SourceCacheKey
import com.nexusflow.backend.core.external.SourceCacheResultKind
import com.nexusflow.backend.core.external.SourceCacheStore
import com.nexusflow.backend.core.external.SourceCacheTtlPolicy
import com.nexusflow.backend.core.external.executeExternalSourceRequest
import com.nexusflow.backend.core.external.rejectKnownExternalSourceStatus
import com.nexusflow.backend.feature.task.domain.SourceAuthority
import com.nexusflow.backend.feature.task.domain.source.PlaceCandidate
import com.nexusflow.backend.feature.task.domain.source.PlaceLookupQuery
import com.nexusflow.backend.feature.task.domain.source.PlaceLookupSource
import com.nexusflow.backend.feature.task.domain.source.RouteFact
import com.nexusflow.backend.feature.task.domain.source.RouteQuery
import com.nexusflow.backend.feature.task.domain.source.RouteSource
import com.nexusflow.backend.feature.task.domain.source.TrailCandidate
import com.nexusflow.backend.feature.task.domain.source.TrailDiscoveryQuery
import com.nexusflow.backend.feature.task.domain.source.TrailDiscoverySource
import com.nexusflow.backend.feature.task.domain.source.WeatherFact
import com.nexusflow.backend.feature.task.domain.source.WeatherQuery
import com.nexusflow.backend.feature.task.domain.source.WeatherSource
import com.nexusflow.observability.StructuredLogger
import com.nexusflow.observability.logFields
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

class OverpassTrailSource(
    private val http: ExternalSourceHttpClient,
    private val config: OverpassRuntimeConfig,
    private val cacheStore: SourceCacheStore? = null,
    private val logger: StructuredLogger? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val cachePolicy: SourceCacheTtlPolicy = defaultListCachePolicy(),
) : TrailDiscoverySource {
    override suspend fun search(query: TrailDiscoveryQuery): List<TrailCandidate> {
        if (query.center == null) {
            logAcquisition(logger, "overpass", "trail_discovery", "search", "empty", 0, cacheHit = false)
            return emptyList()
        }
        val key = SourceCacheKey("source:v1:overpass:trail-discovery:${sha256(query.canonicalTrailRequest())}")
        cacheStore?.get(key)?.let { bytes ->
            val cached = TrailCandidateListCacheCodec.decode(bytes)
            logAcquisition(logger, "overpass", "trail_discovery", "search", "success", cached.size, cacheHit = true)
            return cached
        }

        val candidates = executeExternalSourceRequest("overpass", "search") {
            val response = http.client.post("${config.baseUrl.trimEnd('/')}/interpreter") {
                parameter("data", query.toOverpassQuery())
            }
            response.rejectKnownExternalSourceStatus("overpass", "search")
            val observedAt = clock.instant()
            response.body<OverpassResponse>()
                .elements
                .map { it.toTrailCandidate(observedAt) }
                .take(MAX_RESULTS)
        }

        cacheStore?.put(key, TrailCandidateListCacheCodec.encode(candidates), cachePolicy.ttlFor(candidates.resultKind()))
        logAcquisition(logger, "overpass", "trail_discovery", "search", if (candidates.isEmpty()) "empty" else "success", candidates.size, false)
        return candidates
    }

    private fun TrailDiscoveryQuery.toOverpassQuery(): String {
        val safeKeyword = keyword.toOverpassRegexLiteral()
        val center = requireNotNull(center) { "Overpass trail discovery requires a bounded center" }
        return """
            [out:json][timeout:8];
            (
              relation["route"="hiking"]["name"~"$safeKeyword",i](around:$radiusMeters,${center.latitude},${center.longitude});
              way["highway"="path"]["name"~"$safeKeyword",i](around:$radiusMeters,${center.latitude},${center.longitude});
              node["natural"="peak"]["name"~"$safeKeyword",i](around:$radiusMeters,${center.latitude},${center.longitude});
            );
            out center 20;
        """.trimIndent()
    }
}

class TrailSplitsTrailSource(
    private val http: ExternalSourceHttpClient,
    private val config: TrailSplitsRuntimeConfig,
    private val cacheStore: SourceCacheStore? = null,
    private val logger: StructuredLogger? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val cachePolicy: SourceCacheTtlPolicy = defaultListCachePolicy(),
) : TrailDiscoverySource {
    override suspend fun search(query: TrailDiscoveryQuery): List<TrailCandidate> {
        val key = SourceCacheKey("source:v1:trailsplits:trail-discovery:${sha256(query.canonicalTrailRequest())}")
        cacheStore?.get(key)?.let { bytes ->
            val cached = TrailCandidateListCacheCodec.decode(bytes)
            logAcquisition(logger, "trailsplits", "trail_discovery", "search", "success", cached.size, cacheHit = true)
            return cached
        }

        val candidates = executeExternalSourceRequest("trailsplits", "search") {
            val response = http.client.get("${config.baseUrl.trimEnd('/')}/v1/trails/search") {
                parameter("q", query.keyword)
                query.near?.let { parameter("near", it) }
                parameter("limit", MAX_RESULTS.toString())
            }
            response.rejectKnownExternalSourceStatus("trailsplits", "search")
            val observedAt = clock.instant()
            response.body<TrailSplitsTrailSearchResponse>()
                .trails
                .map { it.toTrailCandidate(observedAt) }
                .take(MAX_RESULTS)
        }

        cacheStore?.put(key, TrailCandidateListCacheCodec.encode(candidates), cachePolicy.ttlFor(candidates.resultKind()))
        logAcquisition(logger, "trailsplits", "trail_discovery", "search", if (candidates.isEmpty()) "empty" else "success", candidates.size, false)
        return candidates
    }
}

class OpenRouteServicePlaceSource(
    private val http: ExternalSourceHttpClient,
    private val config: OpenRouteServiceRuntimeConfig,
    private val cacheStore: SourceCacheStore? = null,
    private val logger: StructuredLogger? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val cachePolicy: SourceCacheTtlPolicy = defaultListCachePolicy(),
) : PlaceLookupSource {
    override suspend fun find(query: PlaceLookupQuery): List<PlaceCandidate> {
        val key = SourceCacheKey("source:v1:openrouteservice:place-lookup:${sha256(query.canonicalPlaceRequest())}")
        cacheStore?.get(key)?.let { bytes ->
            val cached = PlaceCandidateListCacheCodec.decode(bytes)
            logAcquisition(logger, "openrouteservice-geocode", "place_lookup", "find", "success", cached.size, cacheHit = true)
            return cached
        }

        val candidates = executeExternalSourceRequest("openrouteservice-geocode", "find") {
            val response = http.client.get("${config.geocodeBaseUrl.trimEnd('/')}/geocode/search") {
                parameter("api_key", config.apiKey)
                parameter("text", listOfNotNull(query.text, query.near).joinToString(" "))
                parameter("size", MAX_RESULTS.toString())
            }
            response.rejectKnownExternalSourceStatus("openrouteservice-geocode", "find")
            val observedAt = clock.instant()
            response.body<OpenRouteServiceGeocodeResponse>()
                .features
                .map { it.toPlaceCandidate(observedAt) }
                .take(MAX_RESULTS)
        }

        cacheStore?.put(key, PlaceCandidateListCacheCodec.encode(candidates), cachePolicy.ttlFor(candidates.resultKind()))
        logAcquisition(logger, "openrouteservice-geocode", "place_lookup", "find", if (candidates.isEmpty()) "empty" else "success", candidates.size, false)
        return candidates
    }
}

class NominatimPlaceSource(
    private val http: ExternalSourceHttpClient,
    private val config: NominatimRuntimeConfig,
    private val cacheStore: SourceCacheStore? = null,
    private val logger: StructuredLogger? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val cachePolicy: SourceCacheTtlPolicy = defaultListCachePolicy(),
) : PlaceLookupSource {
    override suspend fun find(query: PlaceLookupQuery): List<PlaceCandidate> {
        val key = SourceCacheKey("source:v1:nominatim:place-lookup:${sha256(query.canonicalPlaceRequest())}")
        cacheStore?.get(key)?.let { bytes ->
            val cached = PlaceCandidateListCacheCodec.decode(bytes)
            logAcquisition(logger, "nominatim", "place_lookup", "find", "success", cached.size, cacheHit = true)
            return cached
        }

        val candidates = executeExternalSourceRequest("nominatim", "find") {
            val response = http.client.get("${config.baseUrl.trimEnd('/')}/search") {
                parameter("q", listOfNotNull(query.text, query.near).joinToString(" "))
                parameter("format", "jsonv2")
                parameter("limit", MAX_RESULTS.toString())
            }
            response.rejectKnownExternalSourceStatus("nominatim", "find")
            val observedAt = clock.instant()
            response.body<List<NominatimPlaceDto>>()
                .map { it.toPlaceCandidate(observedAt) }
                .take(MAX_RESULTS)
        }

        cacheStore?.put(key, PlaceCandidateListCacheCodec.encode(candidates), cachePolicy.ttlFor(candidates.resultKind()))
        logAcquisition(logger, "nominatim", "place_lookup", "find", if (candidates.isEmpty()) "empty" else "success", candidates.size, false)
        return candidates
    }
}

class OpenRouteServiceRouteSource(
    private val http: ExternalSourceHttpClient,
    private val config: OpenRouteServiceRuntimeConfig,
    private val cacheStore: SourceCacheStore? = null,
    private val logger: StructuredLogger? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val cachePolicy: SourceCacheTtlPolicy = defaultFactCachePolicy(),
) : RouteSource {
    override suspend fun route(query: RouteQuery): RouteFact? {
        if (query.origin == null) {
            logAcquisition(logger, "openrouteservice-route", "routing", "route", "empty", 0, false)
            return null
        }
        val key = SourceCacheKey("source:v1:openrouteservice:routing:${sha256(query.canonicalRouteRequest())}")
        cacheStore?.get(key)?.let { bytes ->
            val cached = RouteFactNullableCacheCodec.decode(bytes)
            logAcquisition(logger, "openrouteservice-route", "routing", "route", if (cached == null) "empty" else "success", if (cached == null) 0 else 1, true)
            return cached
        }

        val fact = executeExternalSourceRequest("openrouteservice-route", "route") {
            val response = http.client.post("${config.routingBaseUrl.trimEnd('/')}/v2/directions/foot-hiking") {
                header(HttpHeaders.Authorization, config.apiKey)
                contentType(ContentType.Application.Json)
                setBody(query.toOpenRouteServiceRequestBody())
            }
            response.rejectKnownExternalSourceStatus("openrouteservice-route", "route")
            val observedAt = clock.instant()
            response.body<RouteFeatureCollectionDto>()
                .features
                .firstOrNull()
                ?.properties
                ?.summary
                ?.toRouteFact(observedAt, "openrouteservice-route", "openrouteservice Routing", SourceAuthority.StructuredPrimary)
        }

        cacheStore?.put(key, RouteFactNullableCacheCodec.encode(fact), cachePolicy.ttlFor(if (fact == null) SourceCacheResultKind.SuccessEmpty else SourceCacheResultKind.Success))
        logAcquisition(logger, "openrouteservice-route", "routing", "route", if (fact == null) "empty" else "success", if (fact == null) 0 else 1, false)
        return fact
    }
}

class TrailSplitsRouteSource(
    private val http: ExternalSourceHttpClient,
    private val config: TrailSplitsRuntimeConfig,
    private val cacheStore: SourceCacheStore? = null,
    private val logger: StructuredLogger? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val cachePolicy: SourceCacheTtlPolicy = defaultFactCachePolicy(),
) : RouteSource {
    override suspend fun route(query: RouteQuery): RouteFact? {
        if (query.origin == null) {
            logAcquisition(logger, "trailsplits", "routing", "route", "empty", 0, false)
            return null
        }
        val key = SourceCacheKey("source:v1:trailsplits:routing:${sha256(query.canonicalRouteRequest())}")
        cacheStore?.get(key)?.let { bytes ->
            val cached = RouteFactNullableCacheCodec.decode(bytes)
            logAcquisition(logger, "trailsplits", "routing", "route", if (cached == null) "empty" else "success", if (cached == null) 0 else 1, true)
            return cached
        }

        val fact = executeExternalSourceRequest("trailsplits", "route") {
            val response = http.client.get("${config.baseUrl.trimEnd('/')}/v1/routes/hiking") {
                parameter("to", "${query.destination.latitude},${query.destination.longitude}")
                query.origin?.let { parameter("from", "${it.latitude},${it.longitude}") }
            }
            response.rejectKnownExternalSourceStatus("trailsplits", "route")
            val observedAt = clock.instant()
            response.body<TrailSplitsRouteResponse>()
                .routes
                .firstOrNull()
                ?.toRouteFact(observedAt)
        }

        cacheStore?.put(key, RouteFactNullableCacheCodec.encode(fact), cachePolicy.ttlFor(if (fact == null) SourceCacheResultKind.SuccessEmpty else SourceCacheResultKind.Success))
        logAcquisition(logger, "trailsplits", "routing", "route", if (fact == null) "empty" else "success", if (fact == null) 0 else 1, false)
        return fact
    }
}

class OpenMeteoWeatherSource(
    private val http: ExternalSourceHttpClient,
    private val config: OpenMeteoRuntimeConfig,
    private val cacheStore: SourceCacheStore? = null,
    private val logger: StructuredLogger? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val cachePolicy: SourceCacheTtlPolicy = defaultFactCachePolicy(),
) : WeatherSource {
    override suspend fun forecast(query: WeatherQuery): WeatherFact? {
        val key = SourceCacheKey("source:v1:open-meteo:weather:${sha256(query.canonicalWeatherRequest())}")
        cacheStore?.get(key)?.let { bytes ->
            val cached = WeatherFactNullableCacheCodec.decode(bytes)
            logAcquisition(logger, "open-meteo", "weather", "forecast", if (cached == null) "empty" else "success", if (cached == null) 0 else 1, true)
            return cached
        }

        val fact = executeExternalSourceRequest("open-meteo", "forecast") {
            val response = http.client.get("${config.baseUrl.trimEnd('/')}/v1/forecast") {
                parameter("latitude", query.point.latitude.toString())
                parameter("longitude", query.point.longitude.toString())
                parameter("current", "temperature_2m,precipitation_probability,wind_speed_10m,weather_code")
                parameter("forecast_days", "7")
            }
            response.rejectKnownExternalSourceStatus("open-meteo", "forecast")
            response.body<OpenMeteoForecastResponse>().toWeatherFactOrNull(clock.instant())
        }

        cacheStore?.put(key, WeatherFactNullableCacheCodec.encode(fact), cachePolicy.ttlFor(if (fact == null) SourceCacheResultKind.SuccessEmpty else SourceCacheResultKind.Success))
        logAcquisition(logger, "open-meteo", "weather", "forecast", if (fact == null) "empty" else "success", if (fact == null) 0 else 1, false)
        return fact
    }
}

class MetNoWeatherSource(
    private val http: ExternalSourceHttpClient,
    private val config: MetNoRuntimeConfig,
    private val userAgent: String,
    private val cacheStore: SourceCacheStore? = null,
    private val logger: StructuredLogger? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val cachePolicy: SourceCacheTtlPolicy = defaultFactCachePolicy(),
) : WeatherSource {
    override suspend fun forecast(query: WeatherQuery): WeatherFact? {
        val key = SourceCacheKey("source:v1:met-no:weather:${sha256(query.canonicalWeatherRequest())}")
        val now = clock.instant()
        val cached = cacheStore?.get(key)?.let(MetNoWeatherCacheCodec::decode)
        if (cached != null && cached.expiresAtInstant()?.isAfter(now) == true) {
            val fact = cached.toDomain()
            logAcquisition(logger, "met-no", "weather", "forecast", if (fact == null) "empty" else "success", if (fact == null) 0 else 1, true)
            return fact
        }

        val fact = executeExternalSourceRequest("met-no", "forecast") {
            val response = http.client.get("${config.baseUrl.trimEnd('/')}/weatherapi/locationforecast/2.0/compact") {
                header(HttpHeaders.UserAgent, userAgent)
                cached?.lastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
                parameter("lat", query.point.latitude.toString())
                parameter("lon", query.point.longitude.toString())
            }
            if (response.status == HttpStatusCode.NotModified) {
                val refreshed = cached ?: throw ExternalSourceUnavailableException("met-no", "forecast")
                val refreshedFact = refreshed.toDomain()
                val refreshedDocument = MetNoWeatherCacheDocument.from(
                    fact = refreshedFact,
                    expiresAt = response.providerExpiresAt(now, refreshedFact.weatherResultKind()),
                    lastModified = response.providerLastModified() ?: refreshed.lastModified,
                )
                cacheStore?.put(key, MetNoWeatherCacheCodec.encode(refreshedDocument), storageTtlFor(refreshedDocument, refreshedFact.weatherResultKind(), now))
                return@executeExternalSourceRequest refreshedFact
            }
            response.rejectKnownExternalSourceStatus("met-no", "forecast")
            val projected = response.body<MetNoForecastResponse>().toWeatherFactOrNull(clock.instant())
            val document = MetNoWeatherCacheDocument.from(
                fact = projected,
                expiresAt = response.providerExpiresAt(now, projected.weatherResultKind()),
                lastModified = response.providerLastModified(),
            )
            cacheStore?.put(key, MetNoWeatherCacheCodec.encode(document), storageTtlFor(document, projected.weatherResultKind(), now))
            projected
        }

        logAcquisition(logger, "met-no", "weather", "forecast", if (fact == null) "empty" else "success", if (fact == null) 0 else 1, false)
        return fact
    }

    private fun HttpResponse.providerExpiresAt(
        now: Instant,
        kind: SourceCacheResultKind,
    ): Instant =
        headers[HttpHeaders.Expires]?.parseHttpDate()
            ?: now.plus(cachePolicy.ttlFor(kind))

    private fun HttpResponse.providerLastModified(): String? =
        headers[HttpHeaders.LastModified]?.takeIf { it.parseHttpDate() != null }

    private fun storageTtlFor(
        document: MetNoWeatherCacheDocument,
        kind: SourceCacheResultKind,
        now: Instant,
    ): Duration {
        val fallbackTtl = cachePolicy.ttlFor(kind)
        val providerTtl = document.expiresAtInstant()
            ?.let { Duration.between(now, it) }
            ?.takeIf { it.isPositive() }
        return (providerTtl ?: fallbackTtl).coerceAtLeast(fallbackTtl).plus(fallbackTtl)
    }
}

private fun logAcquisition(
    logger: StructuredLogger?,
    sourceId: String,
    capability: String,
    operation: String,
    outcome: String,
    resultCount: Int,
    cacheHit: Boolean,
) {
    logger?.info(
        component = "external_source",
        event = "source_acquisition_finished",
        fields = logFields {
            "source_id" value sourceId
            "capability" value capability
            "operation" value operation
            "outcome" value outcome
            "result_count" value resultCount
            "cache_mode" value "memory"
            "cache_hit" value cacheHit
            "external_call_avoided" value cacheHit
        },
    )
}

private fun <T> List<T>.resultKind(): SourceCacheResultKind =
    if (isEmpty()) SourceCacheResultKind.SuccessEmpty else SourceCacheResultKind.Success

private fun defaultListCachePolicy(): SourceCacheTtlPolicy =
    SourceCacheTtlPolicy(successTtl = Duration.ofMinutes(45), emptyTtl = Duration.ofMinutes(10))

private fun defaultFactCachePolicy(): SourceCacheTtlPolicy =
    SourceCacheTtlPolicy(successTtl = Duration.ofMinutes(30), emptyTtl = Duration.ofMinutes(5))

private fun WeatherFact?.weatherResultKind(): SourceCacheResultKind =
    if (this == null) SourceCacheResultKind.SuccessEmpty else SourceCacheResultKind.Success

private fun TrailDiscoveryQuery.canonicalTrailRequest(): String =
    "keyword=${keyword.normalized()}|near=${near.orEmpty().normalized()}|center=${center?.latitude ?: ""},${center?.longitude ?: ""}|origin=${origin?.latitude ?: ""},${origin?.longitude ?: ""}|radius=$radiusMeters|from=${dateFrom ?: ""}|to=${dateTo ?: ""}"

private fun PlaceLookupQuery.canonicalPlaceRequest(): String =
    "text=${text.normalized()}|near=${near.orEmpty().normalized()}"

private fun RouteQuery.canonicalRouteRequest(): String =
    "profile=$profile|to=${destination.latitude},${destination.longitude}|from=${origin?.latitude ?: ""},${origin?.longitude ?: ""}"

private fun WeatherQuery.canonicalWeatherRequest(): String =
    "point=${point.latitude},${point.longitude}|from=${dateFrom ?: ""}|to=${dateTo ?: ""}"

private fun RouteQuery.toOpenRouteServiceRequestBody(): String {
    val start = requireNotNull(origin) { "OpenRouteService routing requires a typed origin" }
    return """
        {"coordinates":[[${start.longitude},${start.latitude}],[${destination.longitude},${destination.latitude}]]}
    """.trimIndent()
}

private fun String.normalized(): String =
    lowercase().replace(Regex("\\s+"), " ").trim()

private fun String.toOverpassRegexLiteral(): String =
    asSequence()
        .mapNotNull { char ->
            when {
                char == '"' || char == '\\' -> null
                char.isISOControl() -> ' '
                else -> char
            }
        }
        .joinToString("")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(80)
        .ifBlank { "trail" }

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

private object TrailCandidateListCacheCodec : SourceCacheCodec<List<TrailCandidate>> {
    override fun encode(value: List<TrailCandidate>): ByteArray =
        Json.encodeToString(ListSerializer(TrailCandidateCacheDocument.serializer()), value.map(TrailCandidateCacheDocument::from))
            .toByteArray()

    override fun decode(bytes: ByteArray): List<TrailCandidate> =
        Json.decodeFromString(ListSerializer(TrailCandidateCacheDocument.serializer()), bytes.decodeToString())
            .map(TrailCandidateCacheDocument::toDomain)
}

private object PlaceCandidateListCacheCodec : SourceCacheCodec<List<PlaceCandidate>> {
    override fun encode(value: List<PlaceCandidate>): ByteArray =
        Json.encodeToString(ListSerializer(PlaceCandidateCacheDocument.serializer()), value.map(PlaceCandidateCacheDocument::from))
            .toByteArray()

    override fun decode(bytes: ByteArray): List<PlaceCandidate> =
        Json.decodeFromString(ListSerializer(PlaceCandidateCacheDocument.serializer()), bytes.decodeToString())
            .map(PlaceCandidateCacheDocument::toDomain)
}

private object RouteFactNullableCacheCodec : SourceCacheCodec<RouteFact?> {
    override fun encode(value: RouteFact?): ByteArray =
        Json.encodeToString(NullableRouteFactDocument.serializer(), NullableRouteFactDocument(value?.let(RouteFactCacheDocument::from)))
            .toByteArray()

    override fun decode(bytes: ByteArray): RouteFact? =
        Json.decodeFromString(NullableRouteFactDocument.serializer(), bytes.decodeToString()).value?.toDomain()
}

private object WeatherFactNullableCacheCodec : SourceCacheCodec<WeatherFact?> {
    override fun encode(value: WeatherFact?): ByteArray =
        Json.encodeToString(NullableWeatherFactDocument.serializer(), NullableWeatherFactDocument(value?.let(WeatherFactCacheDocument::from)))
            .toByteArray()

    override fun decode(bytes: ByteArray): WeatherFact? =
        Json.decodeFromString(NullableWeatherFactDocument.serializer(), bytes.decodeToString()).value?.toDomain()
}

private object MetNoWeatherCacheCodec : SourceCacheCodec<MetNoWeatherCacheDocument> {
    override fun encode(value: MetNoWeatherCacheDocument): ByteArray =
        Json.encodeToString(MetNoWeatherCacheDocument.serializer(), value)
            .toByteArray()

    override fun decode(bytes: ByteArray): MetNoWeatherCacheDocument =
        Json.decodeFromString(MetNoWeatherCacheDocument.serializer(), bytes.decodeToString())
}

@Serializable
private data class NullableRouteFactDocument(
    val value: RouteFactCacheDocument?,
)

@Serializable
private data class NullableWeatherFactDocument(
    val value: WeatherFactCacheDocument?,
)

private fun MetNoWeatherCacheDocument.expiresAtInstant(): Instant? =
    runCatching { Instant.parse(expiresAt) }.getOrNull()

private fun String.parseHttpDate(): Instant? =
    try {
        ZonedDateTime.parse(this, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
    } catch (_: DateTimeParseException) {
        null
    }

private fun Duration.isPositive(): Boolean = !isNegative && !isZero

private const val MAX_RESULTS = 10
