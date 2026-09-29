package com.nexusflow.backend.feature.research.infrastructure.source.outdoor

import com.nexusflow.backend.core.config.MetNoRuntimeConfig
import com.nexusflow.backend.core.config.NominatimRuntimeConfig
import com.nexusflow.backend.core.config.OpenMeteoRuntimeConfig
import com.nexusflow.backend.core.config.OpenRouteServiceRuntimeConfig
import com.nexusflow.backend.core.config.OverpassRuntimeConfig
import com.nexusflow.backend.core.config.TrailSplitsRuntimeConfig
import com.nexusflow.backend.core.external.ExternalSourceHttpClient
import com.nexusflow.backend.core.external.ExternalSourceInvalidPayloadException
import com.nexusflow.backend.core.external.ExternalSourcePolicyRateLimitedException
import com.nexusflow.backend.core.external.ExternalSourceDisabledException
import com.nexusflow.backend.core.external.ExternalSourceRateLimitedException
import com.nexusflow.backend.core.external.ExternalSourceUnauthorizedException
import com.nexusflow.backend.core.external.ExternalSourceUnavailableException
import com.nexusflow.backend.core.external.SourceCacheKey
import com.nexusflow.backend.core.external.SourceCacheStore
import com.nexusflow.backend.feature.task.domain.SourceAuthority
import com.nexusflow.backend.feature.task.domain.source.GeoPoint
import com.nexusflow.backend.feature.task.domain.source.PlaceLookupQuery
import com.nexusflow.backend.feature.task.domain.source.RouteQuery
import com.nexusflow.backend.feature.task.domain.source.TrailDiscoveryQuery
import com.nexusflow.backend.feature.task.domain.source.WeatherQuery
import com.nexusflow.backend.feature.research.application.source.OutdoorAcquirer
import com.nexusflow.observability.LogFields
import com.nexusflow.observability.LogLevel
import com.nexusflow.observability.StructuredLogger
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headers
import io.ktor.http.content.TextContent
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OutdoorSourceTest {
    private val now = Instant.parse("2026-09-06T00:00:00Z")
    private val trailQuery = TrailDiscoveryQuery(keyword = "Dragon Back", near = "Hong Kong", center = GeoPoint(22.236, 114.242))
    private val placeQuery = PlaceLookupQuery(text = "Dragon Back", near = "Hong Kong")
    private val routeQuery = RouteQuery(destination = GeoPoint(22.236, 114.242), origin = GeoPoint(22.300, 114.170))
    private val weatherQuery = WeatherQuery(point = GeoPoint(22.236, 114.242))

    @Test
    fun `200 text payload is invalid and exposes only safe response metadata`() = runBlocking {
        val logFields = mutableListOf<LogFields>()
        val logger = object : StructuredLogger {
            override fun log(level: LogLevel, component: String, event: String, fields: LogFields, cause: Throwable?) {
                if (event == "source_acquisition_failed") logFields += fields
            }
        }
        val failure = assertFailsWith<ExternalSourceInvalidPayloadException> {
            TrailSplitsTrailSource(
                http = ExternalSourceHttpClient(testHttpClient(MockEngine {
                    respond("SECRET_RESPONSE_BODY", headers = headers { append(HttpHeaders.ContentType, "text/plain") })
                })),
                config = TrailSplitsRuntimeConfig(baseUrl = "https://trailsplits.test"),
                logger = logger,
            ).search(trailQuery.copy(keyword = "SECRET_QUERY"))
        }
        assertEquals(200, failure.httpStatus)
        assertEquals("text/plain", failure.contentType)
        assertEquals("invalid_payload", failure.failureCategory)
        assertEquals("200", logFields.single().values["http_status"])
        assertEquals("text/plain", logFields.single().values["content_type"])
        assertFalse(logFields.single().values.toString().contains("SECRET"))
    }

    @Test
    fun `bad primary content type falls through to TrailSplits evidence`() = runBlocking {
        val acquirer = OutdoorAcquirer(
            trailPrimary = overpassSource(MockEngine {
                respond("provider error", headers = headers { append(HttpHeaders.ContentType, "text/plain") })
            }),
            trailSecondary = trailSplitsTrailSource(MockEngine { respondJson(trailSplitsTrailPayload("safe")) }),
            placePrimary = null,
            placeSecondary = null,
            routePrimary = null,
            routeSecondary = null,
            weatherPrimary = null,
            weatherSecondary = null,
            webDiscoverySource = null,
        )
        val opportunities = acquirer.acquire(trailQuery, now)
        assertEquals("Dragon Back Trail", opportunities.single().title)
        assertEquals("trailsplits", opportunities.single().sources.single().sourceId)
    }

    @Test
    fun `Nominatim local admission and contact policy prevent extra requests`() = runBlocking {
        var requestCount = 0
        val http = ExternalSourceHttpClient(
            testHttpClient(MockEngine { requestCount++; respondJson(nominatimPayload("safe")) }),
            "NexusFlow Test/1.0 (test@example.com)",
        )
        val source = NominatimPlaceSource(http, NominatimRuntimeConfig(baseUrl = "https://nominatim.test"))
        source.find(placeQuery)
        assertFailsWith<ExternalSourcePolicyRateLimitedException> { source.find(placeQuery.copy(text = "New place")) }
        assertEquals(1, requestCount)

        val disabled = NominatimPlaceSource(
            ExternalSourceHttpClient(testHttpClient(MockEngine { requestCount++; respondJson(nominatimPayload("safe")) })),
            NominatimRuntimeConfig(baseUrl = "https://nominatim.test"),
        )
        assertFailsWith<ExternalSourceDisabledException> { disabled.find(placeQuery) }
        assertEquals(1, requestCount)
    }

    @Test
    fun `MET coordinates are truncated to four fractional digits`() = runBlocking {
        val source = metNoSource(MockEngine { request ->
            assertEquals("22.2367", request.url.parameters["lat"])
            assertEquals("-114.2429", request.url.parameters["lon"])
            respondJson(metNoPayload("safe"))
        })
        source.forecast(WeatherQuery(GeoPoint(22.236789, -114.242987)))
        Unit
    }

    @Test
    fun `Overpass projects valid payload and separates empty status and invalid outcomes`() = runBlocking {
        val source = overpassSource(MockEngine { request ->
            assertEquals("/api/interpreter", request.url.encodedPath)
            val data = request.url.parameters["data"]
            assertTrue(data?.contains("Dragon Back") == true)
            assertTrue(data?.contains("around:25000,22.236,114.242") == true)
            respondJson(overpassPayload(rawMarker = "RAW_OVERPASS_SHOULD_NOT_CACHE"))
        })

        val candidates = source.search(trailQuery)

        assertEquals("way:1001", candidates.single().externalTrailId)
        assertEquals("Dragon Back Trail", candidates.single().name)
        assertEquals(8_500, candidates.single().distanceMeters)
        assertEquals(SourceAuthority.StructuredPrimary, candidates.single().sources.single().authority)

        assertEquals(emptyList(), overpassSource(MockEngine { respondJson("""{"elements":[]}""") }).search(trailQuery))
        assertIs<ExternalSourceUnauthorizedException>(overpassFailure(HttpStatusCode.Unauthorized))
        assertIs<ExternalSourceUnauthorizedException>(overpassFailure(HttpStatusCode.Forbidden))
        assertIs<ExternalSourceRateLimitedException>(overpassFailure(HttpStatusCode.TooManyRequests))
        assertIs<ExternalSourceUnavailableException>(overpassFailure(HttpStatusCode.BadGateway))
        assertFailsWith<ExternalSourceInvalidPayloadException> {
            overpassSource(MockEngine { respondJson("""{"elements":[{"type":"way","id":1001}]}""") }).search(trailQuery)
        }
        Unit
    }

    @Test
    fun `Overpass requires typed center and does not issue unbounded request without it`() = runBlocking {
        var callCount = 0
        val source = overpassSource(MockEngine {
            callCount += 1
            respondJson(overpassPayload(rawMarker = "RAW_OVERPASS_SHOULD_NOT_CACHE"))
        })

        val candidates = source.search(TrailDiscoveryQuery(keyword = "Dragon Back", near = "Hong Kong", center = null))

        assertEquals(emptyList(), candidates)
        assertEquals(0, callCount)
    }

    @Test
    fun `Overpass strips unsafe keyword characters before regex interpolation`() = runBlocking {
        val source = overpassSource(MockEngine { request ->
            val data = request.url.parameters["data"].orEmpty()
            assertTrue(data.contains("around:25000,22.236,114.242"))
            assertEquals(3, Regex("around:25000,22\\.236,114\\.242").findAll(data).count())
            assertTrue(data.contains("\"Dragon Back node[amenity=bar]\",i"))
            assertFalse(data.contains("node[\"amenity\""))
            assertFalse(data.contains("\\"))
            respondJson(overpassPayload(rawMarker = "RAW_OVERPASS_SHOULD_NOT_CACHE"))
        })

        source.search(trailQuery.copy(keyword = "Dragon \"Back\nnode[\"amenity\"=\"bar\"]\\"))
        Unit
    }

    @Test
    fun `TrailSplits trail source projects valid payload and separates empty status and invalid outcomes`() = runBlocking {
        val source = trailSplitsTrailSource(MockEngine { request ->
            assertEquals("/trails/v1/search", request.url.encodedPath)
            assertEquals("Dragon Back", request.url.parameters["q"])
            assertEquals("hiking", request.url.parameters["type"])
            assertNull(request.url.parameters["near"])
            respondJson(trailSplitsTrailPayload(rawMarker = "RAW_TRAILSPLITS_TRAIL_SHOULD_NOT_CACHE"))
        })

        val candidates = source.search(trailQuery)

        assertEquals("1001", candidates.single().externalTrailId)
        assertEquals("Dragon Back Trail", candidates.single().name)
        assertEquals(8_500, candidates.single().distanceMeters)
        assertEquals(GeoPoint(22.236, 114.242), candidates.single().startLocation)
        assertEquals(SourceAuthority.StructuredSecondary, candidates.single().sources.single().authority)

        assertEquals(emptyList(), trailSplitsTrailSource(MockEngine { respondJson("""{"features":[]}""") }).search(trailQuery))
        assertIs<ExternalSourceUnauthorizedException>(trailSplitsTrailFailure(HttpStatusCode.Unauthorized))
        assertIs<ExternalSourceUnauthorizedException>(trailSplitsTrailFailure(HttpStatusCode.Forbidden))
        assertIs<ExternalSourceRateLimitedException>(trailSplitsTrailFailure(HttpStatusCode.TooManyRequests))
        assertIs<ExternalSourceUnavailableException>(trailSplitsTrailFailure(HttpStatusCode.ServiceUnavailable))
        assertFailsWith<ExternalSourceInvalidPayloadException> {
            trailSplitsTrailSource(MockEngine { respondJson("""{"features":[{"properties":{"osm_relation_id":1001}}]}""") }).search(trailQuery)
        }
        assertEquals(
            emptyList(),
            trailSplitsTrailSource(MockEngine {
                respondJson(trailSplitsTrailPayload("safe").replace("[114.242, 22.236]", "[2.35, 48.86]"))
            }).search(trailQuery),
        )
        Unit
    }

    @Test
    fun `place lookup sources project valid payload and separate failures`() = runBlocking {
        val ors = orsPlaceSource(MockEngine { request ->
            assertEquals("/geocode/search", request.url.encodedPath)
            assertEquals("ors-secret", request.url.parameters["api_key"])
            respondJson(orsPlacePayload(rawMarker = "RAW_ORS_PLACE_SHOULD_NOT_CACHE"))
        })
        assertEquals("Dragon Back Trail, Hong Kong", ors.find(placeQuery).single().displayName)

        val nominatim = nominatimSource(MockEngine { request ->
            assertEquals("/search", request.url.encodedPath)
            assertEquals("jsonv2", request.url.parameters["format"])
            respondJson(nominatimPayload(rawMarker = "RAW_NOMINATIM_SHOULD_NOT_CACHE"))
        })
        assertEquals("Dragon Back Trail, Hong Kong", nominatim.find(placeQuery).single().displayName)

        assertEquals(emptyList(), orsPlaceSource(MockEngine { respondJson("""{"features":[]}""") }).find(placeQuery))
        assertEquals(emptyList(), nominatimSource(MockEngine { respondJson("""[]""") }).find(placeQuery))
        assertIs<ExternalSourceUnauthorizedException>(orsPlaceFailure(HttpStatusCode.Forbidden))
        assertIs<ExternalSourceRateLimitedException>(nominatimFailure(HttpStatusCode.TooManyRequests))
        assertIs<ExternalSourceUnavailableException>(orsPlaceFailure(HttpStatusCode.InternalServerError))
        assertFailsWith<ExternalSourceInvalidPayloadException> {
            orsPlaceSource(MockEngine { respondJson("""{"features":[{"properties":{"id":"p1"}}]}""") }).find(placeQuery)
        }
        assertFailsWith<ExternalSourceInvalidPayloadException> {
            nominatimSource(MockEngine { respondJson("""[{"osm_type":"way","osm_id":1001}]""") }).find(placeQuery)
        }
        Unit
    }

    @Test
    fun `routing sources project route facts with commute and separate failures`() = runBlocking {
        val ors = orsRouteSource(MockEngine { request ->
            assertEquals("/v2/directions/foot-hiking", request.url.encodedPath)
            assertEquals("ors-secret", request.headers[HttpHeaders.Authorization])
            respondJson(routePayload(rawMarker = "RAW_ORS_ROUTE_SHOULD_NOT_CACHE"))
        })
        val orsFact = ors.route(routeQuery)
        assertEquals(8_500, orsFact?.distanceMeters)
        assertEquals(150, orsFact?.durationMinutes)
        assertEquals(150, orsFact?.commuteMinutes)

        val trailSplits = trailSplitsRouteSource(MockEngine { request ->
            assertEquals("/route/v1", request.url.encodedPath)
            assertEquals("POST", request.method.value)
            val requestJson = (request.body as TextContent).text
            assertTrue(requestJson.contains("\"costing\":\"pedestrian\""))
            assertTrue(requestJson.contains("\"lat\":22.3,\"lon\":114.17"))
            respondJson(trailSplitsRoutePayload(rawMarker = "RAW_TRAILSPLITS_ROUTE_SHOULD_NOT_CACHE"))
        })
        val trailSplitsFact = trailSplits.route(routeQuery)
        assertEquals(146, trailSplitsFact?.durationMinutes)
        assertEquals(146, trailSplitsFact?.commuteMinutes)

        assertNull(orsRouteSource(MockEngine { respondJson("""{"routes":[]}""") }).route(routeQuery))
        assertNull(trailSplitsRouteSource(MockEngine { respondJson("""{"routes":[]}""") }).route(routeQuery))
        assertIs<ExternalSourceUnauthorizedException>(orsRouteFailure(HttpStatusCode.Unauthorized))
        assertIs<ExternalSourceRateLimitedException>(trailSplitsRouteFailure(HttpStatusCode.TooManyRequests))
        assertIs<ExternalSourceUnavailableException>(orsRouteFailure(HttpStatusCode.ServiceUnavailable))
        assertFailsWith<ExternalSourceInvalidPayloadException> {
            orsRouteSource(MockEngine { respondJson("""{"routes":[{"summary":{"distance":"bad"}}]}""") }).route(routeQuery)
        }
        assertFailsWith<ExternalSourceInvalidPayloadException> {
            trailSplitsRouteSource(MockEngine { respondJson("""{"routes":[{"distance":123.0}]}""") }).route(routeQuery)
        }
        Unit
    }

    @Test
    fun `routing sources do not call providers or synthesize facts without origin`() = runBlocking {
        var orsCallCount = 0
        val destinationOnly = RouteQuery(destination = GeoPoint(22.236, 114.242))
        val orsFact = orsRouteSource(MockEngine {
            orsCallCount += 1
            respondJson(routePayload(rawMarker = "RAW_ORS_ROUTE_SHOULD_NOT_CACHE"))
        }).route(destinationOnly)

        var trailSplitsCallCount = 0
        val trailSplitsFact = trailSplitsRouteSource(MockEngine {
            trailSplitsCallCount += 1
            respondJson(trailSplitsRoutePayload(rawMarker = "RAW_TRAILSPLITS_ROUTE_SHOULD_NOT_CACHE"))
        }).route(destinationOnly)

        assertNull(orsFact)
        assertNull(trailSplitsFact)
        assertEquals(0, orsCallCount)
        assertEquals(0, trailSplitsCallCount)
    }

    @Test
    fun `weather sources project typed facts and separate failures`() = runBlocking {
        val openMeteo = openMeteoSource(MockEngine { request ->
            assertEquals("/v1/forecast", request.url.encodedPath)
            assertEquals("22.236", request.url.parameters["latitude"])
            respondJson(openMeteoPayload(rawMarker = "RAW_OPEN_METEO_SHOULD_NOT_CACHE"))
        })
        val openMeteoFact = openMeteo.forecast(weatherQuery)
        assertEquals(27, openMeteoFact?.temperatureCelsius)
        assertEquals(SourceAuthority.StructuredSecondary, openMeteoFact?.source?.authority)

        val metNo = metNoSource(MockEngine { request ->
            assertEquals("/weatherapi/locationforecast/2.0/compact", request.url.encodedPath)
            assertEquals("NexusFlow Test/1.0 (test@example.com)", request.headers[HttpHeaders.UserAgent])
            respondJson(metNoPayload(rawMarker = "RAW_MET_NO_SHOULD_NOT_CACHE"))
        })
        val metNoFact = metNo.forecast(weatherQuery)
        assertEquals("partlycloudy_day", metNoFact?.summary)
        assertEquals(SourceAuthority.StructuredPrimary, metNoFact?.source?.authority)

        assertNull(openMeteoSource(MockEngine { respondJson("""{}""") }).forecast(weatherQuery))
        assertNull(metNoSource(MockEngine { respondJson("""{"properties":{"timeseries":[]}}""") }).forecast(weatherQuery))
        assertIs<ExternalSourceUnauthorizedException>(openMeteoFailure(HttpStatusCode.Forbidden))
        assertIs<ExternalSourceRateLimitedException>(metNoFailure(HttpStatusCode.TooManyRequests))
        assertIs<ExternalSourceUnavailableException>(openMeteoFailure(HttpStatusCode.BadGateway))
        assertFailsWith<ExternalSourceInvalidPayloadException> {
            openMeteoSource(MockEngine { respondJson("""{"current":{"temperature_2m":"bad"}}""") }).forecast(weatherQuery)
        }
        Unit
    }

    @Test
    fun `MetNo fresh provider cache returns projected fact without a second request`() = runBlocking {
        val store = RecordingSourceCacheStore()
        val mutableClock = MutableClock(now)
        var callCount = 0
        val source = metNoSource(
            cacheStore = store,
            clock = mutableClock,
            engine = MockEngine {
                callCount += 1
                respondJson(
                    metNoPayload(rawMarker = "RAW_MET_NO_SHOULD_NOT_CACHE"),
                    headers = jsonHeaders(
                        HttpHeaders.Expires to "Sun, 06 Sep 2026 00:10:00 GMT",
                        HttpHeaders.LastModified to "Sun, 06 Sep 2026 00:00:00 GMT",
                    ),
                )
            },
        )

        val first = source.forecast(weatherQuery)
        mutableClock.now = now.plus(Duration.ofMinutes(5))
        val second = source.forecast(weatherQuery)

        assertEquals("partlycloudy_day", first?.summary)
        assertEquals(first, second)
        assertEquals(1, callCount)
        assertCacheClean(store, forbidden = listOf("RAW_MET_NO_SHOULD_NOT_CACHE", "raw_marker"))
    }

    @Test
    fun `MetNo stale cache revalidates with last modified and handles not modified without decoding body`() = runBlocking {
        val store = RecordingSourceCacheStore()
        val mutableClock = MutableClock(now)
        var callCount = 0
        val source = metNoSource(
            cacheStore = store,
            clock = mutableClock,
            engine = MockEngine { request ->
                callCount += 1
                when (callCount) {
                    1 -> respondJson(
                        metNoPayload(rawMarker = "RAW_MET_NO_SHOULD_NOT_CACHE"),
                        headers = jsonHeaders(
                            HttpHeaders.Expires to "Sun, 06 Sep 2026 00:01:00 GMT",
                            HttpHeaders.LastModified to "Sun, 06 Sep 2026 00:00:00 GMT",
                        ),
                    )
                    2 -> {
                        assertEquals("Sun, 06 Sep 2026 00:00:00 GMT", request.headers[HttpHeaders.IfModifiedSince])
                        respondJson(
                            "",
                            status = HttpStatusCode.NotModified,
                            headers = jsonHeaders(HttpHeaders.Expires to "Sun, 06 Sep 2026 00:20:00 GMT"),
                        )
                    }
                    else -> error("fresh cache should avoid additional MetNo HTTP calls")
                }
            },
        )

        val first = source.forecast(weatherQuery)
        mutableClock.now = now.plus(Duration.ofMinutes(2))
        val revalidated = source.forecast(weatherQuery)
        mutableClock.now = now.plus(Duration.ofMinutes(10))
        val freshAfterRevalidation = source.forecast(weatherQuery)

        assertEquals("partlycloudy_day", first?.summary)
        assertEquals(first, revalidated)
        assertEquals(first, freshAfterRevalidation)
        assertEquals(2, callCount)
        assertCacheClean(store, forbidden = listOf("RAW_MET_NO_SHOULD_NOT_CACHE", "raw_marker"))
    }

    @Test
    fun `outdoor source caches typed projections without raw payload or api keys`() = runBlocking {
        val trailStore = RecordingSourceCacheStore()
        overpassSource(
            cacheStore = trailStore,
            engine = MockEngine { respondJson(overpassPayload(rawMarker = "RAW_OVERPASS_SHOULD_NOT_CACHE")) },
        ).search(trailQuery)
        assertCacheClean(trailStore, forbidden = listOf("RAW_OVERPASS_SHOULD_NOT_CACHE", "raw_marker"))

        val orsStore = RecordingSourceCacheStore()
        orsPlaceSource(
            cacheStore = orsStore,
            engine = MockEngine { respondJson(orsPlacePayload(rawMarker = "RAW_ORS_PLACE_SHOULD_NOT_CACHE")) },
        ).find(placeQuery)
        assertCacheClean(orsStore, forbidden = listOf("ors-secret", "RAW_ORS_PLACE_SHOULD_NOT_CACHE", "raw_marker"))

        val routeStore = RecordingSourceCacheStore()
        orsRouteSource(
            cacheStore = routeStore,
            engine = MockEngine { respondJson(routePayload(rawMarker = "RAW_ORS_ROUTE_SHOULD_NOT_CACHE")) },
        ).route(routeQuery)
        assertCacheClean(routeStore, forbidden = listOf("ors-secret", "RAW_ORS_ROUTE_SHOULD_NOT_CACHE", "raw_marker"))

        val weatherStore = RecordingSourceCacheStore()
        openMeteoSource(
            cacheStore = weatherStore,
            engine = MockEngine { respondJson(openMeteoPayload(rawMarker = "RAW_OPEN_METEO_SHOULD_NOT_CACHE")) },
        ).forecast(weatherQuery)
        assertCacheClean(weatherStore, forbidden = listOf("RAW_OPEN_METEO_SHOULD_NOT_CACHE", "raw_marker"))

        val metNoStore = RecordingSourceCacheStore()
        metNoSource(
            cacheStore = metNoStore,
            engine = MockEngine { respondJson(metNoPayload(rawMarker = "RAW_MET_NO_SHOULD_NOT_CACHE")) },
        ).forecast(weatherQuery)
        assertCacheClean(metNoStore, forbidden = listOf("RAW_MET_NO_SHOULD_NOT_CACHE", "raw_marker"))
    }

    private suspend fun overpassFailure(status: HttpStatusCode): Throwable =
        assertFailsWith<Throwable> { overpassSource(MockEngine { respondJson("""{}""", status) }).search(trailQuery) }

    private suspend fun trailSplitsTrailFailure(status: HttpStatusCode): Throwable =
        assertFailsWith<Throwable> { trailSplitsTrailSource(MockEngine { respondJson("""{}""", status) }).search(trailQuery) }

    private suspend fun orsPlaceFailure(status: HttpStatusCode): Throwable =
        assertFailsWith<Throwable> { orsPlaceSource(MockEngine { respondJson("""{}""", status) }).find(placeQuery) }

    private suspend fun nominatimFailure(status: HttpStatusCode): Throwable =
        assertFailsWith<Throwable> { nominatimSource(MockEngine { respondJson("""[]""", status) }).find(placeQuery) }

    private suspend fun orsRouteFailure(status: HttpStatusCode): Throwable =
        assertFailsWith<Throwable> { orsRouteSource(MockEngine { respondJson("""{}""", status) }).route(routeQuery) }

    private suspend fun trailSplitsRouteFailure(status: HttpStatusCode): Throwable =
        assertFailsWith<Throwable> { trailSplitsRouteSource(MockEngine { respondJson("""{}""", status) }).route(routeQuery) }

    private suspend fun openMeteoFailure(status: HttpStatusCode): Throwable =
        assertFailsWith<Throwable> { openMeteoSource(MockEngine { respondJson("""{}""", status) }).forecast(weatherQuery) }

    private suspend fun metNoFailure(status: HttpStatusCode): Throwable =
        assertFailsWith<Throwable> { metNoSource(MockEngine { respondJson("""{}""", status) }).forecast(weatherQuery) }

    private fun assertCacheClean(
        store: RecordingSourceCacheStore,
        forbidden: List<String>,
    ) {
        val keys = store.keys.joinToString("\n") { it.value }
        val values = store.putValues.joinToString("\n") { it.decodeToString() }
        forbidden.forEach { text ->
            assertFalse(keys.contains(text), "cache key must not contain $text")
            assertFalse(values.contains(text), "cache value must not contain $text")
        }
    }

    private fun overpassPayload(rawMarker: String): String =
        """
        {
          "elements": [
            {
              "type": "way",
              "id": 1001,
              "center": {"lat": 22.236, "lon": 114.242},
              "tags": {
                "name": "Dragon Back Trail",
                "route": "hiking",
                "distance": "8500",
                "ele": "300",
                "raw_marker": "$rawMarker"
              }
            }
          ]
        }
        """.trimIndent()

    private fun trailSplitsTrailPayload(rawMarker: String): String =
        """
        {
          "type": "FeatureCollection",
          "features": [
            {
              "type": "Feature",
              "properties": {"osm_relation_id": 1001, "name": "Dragon Back Trail", "route_type": "hiking", "distance_km": 8.5},
              "geometry": {"type": "Point", "coordinates": [114.242, 22.236]},
              "raw_marker": "$rawMarker"
            }
          ]
        }
        """.trimIndent()

    private fun orsPlacePayload(rawMarker: String): String =
        """
        {
          "features": [
            {
              "properties": {"id": "ors-place-1001", "label": "Dragon Back Trail, Hong Kong"},
              "geometry": {"coordinates": [114.242, 22.236]},
              "raw_marker": "$rawMarker"
            }
          ]
        }
        """.trimIndent()

    private fun nominatimPayload(rawMarker: String): String =
        """
        [
          {
            "osm_type": "way",
            "osm_id": 1001,
            "display_name": "Dragon Back Trail, Hong Kong",
            "lat": "22.236",
            "lon": "114.242",
            "raw_marker": "$rawMarker"
          }
        ]
        """.trimIndent()

    private fun routePayload(rawMarker: String): String =
        """
        {"routes":[{"summary":{"distance":8500.0,"duration":9000.0},"raw_marker":"$rawMarker"}]}
        """.trimIndent()

    private fun trailSplitsRoutePayload(rawMarker: String): String =
        """
        {"routes":[{"distance":8400.0,"duration":8760.0,"raw_marker":"$rawMarker"}]}
        """.trimIndent()

    private fun openMeteoPayload(rawMarker: String): String =
        """
        {
          "current": {
            "temperature_2m": 27.0,
            "precipitation_probability": 20.0,
            "wind_speed_10m": 15.0,
            "weather_code": 2
          },
          "raw_marker": "$rawMarker"
        }
        """.trimIndent()

    private fun metNoPayload(rawMarker: String): String =
        """
        {
          "properties": {
            "timeseries": [
              {
                "data": {
                  "instant": {"details": {"air_temperature": 18.0, "wind_speed": 7.0}},
                  "next_1_hours": {"summary": {"symbol_code": "partlycloudy_day"}}
                },
                "raw_marker": "$rawMarker"
              }
            ]
          }
        }
        """.trimIndent()

    private fun overpassSource(
        engine: MockEngine,
        cacheStore: SourceCacheStore? = null,
    ): OverpassTrailSource =
        OverpassTrailSource(
            http = ExternalSourceHttpClient(testHttpClient(engine)),
            config = OverpassRuntimeConfig(baseUrl = "https://overpass.test/api"),
            cacheStore = cacheStore,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun trailSplitsTrailSource(
        engine: MockEngine,
        cacheStore: SourceCacheStore? = null,
    ): TrailSplitsTrailSource =
        TrailSplitsTrailSource(
            http = ExternalSourceHttpClient(testHttpClient(engine)),
            config = TrailSplitsRuntimeConfig(baseUrl = "https://trailsplits.test"),
            cacheStore = cacheStore,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun orsPlaceSource(
        engine: MockEngine,
        cacheStore: SourceCacheStore? = null,
    ): OpenRouteServicePlaceSource =
        OpenRouteServicePlaceSource(
            http = ExternalSourceHttpClient(testHttpClient(engine)),
            config = OpenRouteServiceRuntimeConfig(apiKey = "ors-secret", geocodeBaseUrl = "https://ors-geocode.test"),
            cacheStore = cacheStore,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun nominatimSource(
        engine: MockEngine,
        cacheStore: SourceCacheStore? = null,
    ): NominatimPlaceSource =
        NominatimPlaceSource(
            http = ExternalSourceHttpClient(testHttpClient(engine), "NexusFlow Test/1.0 (test@example.com)"),
            config = NominatimRuntimeConfig(baseUrl = "https://nominatim.test"),
            cacheStore = cacheStore,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun orsRouteSource(
        engine: MockEngine,
        cacheStore: SourceCacheStore? = null,
    ): OpenRouteServiceRouteSource =
        OpenRouteServiceRouteSource(
            http = ExternalSourceHttpClient(testHttpClient(engine)),
            config = OpenRouteServiceRuntimeConfig(apiKey = "ors-secret", routingBaseUrl = "https://ors-route.test"),
            cacheStore = cacheStore,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun trailSplitsRouteSource(
        engine: MockEngine,
        cacheStore: SourceCacheStore? = null,
    ): TrailSplitsRouteSource =
        TrailSplitsRouteSource(
            http = ExternalSourceHttpClient(testHttpClient(engine)),
            config = TrailSplitsRuntimeConfig(baseUrl = "https://trailsplits.test"),
            cacheStore = cacheStore,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun openMeteoSource(
        engine: MockEngine,
        cacheStore: SourceCacheStore? = null,
    ): OpenMeteoWeatherSource =
        OpenMeteoWeatherSource(
            http = ExternalSourceHttpClient(testHttpClient(engine)),
            config = OpenMeteoRuntimeConfig(baseUrl = "https://open-meteo.test"),
            cacheStore = cacheStore,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    private fun metNoSource(
        engine: MockEngine,
        cacheStore: SourceCacheStore? = null,
        clock: Clock = Clock.fixed(now, ZoneOffset.UTC),
    ): MetNoWeatherSource =
        MetNoWeatherSource(
            http = ExternalSourceHttpClient(testHttpClient(engine)),
            config = MetNoRuntimeConfig(baseUrl = "https://met-no.test"),
            userAgent = "NexusFlow Test/1.0 (test@example.com)",
            cacheStore = cacheStore,
            clock = clock,
        )

    private fun testHttpClient(engine: MockEngine): HttpClient =
        HttpClient(engine) {
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        explicitNulls = false
                    },
                )
            }
        }

    private fun MockRequestHandleScope.respondJson(
        content: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        headers: Headers = jsonHeaders(),
    ) = respond(
        content = content,
        status = status,
        headers = headers,
    )

    private fun jsonHeaders(vararg extra: Pair<String, String>): Headers =
        headers {
            append(HttpHeaders.ContentType, "application/json")
            extra.forEach { (name, value) -> append(name, value) }
        }

    private class RecordingSourceCacheStore : SourceCacheStore {
        private val values = linkedMapOf<SourceCacheKey, ByteArray>()
        val keys = mutableListOf<SourceCacheKey>()
        val putValues = mutableListOf<ByteArray>()

        override suspend fun get(key: SourceCacheKey): ByteArray? = values[key]?.copyOf()

        override suspend fun put(
            key: SourceCacheKey,
            value: ByteArray,
            ttl: Duration,
        ) {
            values[key] = value.copyOf()
            keys += key
            putValues += value.copyOf()
        }

        override suspend fun remove(key: SourceCacheKey) {
            values.remove(key)
        }
    }

    private class MutableClock(
        var now: Instant,
    ) : Clock() {
        override fun instant(): Instant = now

        override fun withZone(zone: ZoneId): Clock = this

        override fun getZone(): ZoneId = ZoneOffset.UTC
    }
}
