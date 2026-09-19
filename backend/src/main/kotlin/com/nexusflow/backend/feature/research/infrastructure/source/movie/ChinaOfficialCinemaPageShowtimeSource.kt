package com.nexusflow.backend.feature.research.infrastructure.source.movie

import com.nexusflow.backend.core.config.ChinaOfficialCinemaRuntimeConfig
import com.nexusflow.backend.core.external.ExternalSourceHttpClient
import com.nexusflow.backend.core.external.SourceCacheCodec
import com.nexusflow.backend.core.external.SourceCacheKey
import com.nexusflow.backend.core.external.SourceCacheResultKind
import com.nexusflow.backend.core.external.SourceCacheStore
import com.nexusflow.backend.core.external.SourceCacheTtlPolicy
import com.nexusflow.backend.core.external.executeExternalSourceRequest
import com.nexusflow.backend.core.external.rejectKnownExternalSourceStatus
import com.nexusflow.backend.feature.task.domain.AvailabilityFact
import com.nexusflow.backend.feature.task.domain.MoneyFact
import com.nexusflow.backend.feature.task.domain.OpportunityFactKey
import com.nexusflow.backend.feature.task.domain.SourceAuthority
import com.nexusflow.backend.feature.task.domain.SourceRef
import com.nexusflow.backend.feature.task.domain.normalizedPlanningToken
import com.nexusflow.backend.feature.task.domain.source.MovieShowtimeCandidate
import com.nexusflow.backend.feature.task.domain.source.MovieShowtimeQuery
import com.nexusflow.backend.feature.task.domain.source.MovieShowtimeSource
import com.nexusflow.backend.feature.research.infrastructure.source.CachedSourceRefDocument
import com.nexusflow.observability.StructuredLogger
import com.nexusflow.observability.logFields
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import org.jsoup.Jsoup
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ChinaOfficialCinemaPageShowtimeSource(
    private val http: ExternalSourceHttpClient,
    private val config: ChinaOfficialCinemaRuntimeConfig,
    private val cacheStore: SourceCacheStore? = null,
    private val logger: StructuredLogger? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val cachePolicy: SourceCacheTtlPolicy = SourceCacheTtlPolicy(
        successTtl = Duration.ofHours(2),
        emptyTtl = Duration.ofMinutes(20),
    ),
) : MovieShowtimeSource {
    override suspend fun search(query: MovieShowtimeQuery): List<MovieShowtimeCandidate> {
        val key = SourceCacheKey("source:v1:china-official-cinema:movie-showtime:${sha256(query.canonicalCacheRequest())}")
        cacheStore?.get(key)?.let { bytes ->
            val cached = MovieShowtimeCandidateListCacheCodec.decode(bytes)
            logAcquisition("search", if (cached.isEmpty()) "empty" else "success", cached.size, cacheHit = true)
            return cached
        }

        val candidates = executeExternalSourceRequest(PROVIDER, "search") {
            config.pageUrls
                .flatMap { pageUrl -> fetchPageCandidates(pageUrl, query) }
                .distinctBy { it.externalShowtimeId }
                .take(MAX_RESULTS)
        }

        cacheStore?.put(
            key = key,
            value = MovieShowtimeCandidateListCacheCodec.encode(candidates),
            ttl = cachePolicy.ttlFor(if (candidates.isEmpty()) SourceCacheResultKind.SuccessEmpty else SourceCacheResultKind.Success),
        )
        logAcquisition("search", if (candidates.isEmpty()) "empty" else "success", candidates.size, cacheHit = false)
        return candidates
    }

    private suspend fun fetchPageCandidates(
        pageUrl: String,
        query: MovieShowtimeQuery,
    ): List<MovieShowtimeCandidate> {
        val response = http.client.get(pageUrl) {
            parameter("country", query.countryCode)
            query.city?.let { parameter("city", it) }
            query.title?.takeIf(String::isNotBlank)?.let { parameter("movie", it) }
            query.dateFrom?.let { parameter("dateFrom", it.toString()) }
            query.dateTo?.let { parameter("dateTo", it.toString()) }
        }
        response.rejectKnownExternalSourceStatus(PROVIDER, "search")
        val html = response.bodyAsText()
        return html.toShowtimeCandidates(
            pageUrl = pageUrl,
            query = query,
            observedAt = clock.instant(),
        )
    }

    private fun String.toShowtimeCandidates(
        pageUrl: String,
        query: MovieShowtimeQuery,
        observedAt: Instant,
    ): List<MovieShowtimeCandidate> {
        val scripts = Jsoup.parse(this).select("script[type=application/ld+json]")
        if (scripts.isEmpty()) return emptyList()
        return scripts.flatMap { element ->
            val jsonLd = parseJsonLd(element.data().ifBlank { element.html() })
            jsonLd.schemaObjects()
                .filter { it.schemaTypes().any(::isEventType) }
                .mapNotNull { it.toMovieShowtimeCandidate(pageUrl, query, observedAt) }
        }
    }

    private fun parseJsonLd(content: String): JsonElement =
        try {
            json.parseToJsonElement(content)
        } catch (cause: SerializationException) {
            throw IllegalArgumentException("Invalid schema.org JSON-LD on official cinema page", cause)
        }

    private fun JsonElement.schemaObjects(): List<JsonObject> =
        when (this) {
            is JsonObject -> {
                val current: JsonObject = this
                val graphObjects: List<JsonObject> = (this["@graph"] as? JsonArray)
                    ?.flatMap { element: JsonElement -> element.schemaObjects() }
                    ?: emptyList()
                listOf(current) + graphObjects
            }
            is JsonArray -> flatMap { it.schemaObjects() }
            else -> emptyList()
        }

    private fun JsonObject.toMovieShowtimeCandidate(
        pageUrl: String,
        query: MovieShowtimeQuery,
        observedAt: Instant,
    ): MovieShowtimeCandidate? {
        val startsAt = stringValue("startDate")?.parseScreeningTime() ?: return null
        if (!startsAt.isWithinQueryWindow(query)) return null
        val movieTitle = movieTitle() ?: return null
        val requestedTitles = query.requestedMovieTitles()
        if (requestedTitles.isNotEmpty() && !movieTitle.matchesAny(requestedTitles)) return null
        val location = objectValue("location")
        val cinemaName = location?.stringValue("name")?.takeIf(String::isNotBlank) ?: return null
        val address = location?.objectValue("address")
        val city = address?.stringValue("addressLocality")
            ?: location?.stringValue("addressLocality")
        if (!location.matchesCity(query.city, city, cinemaName)) return null
        val publicUrl = stringValue("url")
            ?: objectValue("offers")?.stringValue("url")
            ?: pageUrl
        val price = objectValue("offers")?.price()
        val availability = objectValue("offers")?.availability()
        val endsAt = stringValue("endDate")?.parseScreeningTime()
        val source = SourceRef(
            label = "China official cinema page",
            uri = publicUrl,
            sourceUpdatedAt = observedAt,
            sourceId = "china-official-cinema-page",
            authority = SourceAuthority.OfficialWeb,
            factKeys = buildSet {
                add(OpportunityFactKey.Title)
                add(OpportunityFactKey.StartTime)
                add(OpportunityFactKey.Location)
                add(OpportunityFactKey.MovieShowtime)
                if (endsAt != null) add(OpportunityFactKey.EndTime)
                if (price != null) add(OpportunityFactKey.Price)
                if (availability != null) add(OpportunityFactKey.Availability)
            },
        )
        return MovieShowtimeCandidate(
            externalShowtimeId = showtimeId(publicUrl, movieTitle, cinemaName, startsAt),
            movieTitle = movieTitle,
            cinemaName = cinemaName,
            startsAt = startsAt,
            endsAt = endsAt,
            city = city,
            price = price,
            availability = availability,
            publicUrl = publicUrl,
            source = source,
        )
    }

    private fun JsonObject.movieTitle(): String? =
        objectValue("workPerformed")?.stringValue("name")
            ?: objectValue("workPresented")?.stringValue("name")
            ?: objectValue("about")?.stringValue("name")
            ?: objectValue("recordedIn")?.stringValue("name")
            ?: stringValue("name")

    private fun JsonObject.price(): MoneyFact? {
        val priceText = stringValue("price")
            ?: primitiveValue("price")?.contentOrNull
            ?: return null
        val amount = priceText.toMoneyAmount() ?: return null
        return MoneyFact(
            wholeUnits = amount.toLong(),
            currencyCode = stringValue("priceCurrency") ?: "CNY",
        )
    }

    private fun JsonObject.availability(): AvailabilityFact? =
        stringValue("availability")
            ?.lowercase()
            ?.let { value ->
                when {
                    "soldout" in value || "outofstock" in value -> AvailabilityFact.Unavailable
                    "limited" in value -> AvailabilityFact.Limited
                    "instock" in value || "presale" in value || "available" in value -> AvailabilityFact.Available
                    else -> null
                }
            }

    private fun String.parseScreeningTime(): Instant? =
        runCatching { Instant.parse(this) }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(this).toInstant() }.getOrNull()
            ?: runCatching { ZonedDateTime.parse(this).toInstant() }.getOrNull()
            ?: runCatching { LocalDateTime.parse(this).atZone(CHINA_ZONE).toInstant() }.getOrNull()

    private fun String.matchesAny(values: Set<String>): Boolean {
        val normalized = normalizedPlanningToken()
        return values.any { value ->
            val candidate = value.normalizedPlanningToken()
            candidate.isNotBlank() &&
                (normalized == candidate || normalized.contains(candidate) || candidate.contains(normalized))
        }
    }

    private fun JsonObject.schemaTypes(): Set<String> =
        when (val type = get("@type")) {
            is JsonPrimitive -> setOfNotNull(type.contentOrNull)
            is JsonArray -> type.jsonArray.mapNotNullTo(mutableSetOf()) { (it as? JsonPrimitive)?.contentOrNull }
            else -> emptySet()
        }

    private fun isEventType(type: String): Boolean =
        type == "Event" || type == "ScreeningEvent" || type.endsWith("Event")

    private fun JsonObject.objectValue(name: String): JsonObject? = get(name)?.jsonObjectOrNull()

    private fun JsonObject.stringValue(name: String): String? =
        primitiveValue(name)?.contentOrNull?.takeIf(String::isNotBlank)

    private fun JsonObject.primitiveValue(name: String): JsonPrimitive? = get(name) as? JsonPrimitive

    private fun JsonElement.jsonObjectOrNull(): JsonObject? = this as? JsonObject

    private fun String.toMoneyAmount(): BigDecimal? {
        val numeric = replace(",", "").let { Regex("""\d+(\.\d+)?""").find(it)?.value }
        return numeric?.toBigDecimalOrNull()
    }

    private fun showtimeId(
        url: String,
        movieTitle: String,
        cinemaName: String,
        startsAt: Instant,
    ): String = sha256("$url|$movieTitle|$cinemaName|$startsAt").take(24)

    private fun MovieShowtimeQuery.canonicalCacheRequest(): String =
        "title=${title?.normalizedPlanningToken()?.takeIf(String::isNotBlank) ?: ABSENT_CACHE_VALUE}|" +
            "city=${city.orEmpty().normalizedPlanningToken()}|" +
            "country=$countryCode|from=${dateFrom.toCacheText()}|to=${dateTo.toCacheText()}|" +
            "known=${knownMovieTitles.mapNotNull { it.normalizedPlanningToken().takeIf(String::isNotBlank) }.sorted().joinToString(",")}|" +
            "pages=${config.pageUrls.sorted().joinToString(",")}"

    private fun MovieShowtimeQuery.requestedMovieTitles(): Set<String> =
        (knownMovieTitles + listOfNotNull(title?.takeIf(String::isNotBlank)))
            .mapNotNullTo(mutableSetOf()) { it.takeIf(String::isNotBlank) }

    private fun Any?.toCacheText(): String = this?.toString() ?: ""

    private fun logAcquisition(
        operation: String,
        outcome: String,
        resultCount: Int,
        cacheHit: Boolean,
    ) {
        logger?.info(
            component = "external_source",
            event = "source_acquisition_finished",
            fields = logFields {
                "source_id" value PROVIDER
                "capability" value "movie_showtime"
                "operation" value operation
                "outcome" value outcome
                "result_count" value resultCount
                "cache_mode" value (if (cacheStore == null) "none" else "memory")
                "cache_hit" value cacheHit
                "external_call_avoided" value cacheHit
            },
        )
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private object MovieShowtimeCandidateListCacheCodec : SourceCacheCodec<List<MovieShowtimeCandidate>> {
        override fun encode(value: List<MovieShowtimeCandidate>): ByteArray =
            json.encodeToString(
                ListSerializer(MovieShowtimeCandidateCacheDocument.serializer()),
                value.map(MovieShowtimeCandidateCacheDocument::from),
            ).toByteArray()

        override fun decode(bytes: ByteArray): List<MovieShowtimeCandidate> =
            json.decodeFromString(
                ListSerializer(MovieShowtimeCandidateCacheDocument.serializer()),
                bytes.decodeToString(),
            ).map(MovieShowtimeCandidateCacheDocument::toDomain)
    }

    @Serializable
    private data class MovieShowtimeCandidateCacheDocument(
        val externalShowtimeId: String,
        val movieTitle: String,
        val cinemaName: String,
        val startsAt: String,
        val endsAt: String?,
        val city: String?,
        val priceWholeUnits: Long?,
        val priceCurrencyCode: String?,
        val availability: String?,
        val publicUrl: String,
        val source: CachedSourceRefDocument,
    ) {
        fun toDomain(): MovieShowtimeCandidate =
            MovieShowtimeCandidate(
                externalShowtimeId = externalShowtimeId,
                movieTitle = movieTitle,
                cinemaName = cinemaName,
                startsAt = Instant.parse(startsAt),
                endsAt = endsAt?.let(Instant::parse),
                city = city,
                price = priceWholeUnits?.let { MoneyFact(it, priceCurrencyCode) },
                availability = availability?.let(AvailabilityFact::valueOf),
                publicUrl = publicUrl,
                source = source.toDomain(),
            )

        companion object {
            fun from(candidate: MovieShowtimeCandidate): MovieShowtimeCandidateCacheDocument =
                MovieShowtimeCandidateCacheDocument(
                    externalShowtimeId = candidate.externalShowtimeId,
                    movieTitle = candidate.movieTitle,
                    cinemaName = candidate.cinemaName,
                    startsAt = candidate.startsAt.toString(),
                    endsAt = candidate.endsAt?.toString(),
                    city = candidate.city,
                    priceWholeUnits = candidate.price?.wholeUnits,
                    priceCurrencyCode = candidate.price?.currencyCode,
                    availability = candidate.availability?.name,
                    publicUrl = candidate.publicUrl,
                    source = CachedSourceRefDocument.from(candidate.source),
                )
        }
    }

    private fun Instant.isWithinQueryWindow(query: MovieShowtimeQuery): Boolean {
        val localDate = atZone(CHINA_ZONE).toLocalDate()
        return (query.dateFrom == null || !localDate.isBefore(query.dateFrom)) &&
            (query.dateTo == null || !localDate.isAfter(query.dateTo))
    }

    private fun JsonObject?.matchesCity(
        queryCity: String?,
        candidateCity: String?,
        cinemaName: String,
    ): Boolean {
        val normalizedQuery = queryCity?.normalizedPlanningToken()?.takeIf(String::isNotBlank) ?: return true
        val address = this?.objectValue("address")
        val providerLocationText = listOfNotNull(
            candidateCity,
            cinemaName,
            this?.stringValue("name"),
            this?.stringValue("address"),
            this?.stringValue("addressLocality"),
            address?.stringValue("addressLocality"),
            address?.stringValue("streetAddress"),
            address?.stringValue("addressRegion"),
            address?.stringValue("addressCountry"),
        ).joinToString(" ").normalizedPlanningToken()
        return providerLocationText.contains(normalizedQuery)
    }

    private companion object {
        const val PROVIDER = "china-official-cinema-page"
        const val MAX_RESULTS = 10
        const val ABSENT_CACHE_VALUE = "<absent>"
        val CHINA_ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
        val json: Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}
