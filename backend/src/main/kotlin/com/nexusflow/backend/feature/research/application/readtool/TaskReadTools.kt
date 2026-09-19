package com.nexusflow.backend.feature.research.application.readtool

import com.nexusflow.backend.core.external.ExternalSourceException
import com.nexusflow.backend.feature.research.application.ReadTool
import com.nexusflow.backend.feature.research.application.ReadToolActivityKind
import com.nexusflow.backend.feature.research.application.ReadToolDefinition
import com.nexusflow.backend.feature.research.application.ReadToolEvidence
import com.nexusflow.backend.feature.research.application.ReadToolEvidencePayload
import com.nexusflow.backend.feature.research.application.ReadToolFact
import com.nexusflow.backend.feature.research.application.ReadToolFactKind
import com.nexusflow.backend.feature.research.application.ReadToolFactValue
import com.nexusflow.backend.feature.research.application.ReadToolExecutionContext
import com.nexusflow.backend.feature.research.application.ReadToolKey
import com.nexusflow.backend.feature.research.application.ReadToolOutcome
import com.nexusflow.backend.feature.research.application.ReadToolSourceAuthority
import com.nexusflow.backend.feature.task.domain.ActivityModeValue
import com.nexusflow.backend.feature.task.domain.AvailabilityFact
import com.nexusflow.backend.feature.task.domain.FactValue
import com.nexusflow.backend.feature.task.domain.MoneyFact
import com.nexusflow.backend.feature.task.application.TaskDependencyUnavailableException
import com.nexusflow.backend.feature.research.application.source.FootballFixtureAcquirer
import com.nexusflow.backend.feature.research.application.source.GeneralSportsEventAcquirer
import com.nexusflow.backend.feature.research.application.source.LiveMusicEventAcquirer
import com.nexusflow.backend.feature.research.application.source.MovieDiscoveryAcquirer
import com.nexusflow.backend.feature.research.application.source.MovieMetadataAcquirer
import com.nexusflow.backend.feature.research.application.source.MovieShowtimeAcquirer
import com.nexusflow.backend.feature.research.application.source.OutdoorAcquirer
import com.nexusflow.backend.feature.task.domain.Opportunity
import com.nexusflow.backend.feature.task.domain.SourceAuthority as DomainSourceAuthority
import com.nexusflow.backend.feature.task.domain.SourceRef
import com.nexusflow.backend.feature.task.domain.source.FootballCompetition
import com.nexusflow.backend.feature.task.domain.source.FootballCompetitionRegistry
import com.nexusflow.backend.feature.task.domain.source.FootballFixtureCandidate
import com.nexusflow.backend.feature.task.domain.source.FootballFixtureQuery
import com.nexusflow.backend.feature.task.domain.source.GeneralSportsEventQuery
import com.nexusflow.backend.feature.task.domain.source.GeoPoint
import com.nexusflow.backend.feature.task.domain.source.LiveMusicEventQuery
import com.nexusflow.backend.feature.task.domain.source.MovieDiscoveryCandidate
import com.nexusflow.backend.feature.task.domain.source.MovieDiscoveryMode
import com.nexusflow.backend.feature.task.domain.source.MovieDiscoveryQuery
import com.nexusflow.backend.feature.task.domain.source.MovieMetadataCandidate
import com.nexusflow.backend.feature.task.domain.source.MovieMetadataQuery
import com.nexusflow.backend.feature.task.domain.source.MovieShowtimeQuery
import com.nexusflow.backend.feature.task.domain.source.MusicMetadataCandidate
import com.nexusflow.backend.feature.task.domain.source.MusicMetadataQuery
import com.nexusflow.backend.feature.task.domain.source.MusicMetadataSearchType
import com.nexusflow.backend.feature.task.domain.source.MusicMetadataSource
import com.nexusflow.backend.feature.task.domain.source.PlaceCandidate
import com.nexusflow.backend.feature.task.domain.source.PlaceLookupQuery
import com.nexusflow.backend.feature.task.domain.source.PlaceLookupSource
import com.nexusflow.backend.feature.task.domain.source.RouteFact
import com.nexusflow.backend.feature.task.domain.source.RouteProfile
import com.nexusflow.backend.feature.task.domain.source.RouteQuery
import com.nexusflow.backend.feature.task.domain.source.RouteSource
import com.nexusflow.backend.feature.task.domain.source.TrailDiscoveryQuery
import com.nexusflow.backend.feature.task.domain.source.WeatherFact
import com.nexusflow.backend.feature.task.domain.source.WeatherQuery
import com.nexusflow.backend.feature.task.domain.source.WeatherSource
import com.nexusflow.backend.feature.task.domain.source.WebDiscoverySource
import com.nexusflow.backend.feature.task.domain.source.WebSearchHit
import com.nexusflow.backend.feature.task.domain.source.WebSearchQuery
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId

class WeatherForecastReadTool(
    private val primary: WeatherSource?,
    private val secondary: WeatherSource?,
) : ReadTool {
    override val definition: ReadToolDefinition = ReadToolDefinition(
        key = WeatherForecastKey,
        description = "Read a weather forecast for a typed geographic point and optional date range.",
        argumentHint = "Requires latitude and longitude. Optional dateFrom/dateTo use YYYY-MM-DD.",
        activityKind = ReadToolActivityKind.Weather,
    )

    override suspend fun execute(
        proposedArguments: JsonObject,
        context: ReadToolExecutionContext,
    ): ReadToolOutcome {
        val args = proposedArguments.decodeWeatherArgs()
            ?: return ReadToolOutcome.MissingInput(setOf("location"))
        val dateRange = proposedArguments.decodeDateRangeOrMissing()
            ?: return ReadToolOutcome.InvalidArguments("invalid_date_range")
        val query = WeatherQuery(
            point = GeoPoint(args.latitude, args.longitude),
            dateFrom = dateRange.dateFrom,
            dateTo = dateRange.dateTo,
        )
        val outcomes = mutableListOf<SourceOutcome>()
        val fact = primary.forecastSafely(query, outcomes)
            ?: secondary.forecastSafely(query, outcomes)
        val unavailable = primary == null && secondary == null ||
            outcomes.isNotEmpty() && outcomes.all { outcome -> outcome == SourceOutcome.TechnicalFailure }
        if (unavailable) {
            return ReadToolOutcome.Unavailable("weather.forecast source is unavailable")
        }
        val evidence = fact?.toEvidence(query) ?: return ReadToolOutcome.Empty
        return ReadToolOutcome.Success(ReadToolEvidencePayload(listOf(evidence)))
    }

    private suspend fun WeatherSource?.forecastSafely(
        query: WeatherQuery,
        outcomes: MutableList<SourceOutcome>,
    ): WeatherFact? =
        this?.let { source ->
            try {
                source.forecast(query).also { fact ->
                    outcomes += if (fact == null) SourceOutcome.SuccessEmpty else SourceOutcome.SuccessWithResults
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: ExternalSourceException) {
                outcomes += SourceOutcome.TechnicalFailure
                null
            }
        }

    private fun JsonObject.decodeWeatherArgs(): WeatherForecastArgs? {
        val latitude = this["latitude"].primitiveOrNull()?.doubleOrNull ?: return null
        val longitude = this["longitude"].primitiveOrNull()?.doubleOrNull ?: return null
        return runCatching { WeatherForecastArgs(latitude, longitude) }.getOrNull()
    }
}

class MovieDetailsReadTool(
    private val metadataAcquirer: MovieMetadataAcquirer?,
) : ReadTool {
    override val definition: ReadToolDefinition = ReadToolDefinition(
        key = MovieDetailsKey,
        description = "Read title-based movie metadata such as release date, runtime, genres, and summary.",
        argumentHint = "Requires title. Optional region and language are provider hints.",
        activityKind = ReadToolActivityKind.Movie,
    )

    override suspend fun execute(
        proposedArguments: JsonObject,
        context: ReadToolExecutionContext,
    ): ReadToolOutcome {
        val title = proposedArguments.requiredText("title")
            ?: return ReadToolOutcome.MissingInput(setOf("title"))
        val acquirer = metadataAcquirer
            ?: return ReadToolOutcome.Unavailable("movie.details source is unavailable")
        return try {
            val evidence = acquirer
                .acquire(
                    MovieMetadataQuery(
                        title = title,
                        region = proposedArguments.optionalText("region"),
                        language = proposedArguments.optionalText("language"),
                    ),
                )
                .take(MAX_EVIDENCE_ITEMS)
                .mapIndexedNotNull { index, candidate -> candidate.toEvidence(index) }
            if (evidence.isEmpty()) {
                ReadToolOutcome.Empty
            } else {
                ReadToolOutcome.Success(ReadToolEvidencePayload(evidence))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: ExternalSourceException) {
            ReadToolOutcome.Unavailable("movie.details source is unavailable")
        } catch (_: TaskDependencyUnavailableException) {
            ReadToolOutcome.Unavailable("movie.details source is unavailable")
        }
    }
}

class WebSearchReadTool(
    private val webDiscoverySource: WebDiscoverySource?,
) : ReadTool {
    override val definition: ReadToolDefinition = ReadToolDefinition(
        key = WebSearchKey,
        description = "Read bounded web search snippets for current external facts.",
        argumentHint = "Requires query. Optional maxResults must be between 1 and 8.",
        activityKind = ReadToolActivityKind.Web,
    )

    override suspend fun execute(
        proposedArguments: JsonObject,
        context: ReadToolExecutionContext,
    ): ReadToolOutcome =
        executeWebSearch(
            source = webDiscoverySource,
            proposedArguments = proposedArguments,
            sourceKey = WebSearchKey,
            defaultQuery = null,
            evidencePrefix = "web-search",
        )
}

class MovieDiscoveryReadTool(
    private val discoveryAcquirer: MovieDiscoveryAcquirer?,
) : ReadTool {
    override val definition: ReadToolDefinition = ReadToolDefinition(
        key = MovieDiscoveryKey,
        description = "Read current movie discovery evidence such as now playing, trending, or upcoming movies.",
        argumentHint = "Optional mode, region, language, and maxResults. Does not accept or require title.",
        activityKind = ReadToolActivityKind.Movie,
    )

    override suspend fun execute(
        proposedArguments: JsonObject,
        context: ReadToolExecutionContext,
    ): ReadToolOutcome {
        val acquirer = discoveryAcquirer
            ?: return ReadToolOutcome.Unavailable("movie.discovery source is unavailable")
        val mode = proposedArguments.optionalMovieDiscoveryMode()
            ?: return ReadToolOutcome.InvalidArguments("invalid_movie_discovery_mode")
        val maxResults = proposedArguments.maxResultsOrMissing(DEFAULT_MOVIE_DISCOVERY_MAX_RESULTS)
            ?: return ReadToolOutcome.InvalidArguments("invalid_max_results")
        val dateRange = proposedArguments.decodeDateRangeOrMissing()
            ?: return ReadToolOutcome.InvalidArguments("invalid_date_range")
        return try {
            val acquisition = acquirer.acquire(
                MovieDiscoveryQuery(
                    mode = mode,
                    region = proposedArguments.optionalText("region"),
                    language = proposedArguments.optionalText("language"),
                    dateFrom = dateRange.dateFrom,
                    dateTo = dateRange.dateTo,
                    maxResults = maxResults,
                ),
            )
            val evidence = (
                acquisition.candidates.mapIndexedNotNull { index, candidate -> candidate.toEvidence(index) } +
                    acquisition.webHits.mapIndexedNotNull { index, hit ->
                        hit.toEvidence(index, MovieDiscoveryKey, "movie-discovery-web")
                    }
            ).take(maxResults)
            if (evidence.isEmpty()) {
                ReadToolOutcome.Empty
            } else {
                ReadToolOutcome.Success(ReadToolEvidencePayload(evidence))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: ExternalSourceException) {
            ReadToolOutcome.Unavailable("movie.discovery source is unavailable")
        } catch (_: TaskDependencyUnavailableException) {
            ReadToolOutcome.Unavailable("movie.discovery source is unavailable")
        }
    }
}

class MovieShowtimeReadTool(
    private val showtimeAcquirer: MovieShowtimeAcquirer?,
) : ReadTool {
    override val definition: ReadToolDefinition = ReadToolDefinition(
        key = MovieShowtimesKey,
        description = "Read authoritative movie showtime facts from official cinema sources.",
        argumentHint = "Requires city. Optional title, countryCode, dateFrom, and dateTo use YYYY-MM-DD.",
        activityKind = ReadToolActivityKind.Movie,
    )

    override suspend fun execute(
        proposedArguments: JsonObject,
        context: ReadToolExecutionContext,
    ): ReadToolOutcome {
        val city = proposedArguments.optionalText("city")
            ?: return ReadToolOutcome.MissingInput(setOf("city"))
        val dateRange = proposedArguments.decodeDateRangeOrMissing()
            ?: return ReadToolOutcome.InvalidArguments("invalid_date_range")
        val acquirer = showtimeAcquirer
            ?: return ReadToolOutcome.Unavailable("movie.showtimes source is unavailable")
        return try {
            val evidence = acquirer
                .acquire(
                    MovieShowtimeQuery(
                        title = proposedArguments.optionalText("title"),
                        city = city,
                        countryCode = proposedArguments.optionalText("countryCode") ?: "CN",
                        dateFrom = dateRange.dateFrom,
                        dateTo = dateRange.dateTo,
                    ),
                    referenceTime = context.referenceTime,
                )
                .take(MAX_EVIDENCE_ITEMS)
                .mapIndexedNotNull { index, opportunity -> opportunity.toShowtimeEvidence(index) }
            if (evidence.isEmpty()) {
                ReadToolOutcome.Empty
            } else {
                ReadToolOutcome.Success(ReadToolEvidencePayload(evidence))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: ExternalSourceException) {
            ReadToolOutcome.Unavailable("movie.showtimes source is unavailable")
        } catch (_: TaskDependencyUnavailableException) {
            ReadToolOutcome.Unavailable("movie.showtimes source is unavailable")
        }
    }
}

class SportsFixturesReadTool(
    private val fixtureAcquirer: FootballFixtureAcquirer?,
    private val competitionRegistry: FootballCompetitionRegistry,
) : ReadTool {
    override val definition: ReadToolDefinition = ReadToolDefinition(
        key = SportsFixturesKey,
        description = "Read football fixture facts for a canonical competition, team, and optional date range.",
        argumentHint = "Optional competition, team, dateFrom/dateTo, and maxResults. Dates use YYYY-MM-DD.",
        activityKind = ReadToolActivityKind.Sports,
    )

    override suspend fun execute(
        proposedArguments: JsonObject,
        context: ReadToolExecutionContext,
    ): ReadToolOutcome {
        val acquirer = fixtureAcquirer
            ?: return ReadToolOutcome.Unavailable("sports.fixtures source is unavailable")
        val competition = proposedArguments.optionalText("competition")?.let { input ->
            competitionRegistry.resolve(input)
                ?: return ReadToolOutcome.InvalidArguments("unsupported_competition")
        }
        val maxResults = proposedArguments.maxResultsOrMissing()
            ?: return ReadToolOutcome.InvalidArguments("invalid_max_results")
        val dateRange = proposedArguments.decodeDateRangeOrMissing()
            ?: return ReadToolOutcome.InvalidArguments("invalid_date_range")
        val effectiveDateRange = dateRange.withDefaultFixtureWindow(context)
        if (effectiveDateRange.dateTo != null &&
            effectiveDateRange.dateFrom != null &&
            effectiveDateRange.dateTo.isBefore(effectiveDateRange.dateFrom)
        ) {
            return ReadToolOutcome.InvalidArguments("inverted_date_range")
        }
        return try {
            val evidence = acquirer
                .acquireFixtures(
                    FootballFixtureQuery(
                        teamName = proposedArguments.optionalText("team"),
                        dateFrom = effectiveDateRange.dateFrom,
                        dateTo = effectiveDateRange.dateTo,
                        league = competition?.slug,
                    ),
                    referenceTime = context.referenceTime,
                )
                .take(maxResults)
                .mapIndexedNotNull { index, fixture -> fixture.toEvidence(index, competition) }
            if (evidence.isEmpty()) {
                ReadToolOutcome.Empty
            } else {
                ReadToolOutcome.Success(ReadToolEvidencePayload(evidence))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: TaskDependencyUnavailableException) {
            ReadToolOutcome.Unavailable("sports.fixtures source is unavailable")
        } catch (_: ExternalSourceException) {
            ReadToolOutcome.Unavailable("sports.fixtures source is unavailable")
        }
    }
}

class MusicMetadataReadTool(
    private val metadataSource: MusicMetadataSource?,
) : ReadTool {
    override val definition: ReadToolDefinition = ReadToolDefinition(
        key = MusicMetadataKey,
        description = "Read MusicBrainz artist, release, or recording metadata.",
        argumentHint = "Requires query. Optional type is artist, release, or recording. Optional maxResults must be between 1 and 8.",
        activityKind = ReadToolActivityKind.Music,
    )

    override suspend fun execute(
        proposedArguments: JsonObject,
        context: ReadToolExecutionContext,
    ): ReadToolOutcome {
        val query = proposedArguments.requiredText("query")
            ?: return ReadToolOutcome.MissingInput(setOf("query"))
        val type = proposedArguments.optionalMusicMetadataType()
            ?: return ReadToolOutcome.InvalidArguments("invalid_music_metadata_type")
        val maxResults = proposedArguments.maxResultsOrMissing()
            ?: return ReadToolOutcome.InvalidArguments("invalid_max_results")
        val source = metadataSource
            ?: return ReadToolOutcome.Unavailable("music.metadata source is unavailable")
        return try {
            val evidence = source
                .search(MusicMetadataQuery(query = query, type = type, maxResults = maxResults))
                .take(maxResults)
                .mapIndexedNotNull { index, candidate -> candidate.toEvidence(index) }
            if (evidence.isEmpty()) {
                ReadToolOutcome.Empty
            } else {
                ReadToolOutcome.Success(ReadToolEvidencePayload(evidence))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: ExternalSourceException) {
            ReadToolOutcome.Unavailable("music.metadata source is unavailable")
        }
    }
}

class MusicEventsReadTool(
    private val eventAcquirer: LiveMusicEventAcquirer?,
) : ReadTool {
    override val definition: ReadToolDefinition = ReadToolDefinition(
        key = MusicEventsKey,
        description = "Read current ticketed live music event evidence such as concerts and festivals.",
        argumentHint = "Optional artist/keyword, city, countryCode, dateFrom/dateTo, and maxResults. Dates use YYYY-MM-DD.",
        activityKind = ReadToolActivityKind.Music,
    )

    override suspend fun execute(
        proposedArguments: JsonObject,
        context: ReadToolExecutionContext,
    ): ReadToolOutcome {
        val acquirer = eventAcquirer
            ?: return ReadToolOutcome.Unavailable("music.events source is unavailable")
        val maxResults = proposedArguments.maxResultsOrMissing()
            ?: return ReadToolOutcome.InvalidArguments("invalid_max_results")
        val dateRange = proposedArguments.decodeDateRangeOrMissing()
            ?: return ReadToolOutcome.InvalidArguments("invalid_date_range")
        val effectiveDateRange = dateRange.withDefaultEventWindow(context)
        if (effectiveDateRange.hasInvertedDates()) {
            return ReadToolOutcome.InvalidArguments("inverted_date_range")
        }
        return try {
            val evidence = acquirer
                .acquire(
                    LiveMusicEventQuery(
                        keyword = proposedArguments.optionalText("artist")
                            ?: proposedArguments.optionalText("keyword")
                            ?: DEFAULT_LIVE_MUSIC_EVENT_QUERY,
                        city = proposedArguments.optionalText("city"),
                        countryCode = proposedArguments.optionalText("countryCode"),
                        dateFrom = effectiveDateRange.dateFrom,
                        dateTo = effectiveDateRange.dateTo,
                    ),
                    referenceTime = context.referenceTime,
                )
                .take(maxResults)
                .mapIndexedNotNull { index, opportunity -> opportunity.toMusicEventEvidence(index) }
            if (evidence.isEmpty()) {
                ReadToolOutcome.Empty
            } else {
                ReadToolOutcome.Success(ReadToolEvidencePayload(evidence))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: ExternalSourceException) {
            ReadToolOutcome.Unavailable("music.events source is unavailable")
        } catch (_: TaskDependencyUnavailableException) {
            ReadToolOutcome.Unavailable("music.events source is unavailable")
        }
    }
}

class SportsEventsReadTool(
    private val eventAcquirer: GeneralSportsEventAcquirer?,
) : ReadTool {
    override val definition: ReadToolDefinition = ReadToolDefinition(
        key = SportsEventsKey,
        description = "Read current ticketed general sports event evidence, distinct from scheduled football fixtures.",
        argumentHint = "Optional keyword/sport, city, countryCode, dateFrom/dateTo, and maxResults. Dates use YYYY-MM-DD.",
        activityKind = ReadToolActivityKind.Sports,
    )

    override suspend fun execute(
        proposedArguments: JsonObject,
        context: ReadToolExecutionContext,
    ): ReadToolOutcome {
        val acquirer = eventAcquirer
            ?: return ReadToolOutcome.Unavailable("sports.events source is unavailable")
        val maxResults = proposedArguments.maxResultsOrMissing()
            ?: return ReadToolOutcome.InvalidArguments("invalid_max_results")
        val dateRange = proposedArguments.decodeDateRangeOrMissing()
            ?: return ReadToolOutcome.InvalidArguments("invalid_date_range")
        val effectiveDateRange = dateRange.withDefaultEventWindow(context)
        if (effectiveDateRange.hasInvertedDates()) {
            return ReadToolOutcome.InvalidArguments("inverted_date_range")
        }
        return try {
            val evidence = acquirer
                .acquire(
                    GeneralSportsEventQuery(
                        keyword = proposedArguments.optionalText("sport")
                            ?: proposedArguments.optionalText("keyword")
                            ?: DEFAULT_SPORTS_EVENT_QUERY,
                        city = proposedArguments.optionalText("city"),
                        countryCode = proposedArguments.optionalText("countryCode"),
                        dateFrom = effectiveDateRange.dateFrom,
                        dateTo = effectiveDateRange.dateTo,
                    ),
                    referenceTime = context.referenceTime,
                )
                .take(maxResults)
                .mapIndexedNotNull { index, opportunity -> opportunity.toSportsEventEvidence(index) }
            if (evidence.isEmpty()) {
                ReadToolOutcome.Empty
            } else {
                ReadToolOutcome.Success(ReadToolEvidencePayload(evidence))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: ExternalSourceException) {
            ReadToolOutcome.Unavailable("sports.events source is unavailable")
        } catch (_: TaskDependencyUnavailableException) {
            ReadToolOutcome.Unavailable("sports.events source is unavailable")
        }
    }
}

class OutdoorTrailsReadTool(
    private val outdoorAcquirer: OutdoorAcquirer?,
) : ReadTool {
    override val definition: ReadToolDefinition = ReadToolDefinition(
        key = OutdoorTrailsKey,
        description = "Read bounded hiking trail candidates with typed place, route, and weather enrichment.",
        argumentHint = "Requires near/location. Optional keyword, centerLatitude/centerLongitude, originLatitude/originLongitude, dateFrom/dateTo, radiusMeters, and maxResults.",
        activityKind = ReadToolActivityKind.OtherResearch,
    )

    override suspend fun execute(
        proposedArguments: JsonObject,
        context: ReadToolExecutionContext,
    ): ReadToolOutcome {
        val near = proposedArguments.optionalText("near") ?: proposedArguments.optionalText("location")
            ?: return ReadToolOutcome.MissingInput(setOf("near", "location"))
        val maxResults = proposedArguments.maxResultsOrMissing()
            ?: return ReadToolOutcome.InvalidArguments("invalid_max_results")
        val dateRange = proposedArguments.decodeDateRangeOrMissing()
            ?: return ReadToolOutcome.InvalidArguments("invalid_date_range")
        if (dateRange.hasInvertedDates()) {
            return ReadToolOutcome.InvalidArguments("inverted_date_range")
        }
        val radiusMeters = proposedArguments.radiusMetersOrMissing()
            ?: return ReadToolOutcome.InvalidArguments("invalid_radius_meters")
        val center = when (val point = proposedArguments.geoPointFromFields("centerLatitude" to "centerLongitude", "latitude" to "longitude")) {
            GeoPointArgument.Invalid -> return ReadToolOutcome.InvalidArguments("invalid_center_coordinates")
            GeoPointArgument.Missing -> null
            is GeoPointArgument.Present -> point.value
        }
        val origin = when (val point = proposedArguments.geoPointFromFields("originLatitude" to "originLongitude", "fromLatitude" to "fromLongitude")) {
            GeoPointArgument.Invalid -> return ReadToolOutcome.InvalidArguments("invalid_origin_coordinates")
            GeoPointArgument.Missing -> null
            is GeoPointArgument.Present -> point.value
        }
        val acquirer = outdoorAcquirer
            ?: return ReadToolOutcome.Unavailable("outdoor.trails source is unavailable")
        return try {
            val evidence = acquirer
                .acquire(
                    TrailDiscoveryQuery(
                        keyword = proposedArguments.optionalText("keyword") ?: DEFAULT_OUTDOOR_TRAIL_QUERY,
                        near = near,
                        center = center,
                        origin = origin,
                        radiusMeters = radiusMeters,
                        dateFrom = dateRange.dateFrom,
                        dateTo = dateRange.dateTo,
                    ),
                    referenceTime = context.referenceTime,
                )
                .take(maxResults)
                .mapIndexedNotNull { index, opportunity -> opportunity.toOutdoorTrailEvidence(index) }
            if (evidence.isEmpty()) {
                ReadToolOutcome.Empty
            } else {
                ReadToolOutcome.Success(ReadToolEvidencePayload(evidence))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: ExternalSourceException) {
            ReadToolOutcome.Unavailable("outdoor.trails source is unavailable")
        } catch (_: TaskDependencyUnavailableException) {
            ReadToolOutcome.Unavailable("outdoor.trails source is unavailable")
        }
    }
}

class PlacesSearchReadTool(
    private val primary: PlaceLookupSource?,
    private val secondary: PlaceLookupSource?,
) : ReadTool {
    override val definition: ReadToolDefinition = ReadToolDefinition(
        key = PlacesSearchKey,
        description = "Read typed place lookup evidence for a bounded public location query.",
        argumentHint = "Requires text or query. Optional near and maxResults.",
        activityKind = ReadToolActivityKind.PlaceSearch,
    )

    override suspend fun execute(
        proposedArguments: JsonObject,
        context: ReadToolExecutionContext,
    ): ReadToolOutcome {
        val text = proposedArguments.optionalText("text") ?: proposedArguments.optionalText("query")
            ?: return ReadToolOutcome.MissingInput(setOf("text", "query"))
        val maxResults = proposedArguments.maxResultsOrMissing()
            ?: return ReadToolOutcome.InvalidArguments("invalid_max_results")
        val query = PlaceLookupQuery(text = text, near = proposedArguments.optionalText("near"))
        val outcomes = mutableListOf<SourceOutcome>()
        val places = primary.findPlacesSafely(query, outcomes).orEmpty()
            .takeIf(List<PlaceCandidate>::isNotEmpty)
            ?: secondary.findPlacesSafely(query, outcomes).orEmpty()
        val unavailable = primary == null && secondary == null ||
            outcomes.isNotEmpty() && outcomes.all { outcome -> outcome == SourceOutcome.TechnicalFailure }
        if (unavailable) {
            return ReadToolOutcome.Unavailable("places.search source is unavailable")
        }
        val evidence = places
            .take(maxResults)
            .mapIndexedNotNull { index, candidate -> candidate.toEvidence(index) }
        return if (evidence.isEmpty()) {
            ReadToolOutcome.Empty
        } else {
            ReadToolOutcome.Success(ReadToolEvidencePayload(evidence))
        }
    }

    private suspend fun PlaceLookupSource?.findPlacesSafely(
        query: PlaceLookupQuery,
        outcomes: MutableList<SourceOutcome>,
    ): List<PlaceCandidate>? =
        this?.let { source ->
            try {
                source.find(query).also { candidates ->
                    outcomes += if (candidates.isEmpty()) SourceOutcome.SuccessEmpty else SourceOutcome.SuccessWithResults
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: ExternalSourceException) {
                outcomes += SourceOutcome.TechnicalFailure
                null
            }
        }
}

class RouteEstimateReadTool(
    private val primary: RouteSource?,
    private val secondary: RouteSource?,
) : ReadTool {
    override val definition: ReadToolDefinition = ReadToolDefinition(
        key = RouteEstimateKey,
        description = "Read typed route distance and travel-time evidence between bounded coordinates.",
        argumentHint = "Requires originLatitude/originLongitude and destinationLatitude/destinationLongitude. Optional profile supports hiking, walking, or foot.",
        activityKind = ReadToolActivityKind.Route,
    )

    override suspend fun execute(
        proposedArguments: JsonObject,
        context: ReadToolExecutionContext,
    ): ReadToolOutcome {
        val origin = when (val point = proposedArguments.geoPointFromFields("originLatitude" to "originLongitude", "fromLatitude" to "fromLongitude")) {
            GeoPointArgument.Invalid -> return ReadToolOutcome.InvalidArguments("invalid_origin_coordinates")
            GeoPointArgument.Missing -> return ReadToolOutcome.MissingInput(setOf("originLatitude", "originLongitude"))
            is GeoPointArgument.Present -> point.value
        }
        val destination = when (val point = proposedArguments.geoPointFromFields("destinationLatitude" to "destinationLongitude", "toLatitude" to "toLongitude")) {
            GeoPointArgument.Invalid -> return ReadToolOutcome.InvalidArguments("invalid_destination_coordinates")
            GeoPointArgument.Missing -> return ReadToolOutcome.MissingInput(setOf("destinationLatitude", "destinationLongitude"))
            is GeoPointArgument.Present -> point.value
        }
        val profile = proposedArguments.routeProfileOrMissing()
            ?: return ReadToolOutcome.InvalidArguments("invalid_route_profile")
        val query = RouteQuery(destination = destination, origin = origin, profile = profile)
        val outcomes = mutableListOf<SourceOutcome>()
        val fact = primary.routeSafely(query, outcomes)
            ?: secondary.routeSafely(query, outcomes)
        val unavailable = primary == null && secondary == null ||
            outcomes.isNotEmpty() && outcomes.all { outcome -> outcome == SourceOutcome.TechnicalFailure }
        if (unavailable) {
            return ReadToolOutcome.Unavailable("route.estimate source is unavailable")
        }
        val evidence = fact?.toEvidence(query) ?: return ReadToolOutcome.Empty
        return ReadToolOutcome.Success(ReadToolEvidencePayload(listOf(evidence)))
    }

    private suspend fun RouteSource?.routeSafely(
        query: RouteQuery,
        outcomes: MutableList<SourceOutcome>,
    ): RouteFact? =
        this?.let { source ->
            try {
                source.route(query).also { fact ->
                    outcomes += if (fact == null) SourceOutcome.SuccessEmpty else SourceOutcome.SuccessWithResults
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: ExternalSourceException) {
                outcomes += SourceOutcome.TechnicalFailure
                null
            }
        }
}

private suspend fun executeWebSearch(
    source: WebDiscoverySource?,
    proposedArguments: JsonObject,
    sourceKey: ReadToolKey,
    defaultQuery: String?,
    evidencePrefix: String,
): ReadToolOutcome {
    val webSource = source ?: return ReadToolOutcome.Unavailable("${sourceKey.value} source is unavailable")
    val query = proposedArguments.optionalText("query") ?: defaultQuery
        ?: return ReadToolOutcome.MissingInput(setOf("query"))
    val maxResults = proposedArguments.maxResultsOrMissing()
        ?: return ReadToolOutcome.InvalidArguments("invalid_max_results")
    return try {
        val evidence = webSource
            .search(WebSearchQuery(query = query, maxResults = maxResults))
            .take(maxResults)
            .mapIndexedNotNull { index, hit -> hit.toEvidence(index, sourceKey, evidencePrefix) }
        if (evidence.isEmpty()) {
            ReadToolOutcome.Empty
        } else {
            ReadToolOutcome.Success(ReadToolEvidencePayload(evidence))
        }
    } catch (error: CancellationException) {
        throw error
    } catch (_: ExternalSourceException) {
        ReadToolOutcome.Unavailable("${sourceKey.value} source is unavailable")
    }
}

private fun WeatherFact.toEvidence(query: WeatherQuery): ReadToolEvidence? {
    val facts = listOfNotNull(
        fact(ReadToolFactKind.SUMMARY, summary),
        fact(ReadToolFactKind.TEMPERATURE_CELSIUS, temperatureCelsius),
        fact(ReadToolFactKind.PRECIPITATION_PERCENT, precipitationProbabilityPercent),
        fact(ReadToolFactKind.WIND_SPEED_KPH, windSpeedKph),
        ReadToolFact(ReadToolFactKind.LATITUDE, ReadToolFactValue.Decimal(query.point.latitude)),
        ReadToolFact(ReadToolFactKind.LONGITUDE, ReadToolFactValue.Decimal(query.point.longitude)),
    )
    if (facts.isEmpty()) return null
    return ReadToolEvidence(
        sourceId = "weather-forecast-${source.sourceId}-${query.stableEvidenceSuffix()}",
        sourceUrl = source.uri,
        sourceKey = WeatherForecastKey.value,
        sourceUpdatedAt = source.sourceUpdatedAt,
        authority = source.authority.toReadToolAuthority(),
        facts = facts,
    )
}

private fun MovieMetadataCandidate.toEvidence(index: Int): ReadToolEvidence? {
    val facts = listOfNotNull(
        fact(ReadToolFactKind.TITLE, title),
        fact(ReadToolFactKind.RELEASE_DATE, releaseDate?.toString()),
        fact(ReadToolFactKind.RUNTIME_MINUTES, runtimeMinutes?.toLong()),
        fact(ReadToolFactKind.GENRES, genres.takeIf(Set<String>::isNotEmpty)?.joinToString(", ")),
        fact(ReadToolFactKind.SUMMARY, summary),
    )
    if (facts.isEmpty()) return null
    return ReadToolEvidence(
        sourceId = "movie-details-${index + 1}-${(source.sourceId + externalMovieId).stableEvidenceSuffix()}",
        sourceUrl = source.uri,
        sourceKey = MovieDetailsKey.value,
        sourceUpdatedAt = source.sourceUpdatedAt,
        authority = source.authority.toReadToolAuthority(),
        facts = facts,
    )
}

private fun MovieDiscoveryCandidate.toEvidence(index: Int): ReadToolEvidence? {
    val facts = listOfNotNull(
        fact(ReadToolFactKind.TITLE, title),
        fact(ReadToolFactKind.ORIGINAL_TITLE, originalTitle),
        fact(ReadToolFactKind.RELEASE_DATE, releaseDate?.toString()),
        fact(ReadToolFactKind.GENRES, genreIds.takeIf(List<String>::isNotEmpty)?.joinToString(", ")),
        fact(ReadToolFactKind.POPULARITY, popularity),
        fact(ReadToolFactKind.SUMMARY, summary),
    )
    if (facts.isEmpty()) return null
    return ReadToolEvidence(
        sourceId = "movie-discovery-${index + 1}-${(source.sourceId + externalMovieId).stableEvidenceSuffix()}",
        sourceUrl = publicUrl ?: source.uri,
        sourceKey = MovieDiscoveryKey.value,
        sourceUpdatedAt = source.sourceUpdatedAt,
        authority = source.authority.toReadToolAuthority(),
        facts = facts,
    )
}

private fun Opportunity.toShowtimeEvidence(index: Int): ReadToolEvidence? {
    val source = sources.firstOrNull()
    val distilledFacts = listOfNotNull(
        fact(ReadToolFactKind.TITLE, title),
        fact(ReadToolFactKind.SUMMARY, facts.summary),
        fact(ReadToolFactKind.START_TIME, facts.startTime),
        fact(ReadToolFactKind.END_TIME, facts.endTime),
        fact(ReadToolFactKind.LOCATION_NAME, facts.location?.displayName),
        fact(ReadToolFactKind.PRICE, facts.price),
        fact(ReadToolFactKind.AVAILABILITY, facts.availability),
        fact(ReadToolFactKind.ACTIVITY_MODE, facts.activityMode),
    )
    if (distilledFacts.isEmpty()) return null
    return ReadToolEvidence(
        sourceId = "movie-showtime-${index + 1}-${(source?.sourceId.orEmpty() + externalKey).stableEvidenceSuffix()}",
        sourceUrl = source?.uri,
        sourceKey = MovieShowtimesKey.value,
        sourceUpdatedAt = source?.sourceUpdatedAt,
        authority = source?.authority?.toReadToolAuthority() ?: ReadToolSourceAuthority.StructuredPrimary,
        facts = distilledFacts,
    )
}

private fun FootballFixtureCandidate.toEvidence(
    index: Int,
    requestedCompetition: FootballCompetition?,
): ReadToolEvidence? {
    val source = sources.firstOrNull()
    val facts = listOfNotNull(
        fact(ReadToolFactKind.COMPETITION, competition ?: requestedCompetition?.displayName),
        fact(ReadToolFactKind.HOME_TEAM, homeTeam),
        fact(ReadToolFactKind.AWAY_TEAM, awayTeam),
        fact(ReadToolFactKind.START_TIME, startsAt),
        fact(ReadToolFactKind.LOCATION_NAME, listOfNotNull(venueName, venueCity).joinToString(", ").takeIf(String::isNotBlank)),
        fact(ReadToolFactKind.STATUS, status.name),
        fact(ReadToolFactKind.SOURCE_URL, source?.uri),
    )
    if (facts.isEmpty()) return null
    return ReadToolEvidence(
        sourceId = "sports-fixtures-${index + 1}-${(source?.sourceId.orEmpty() + externalFixtureId).stableEvidenceSuffix()}",
        sourceUrl = source?.uri,
        sourceKey = SportsFixturesKey.value,
        sourceUpdatedAt = source?.sourceUpdatedAt,
        authority = source?.authority?.toReadToolAuthority() ?: ReadToolSourceAuthority.StructuredPrimary,
        facts = facts,
    )
}

private fun MusicMetadataCandidate.toEvidence(index: Int): ReadToolEvidence? {
    val facts = listOfNotNull(
        fact(ReadToolFactKind.TYPE, type.name.lowercase()),
        fact(ReadToolFactKind.TITLE, title),
        fact(ReadToolFactKind.ARTISTS, artists.takeIf(Set<String>::isNotEmpty)?.joinToString(", ")),
        fact(ReadToolFactKind.DATE, date?.toString()),
        fact(ReadToolFactKind.COUNTRY_CODE, countryCode),
        fact(ReadToolFactKind.STATUS, status),
        fact(ReadToolFactKind.SUMMARY, disambiguation),
        fact(ReadToolFactKind.DURATION_MINUTES, lengthMillis?.let { it / 60_000L }),
    )
    if (facts.isEmpty()) return null
    return ReadToolEvidence(
        sourceId = "music-metadata-${index + 1}-${(source.sourceId + externalId).stableEvidenceSuffix()}",
        sourceUrl = source.uri,
        sourceKey = MusicMetadataKey.value,
        sourceUpdatedAt = source.sourceUpdatedAt,
        authority = source.authority.toReadToolAuthority(),
        facts = facts,
    )
}

private fun Opportunity.toMusicEventEvidence(index: Int): ReadToolEvidence? =
    toEventEvidence(
        index = index,
        sourceKey = MusicEventsKey,
        evidencePrefix = "music-events",
    )

private fun Opportunity.toSportsEventEvidence(index: Int): ReadToolEvidence? =
    toEventEvidence(
        index = index,
        sourceKey = SportsEventsKey,
        evidencePrefix = "sports-events",
    )

private fun Opportunity.toOutdoorTrailEvidence(index: Int): ReadToolEvidence? {
    val source = sources.firstOrNull()
    val distilledFacts = listOfNotNull(
        fact(ReadToolFactKind.TITLE, title),
        fact(ReadToolFactKind.SUMMARY, facts.summary),
        fact(ReadToolFactKind.START_TIME, facts.startTime),
        fact(ReadToolFactKind.END_TIME, facts.endTime),
        fact(ReadToolFactKind.LOCATION_NAME, facts.location?.displayName),
        fact(ReadToolFactKind.ACTIVITY_MODE, facts.activityMode),
        fact(ReadToolFactKind.PRICE, facts.price),
        fact(ReadToolFactKind.AVAILABILITY, facts.availability),
        fact(ReadToolFactKind.COMMUTE_MINUTES, facts.commute?.minutes?.toLong()),
        fact(ReadToolFactKind.DISTANCE_METERS, facts.attributes["distanceMeters"]?.numberValueOrNull()),
        fact(ReadToolFactKind.ELEVATION_GAIN_METERS, facts.attributes["elevationGainMeters"]?.numberValueOrNull()),
        fact(ReadToolFactKind.DURATION_MINUTES, facts.attributes["routeDurationMinutes"]?.numberValueOrNull()),
        facts.attributes["weatherSummary"]?.textValueOrNull()?.let { fact(ReadToolFactKind.SUMMARY, it) },
        fact(ReadToolFactKind.SOURCE_URL, source?.uri),
    )
    if (distilledFacts.isEmpty()) return null
    return ReadToolEvidence(
        sourceId = "outdoor-trails-${index + 1}-${(source?.sourceId.orEmpty() + externalKey).stableEvidenceSuffix()}",
        sourceUrl = source?.uri,
        sourceKey = OutdoorTrailsKey.value,
        sourceUpdatedAt = source?.sourceUpdatedAt,
        authority = source?.authority?.toReadToolAuthority() ?: ReadToolSourceAuthority.StructuredPrimary,
        facts = distilledFacts,
    )
}

private fun Opportunity.toEventEvidence(
    index: Int,
    sourceKey: ReadToolKey,
    evidencePrefix: String,
): ReadToolEvidence? {
    val source = sources.firstOrNull()
    val distilledFacts = listOfNotNull(
        fact(ReadToolFactKind.TITLE, title),
        fact(ReadToolFactKind.SUMMARY, facts.summary),
        fact(ReadToolFactKind.START_TIME, facts.startTime),
        fact(ReadToolFactKind.END_TIME, facts.endTime),
        fact(ReadToolFactKind.LOCATION_NAME, facts.location?.displayName),
        fact(ReadToolFactKind.ACTIVITY_MODE, facts.activityMode),
        fact(ReadToolFactKind.PRICE, facts.price),
        fact(ReadToolFactKind.AVAILABILITY, facts.availability),
        fact(ReadToolFactKind.SOURCE_URL, source?.uri),
    )
    if (distilledFacts.isEmpty()) return null
    return ReadToolEvidence(
        sourceId = "$evidencePrefix-${index + 1}-${(source?.sourceId.orEmpty() + externalKey).stableEvidenceSuffix()}",
        sourceUrl = source?.uri,
        sourceKey = sourceKey.value,
        sourceUpdatedAt = source?.sourceUpdatedAt,
        authority = source?.authority?.toReadToolAuthority() ?: ReadToolSourceAuthority.StructuredPrimary,
        facts = distilledFacts,
    )
}

private fun PlaceCandidate.toEvidence(index: Int): ReadToolEvidence? {
    val source = sources.firstOrNull()
    val facts = listOfNotNull(
        fact(ReadToolFactKind.TITLE, displayName),
        fact(ReadToolFactKind.LOCATION_NAME, displayName),
        ReadToolFact(ReadToolFactKind.LATITUDE, ReadToolFactValue.Decimal(point.latitude)),
        ReadToolFact(ReadToolFactKind.LONGITUDE, ReadToolFactValue.Decimal(point.longitude)),
        fact(ReadToolFactKind.SOURCE_URL, publicUrl),
    )
    if (facts.isEmpty()) return null
    return ReadToolEvidence(
        sourceId = "places-search-${index + 1}-${(source?.sourceId.orEmpty() + externalPlaceId).stableEvidenceSuffix()}",
        sourceUrl = publicUrl ?: source?.uri,
        sourceKey = PlacesSearchKey.value,
        sourceUpdatedAt = source?.sourceUpdatedAt,
        authority = source?.authority?.toReadToolAuthority() ?: ReadToolSourceAuthority.StructuredPrimary,
        facts = facts,
    )
}

private fun RouteFact.toEvidence(query: RouteQuery): ReadToolEvidence? {
    val facts = listOfNotNull(
        fact(ReadToolFactKind.DISTANCE_METERS, distanceMeters?.toLong()),
        fact(ReadToolFactKind.DURATION_MINUTES, durationMinutes?.toLong()),
        fact(ReadToolFactKind.COMMUTE_MINUTES, commuteMinutes?.toLong()),
        ReadToolFact(ReadToolFactKind.LATITUDE, ReadToolFactValue.Decimal(query.destination.latitude)),
        ReadToolFact(ReadToolFactKind.LONGITUDE, ReadToolFactValue.Decimal(query.destination.longitude)),
        fact(ReadToolFactKind.PROFILE, query.profile.name.lowercase()),
    )
    if (facts.isEmpty()) return null
    return ReadToolEvidence(
        sourceId = "route-estimate-${(source.sourceId + query.stableEvidenceSuffix()).stableEvidenceSuffix()}",
        sourceUrl = source.uri,
        sourceKey = RouteEstimateKey.value,
        sourceUpdatedAt = source.sourceUpdatedAt,
        authority = source.authority.toReadToolAuthority(),
        facts = facts,
    )
}

private fun WebSearchHit.toEvidence(
    index: Int,
    sourceKey: ReadToolKey,
    evidencePrefix: String,
): ReadToolEvidence? {
    val facts = listOfNotNull(
        fact(ReadToolFactKind.TITLE, title),
        fact(ReadToolFactKind.SUMMARY, content?.take(MAX_WEB_SNIPPET_CHARS)),
        fact(ReadToolFactKind.SOURCE_URL, url),
    )
    if (url.isBlank() || facts.isEmpty()) return null
    return ReadToolEvidence(
        sourceId = "$evidencePrefix-${index + 1}-${url.stableEvidenceSuffix()}",
        sourceUrl = url,
        sourceKey = sourceKey.value,
        authority = ReadToolSourceAuthority.GeneralWeb,
        facts = facts,
    )
}

private fun JsonObject.requiredText(field: String): String? =
    optionalText(field)?.takeIf(String::isNotBlank)

private fun JsonObject.optionalText(field: String): String? =
    this[field].primitiveOrNull()?.contentOrNull?.trim()?.takeIf(String::isNotBlank)

private fun JsonObject.decodeDateRangeOrMissing(): DateRange? =
    try {
        DateRange(
            dateFrom = optionalLocalDate("dateFrom"),
            dateTo = optionalLocalDate("dateTo"),
        )
    } catch (_: IllegalArgumentException) {
        null
    }

private fun JsonObject.optionalLocalDate(field: String): LocalDate? {
    val text = optionalText(field) ?: return null
    return runCatching { LocalDate.parse(text) }.getOrElse {
        throw IllegalArgumentException("$field must be YYYY-MM-DD")
    }
}

private fun JsonObject.maxResultsOrMissing(defaultValue: Int = DEFAULT_WEB_MAX_RESULTS): Int? {
    val proposed = this["maxResults"] ?: this["maxresults"] ?: return defaultValue
    val maxResults = proposed.primitiveOrNull()?.intOrNull ?: return null
    return maxResults.takeIf { it in 1..8 }
}

private fun JsonObject.radiusMetersOrMissing(): Int? {
    val proposed = this["radiusMeters"] ?: return DEFAULT_TRAIL_RADIUS_METERS
    val radiusMeters = proposed.primitiveOrNull()?.intOrNull ?: return null
    return radiusMeters.takeIf { it in 1..50_000 }
}

private fun JsonObject.optionalMovieDiscoveryMode(): MovieDiscoveryMode? =
    when (optionalText("mode")?.lowercase()) {
        null,
        "now_playing",
        "nowplaying",
        "now playing",
        -> MovieDiscoveryMode.NowPlaying
        "upcoming" -> MovieDiscoveryMode.Upcoming
        "trending" -> MovieDiscoveryMode.Trending
        else -> null
    }

private fun JsonObject.optionalMusicMetadataType(): MusicMetadataSearchType? =
    when (optionalText("type")?.lowercase()) {
        null,
        "release",
        "album",
        -> MusicMetadataSearchType.Release
        "artist" -> MusicMetadataSearchType.Artist
        "recording",
        "track",
        "song",
        -> MusicMetadataSearchType.Recording
        else -> null
    }

private fun JsonObject.routeProfileOrMissing(): RouteProfile? =
    when (optionalText("profile")?.lowercase()) {
        null,
        "hiking",
        "walking",
        "foot",
        -> RouteProfile.Hiking
        else -> null
    }

private fun JsonObject.geoPointFromFields(vararg fieldPairs: Pair<String, String>): GeoPointArgument {
    fieldPairs.forEach { (latitudeField, longitudeField) ->
        val latitudeElement = this[latitudeField]
        val longitudeElement = this[longitudeField]
        if (latitudeElement == null && longitudeElement == null) {
            return@forEach
        }
        val latitude = latitudeElement.primitiveOrNull()?.doubleOrNull
        val longitude = longitudeElement.primitiveOrNull()?.doubleOrNull
        if (latitude == null || longitude == null) {
            return GeoPointArgument.Invalid
        }
        return runCatching { GeoPointArgument.Present(GeoPoint(latitude, longitude)) }
            .getOrDefault(GeoPointArgument.Invalid)
    }
    return GeoPointArgument.Missing
}

private fun kotlinx.serialization.json.JsonElement?.primitiveOrNull(): JsonPrimitive? =
    this as? JsonPrimitive

private fun fact(kind: ReadToolFactKind, value: String?): ReadToolFact? =
    value
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.take(MAX_FACT_TEXT_CHARS)
        ?.takeIf(String::isNotBlank)
        ?.let { ReadToolFact(kind, ReadToolFactValue.Text(it)) }

private fun fact(kind: ReadToolFactKind, value: Long?): ReadToolFact? =
    value?.let { ReadToolFact(kind, ReadToolFactValue.Integer(it)) }

private fun fact(kind: ReadToolFactKind, value: Double?): ReadToolFact? =
    value?.let { ReadToolFact(kind, ReadToolFactValue.Decimal(it)) }

private fun fact(kind: ReadToolFactKind, value: java.time.Instant?): ReadToolFact? =
    value?.let { ReadToolFact(kind, ReadToolFactValue.Timestamp(it)) }

private fun fact(kind: ReadToolFactKind, value: MoneyFact?): ReadToolFact? =
    value?.let { ReadToolFact(kind, ReadToolFactValue.Money(it.wholeUnits, it.currencyCode)) }

private fun fact(kind: ReadToolFactKind, value: AvailabilityFact?): ReadToolFact? =
    value?.let { fact(kind, it.name) }

private fun fact(kind: ReadToolFactKind, value: ActivityModeValue?): ReadToolFact? =
    value?.let { fact(kind, it.name) }

private fun DomainSourceAuthority.toReadToolAuthority(): ReadToolSourceAuthority =
    when (this) {
        DomainSourceAuthority.StructuredPrimary -> ReadToolSourceAuthority.StructuredPrimary
        DomainSourceAuthority.StructuredSecondary -> ReadToolSourceAuthority.StructuredSecondary
        DomainSourceAuthority.OfficialWeb -> ReadToolSourceAuthority.OfficialWeb
        DomainSourceAuthority.GeneralWeb -> ReadToolSourceAuthority.GeneralWeb
    }

private fun FactValue.textValueOrNull(): String? =
    (this as? FactValue.Text)?.value

private fun FactValue.numberValueOrNull(): Long? =
    (this as? FactValue.Number)?.value

private fun WeatherQuery.stableEvidenceSuffix(): String =
    "point=${point.latitude},${point.longitude}|from=${dateFrom ?: ""}|to=${dateTo ?: ""}".stableEvidenceSuffix()

private fun RouteQuery.stableEvidenceSuffix(): String =
    "from=${origin?.latitude},${origin?.longitude}|to=${destination.latitude},${destination.longitude}|profile=$profile"

private fun String.stableEvidenceSuffix(): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(toByteArray())
    return digest.take(4).joinToString("") { byte -> "%02x".format(byte) }
}

private data class WeatherForecastArgs(
    val latitude: Double,
    val longitude: Double,
) {
    init {
        require(latitude in -90.0..90.0) { "latitude is out of range" }
        require(longitude in -180.0..180.0) { "longitude is out of range" }
    }
}

private data class DateRange(
    val dateFrom: LocalDate?,
    val dateTo: LocalDate?,
)

private sealed interface GeoPointArgument {
    data object Missing : GeoPointArgument
    data object Invalid : GeoPointArgument
    data class Present(val value: GeoPoint) : GeoPointArgument
}

private fun DateRange.withDefaultFixtureWindow(context: ReadToolExecutionContext): DateRange =
    if (dateFrom == null && dateTo == null) {
        val localDate = context.referenceTime.atZone(ZoneId.of(context.timeZoneId)).toLocalDate()
        DateRange(
            dateFrom = localDate,
            dateTo = localDate.plusDays(DEFAULT_FOOTBALL_FIXTURE_WINDOW_DAYS),
        )
    } else {
        this
    }

private fun DateRange.withDefaultEventWindow(context: ReadToolExecutionContext): DateRange =
    if (dateFrom == null && dateTo == null) {
        val localDate = context.referenceTime.atZone(ZoneId.of(context.timeZoneId)).toLocalDate()
        DateRange(
            dateFrom = localDate,
            dateTo = localDate.plusDays(DEFAULT_EVENT_WINDOW_DAYS),
        )
    } else {
        this
    }

private fun DateRange.hasInvertedDates(): Boolean =
    dateTo != null && dateFrom != null && dateTo.isBefore(dateFrom)

private enum class SourceOutcome {
    SuccessWithResults,
    SuccessEmpty,
    TechnicalFailure,
}

private const val MAX_EVIDENCE_ITEMS = 5
private const val DEFAULT_WEB_MAX_RESULTS = 5
private const val DEFAULT_MOVIE_DISCOVERY_MAX_RESULTS = 6
private const val DEFAULT_FOOTBALL_FIXTURE_WINDOW_DAYS = 10L
private const val DEFAULT_EVENT_WINDOW_DAYS = 30L
private const val DEFAULT_TRAIL_RADIUS_METERS = 25_000
private const val DEFAULT_OUTDOOR_TRAIL_QUERY = "hiking trail mountain"
private const val DEFAULT_LIVE_MUSIC_EVENT_QUERY = "concert live music"
private const val DEFAULT_SPORTS_EVENT_QUERY = "sports"
private const val MAX_FACT_TEXT_CHARS = 700
private const val MAX_WEB_SNIPPET_CHARS = 700
