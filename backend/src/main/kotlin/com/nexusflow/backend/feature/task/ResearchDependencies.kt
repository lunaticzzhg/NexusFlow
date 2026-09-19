package com.nexusflow.backend.feature.task

import com.nexusflow.backend.core.config.ApiFootballRuntimeConfig
import com.nexusflow.backend.core.config.BackendRuntimeConfig
import com.nexusflow.backend.core.config.ChinaOfficialCinemaRuntimeConfig
import com.nexusflow.backend.core.config.ExternalSourcesRuntimeConfig
import com.nexusflow.backend.core.config.FootballDataRuntimeConfig
import com.nexusflow.backend.core.config.MetNoRuntimeConfig
import com.nexusflow.backend.core.config.MusicBrainzRuntimeConfig
import com.nexusflow.backend.core.config.NominatimRuntimeConfig
import com.nexusflow.backend.core.config.OmdbRuntimeConfig
import com.nexusflow.backend.core.config.OpenMeteoRuntimeConfig
import com.nexusflow.backend.core.config.OpenRouteServiceRuntimeConfig
import com.nexusflow.backend.core.config.OverpassRuntimeConfig
import com.nexusflow.backend.core.config.TheSportsDbRuntimeConfig
import com.nexusflow.backend.core.config.TicketmasterRuntimeConfig
import com.nexusflow.backend.core.config.TmdbRuntimeConfig
import com.nexusflow.backend.core.config.TrailSplitsRuntimeConfig
import com.nexusflow.backend.core.external.ExternalSourceHttpClient
import com.nexusflow.backend.core.external.SourceCacheStore
import com.nexusflow.backend.feature.research.application.ReadTool
import com.nexusflow.backend.feature.research.application.ReadToolCatalog
import com.nexusflow.backend.feature.research.application.readtool.MovieDetailsReadTool
import com.nexusflow.backend.feature.research.application.readtool.MovieDiscoveryReadTool
import com.nexusflow.backend.feature.research.application.readtool.MovieShowtimeReadTool
import com.nexusflow.backend.feature.research.application.readtool.MusicEventsReadTool
import com.nexusflow.backend.feature.research.application.readtool.MusicMetadataReadTool
import com.nexusflow.backend.feature.research.application.readtool.OutdoorTrailsReadTool
import com.nexusflow.backend.feature.research.application.readtool.PlacesSearchReadTool
import com.nexusflow.backend.feature.research.application.readtool.RouteEstimateReadTool
import com.nexusflow.backend.feature.research.application.readtool.SportsEventsReadTool
import com.nexusflow.backend.feature.research.application.readtool.SportsFixturesReadTool
import com.nexusflow.backend.feature.research.application.readtool.WeatherForecastReadTool
import com.nexusflow.backend.feature.research.application.readtool.WebSearchReadTool
import com.nexusflow.backend.feature.research.application.source.FootballFixtureAcquirer
import com.nexusflow.backend.feature.research.application.source.GeneralSportsEventAcquirer
import com.nexusflow.backend.feature.research.application.source.LiveMusicEventAcquirer
import com.nexusflow.backend.feature.research.application.source.MovieDiscoveryAcquirer
import com.nexusflow.backend.feature.research.application.source.MovieMetadataAcquirer
import com.nexusflow.backend.feature.research.application.source.MovieShowtimeAcquirer
import com.nexusflow.backend.feature.research.application.source.OutdoorAcquirer
import com.nexusflow.backend.feature.research.infrastructure.source.apifootball.ApiFootballFixtureSource
import com.nexusflow.backend.feature.research.infrastructure.source.footballdata.FootballDataFixtureSource
import com.nexusflow.backend.feature.research.infrastructure.source.movie.ChinaOfficialCinemaPageShowtimeSource
import com.nexusflow.backend.feature.research.infrastructure.source.musicbrainz.MusicBrainzLiveMusicEventSource
import com.nexusflow.backend.feature.research.infrastructure.source.musicbrainz.MusicBrainzMetadataSource
import com.nexusflow.backend.feature.research.infrastructure.source.omdb.OmdbMovieMetadataSource
import com.nexusflow.backend.feature.research.infrastructure.source.outdoor.MetNoWeatherSource
import com.nexusflow.backend.feature.research.infrastructure.source.outdoor.NominatimPlaceSource
import com.nexusflow.backend.feature.research.infrastructure.source.outdoor.OpenMeteoWeatherSource
import com.nexusflow.backend.feature.research.infrastructure.source.outdoor.OpenRouteServicePlaceSource
import com.nexusflow.backend.feature.research.infrastructure.source.outdoor.OpenRouteServiceRouteSource
import com.nexusflow.backend.feature.research.infrastructure.source.outdoor.OverpassTrailSource
import com.nexusflow.backend.feature.research.infrastructure.source.outdoor.TrailSplitsRouteSource
import com.nexusflow.backend.feature.research.infrastructure.source.outdoor.TrailSplitsTrailSource
import com.nexusflow.backend.feature.research.infrastructure.source.thesportsdb.TheSportsDbEventSource
import com.nexusflow.backend.feature.research.infrastructure.source.ticketmaster.TicketmasterLiveMusicEventSource
import com.nexusflow.backend.feature.research.infrastructure.source.ticketmaster.TicketmasterSportsEventSource
import com.nexusflow.backend.feature.research.infrastructure.source.tmdb.TmdbMovieDiscoverySource
import com.nexusflow.backend.feature.research.infrastructure.source.tmdb.TmdbMovieMetadataSource
import com.nexusflow.backend.feature.research.infrastructure.source.web.TavilyWebDiscoverySource
import com.nexusflow.backend.feature.task.domain.source.FootballCompetitionRegistry
import com.nexusflow.backend.feature.task.domain.source.FootballFixtureSource
import com.nexusflow.backend.feature.task.domain.source.GeneralSportsEventSource
import com.nexusflow.backend.feature.task.domain.source.LiveMusicEventSource
import com.nexusflow.backend.feature.task.domain.source.MovieDiscoverySource
import com.nexusflow.backend.feature.task.domain.source.MovieMetadataSource
import com.nexusflow.backend.feature.task.domain.source.MovieShowtimeSource
import com.nexusflow.backend.feature.task.domain.source.MusicMetadataSource
import com.nexusflow.backend.feature.task.domain.source.PlaceLookupSource
import com.nexusflow.backend.feature.task.domain.source.RouteSource
import com.nexusflow.backend.feature.task.domain.source.StaticFootballCompetitionRegistry
import com.nexusflow.backend.feature.task.domain.source.TrailDiscoverySource
import com.nexusflow.backend.feature.task.domain.source.WeatherSource
import com.nexusflow.backend.feature.task.domain.source.WebDiscoverySource
import com.nexusflow.observability.StructuredLogger
import com.nexusflow.observability.logFields

internal fun readToolCatalogForExternalSources(
    config: BackendRuntimeConfig,
    externalHttpClient: ExternalSourceHttpClient?,
    cacheStore: SourceCacheStore?,
    webDiscoverySource: WebDiscoverySource?,
    movieMetadataAcquirer: MovieMetadataAcquirer? = config.externalSources.toMovieMetadataAcquirer(
        externalHttpClient,
        cacheStore,
        logger = null,
    ),
    movieDiscoveryAcquirer: MovieDiscoveryAcquirer? = config.externalSources.toMovieDiscoveryAcquirer(
        externalHttpClient = externalHttpClient,
        cacheStore = cacheStore,
        webDiscoverySource = webDiscoverySource,
        logger = null,
    ),
    movieShowtimeAcquirer: MovieShowtimeAcquirer? = config.externalSources.toMovieShowtimeAcquirer(
        externalHttpClient = externalHttpClient,
        cacheStore = cacheStore,
        movieMetadataAcquirer = movieMetadataAcquirer,
        webDiscoverySource = webDiscoverySource,
        logger = null,
    ),
    footballFixtureAcquirer: FootballFixtureAcquirer? = config.externalSources.toFootballFixtureAcquirer(
        externalHttpClient = externalHttpClient,
        cacheStore = cacheStore,
        webDiscoverySource = webDiscoverySource,
        logger = null,
    ),
    footballCompetitionRegistry: FootballCompetitionRegistry = StaticFootballCompetitionRegistry(),
    musicMetadataSource: MusicMetadataSource? = config.externalSources.musicBrainz.toMusicMetadataSource(
        externalHttpClient = externalHttpClient,
        userAgent = config.externalSources.userAgent,
        cacheStore = cacheStore,
        logger = null,
    ),
    liveMusicEventAcquirer: LiveMusicEventAcquirer? = config.externalSources.toLiveMusicEventAcquirer(
        externalHttpClient = externalHttpClient,
        cacheStore = cacheStore,
        webDiscoverySource = webDiscoverySource,
        logger = null,
    ),
    generalSportsEventAcquirer: GeneralSportsEventAcquirer? = config.externalSources.toGeneralSportsEventAcquirer(
        externalHttpClient = externalHttpClient,
        cacheStore = cacheStore,
        webDiscoverySource = webDiscoverySource,
        logger = null,
    ),
    outdoorAcquirer: OutdoorAcquirer? = config.externalSources.toOutdoorAcquirer(
        externalHttpClient = externalHttpClient,
        cacheStore = cacheStore,
        webDiscoverySource = webDiscoverySource,
        logger = null,
    ),
    logger: StructuredLogger?,
): ReadToolCatalog {
    val tools = buildList<ReadTool> {
        add(
            WeatherForecastReadTool(
                primary = config.externalSources.metNo.toWeatherSource(
                    externalHttpClient,
                    config.externalSources.userAgent,
                    cacheStore,
                    logger,
                ),
                secondary = config.externalSources.openMeteo.toWeatherSource(externalHttpClient, cacheStore, logger),
            ),
        )
        movieMetadataAcquirer?.let { add(MovieDetailsReadTool(it)) }
        movieDiscoveryAcquirer?.let { add(MovieDiscoveryReadTool(it)) }
        movieShowtimeAcquirer?.let { add(MovieShowtimeReadTool(it)) }
        footballFixtureAcquirer?.let { add(SportsFixturesReadTool(it, footballCompetitionRegistry)) }
        musicMetadataSource?.let { add(MusicMetadataReadTool(it)) }
        liveMusicEventAcquirer?.let { add(MusicEventsReadTool(it)) }
        generalSportsEventAcquirer?.let { add(SportsEventsReadTool(it)) }
        outdoorAcquirer?.let { acquirer ->
            add(OutdoorTrailsReadTool(acquirer))
            add(
                PlacesSearchReadTool(
                    primary = config.externalSources.openRouteService?.toPlaceLookupSource(
                        externalHttpClient,
                        cacheStore,
                        logger,
                    ),
                    secondary = config.externalSources.nominatim.toPlaceLookupSource(
                        externalHttpClient,
                        cacheStore,
                        logger,
                    ),
                ),
            )
            add(
                RouteEstimateReadTool(
                    primary = config.externalSources.openRouteService?.toRouteSource(externalHttpClient, cacheStore, logger),
                    secondary = config.externalSources.trailSplits.toRouteSource(externalHttpClient, cacheStore, logger),
                ),
            )
        }
        webDiscoverySource?.let { add(WebSearchReadTool(it)) }
    }
    val catalog = ReadToolCatalog(tools)
    val definitions = catalog.definitions()
    logger?.info(
        component = "read_tool",
        event = "read_tool_catalog_initialized",
        fields = logFields {
            "tool_count" value definitions.size
            "available_tool_keys" value definitions.joinToString(",") { definition -> definition.key.value }
        },
    )
    return catalog
}

internal fun ExternalSourcesRuntimeConfig.toMovieMetadataAcquirer(
    externalHttpClient: ExternalSourceHttpClient?,
    cacheStore: SourceCacheStore?,
    logger: StructuredLogger?,
): MovieMetadataAcquirer? =
    if (tmdb == null && omdb == null) {
        null
    } else {
        MovieMetadataAcquirer(
            primary = tmdb?.toMovieMetadataSource(externalHttpClient, cacheStore, logger),
            secondary = omdb?.toMovieMetadataSource(externalHttpClient, cacheStore, logger),
        )
    }

internal fun ExternalSourcesRuntimeConfig.toMovieDiscoveryAcquirer(
    externalHttpClient: ExternalSourceHttpClient?,
    cacheStore: SourceCacheStore?,
    webDiscoverySource: WebDiscoverySource?,
    logger: StructuredLogger?,
): MovieDiscoveryAcquirer? =
    tmdb?.let { tmdbConfig ->
        MovieDiscoveryAcquirer(
            primary = tmdbConfig.toMovieDiscoverySource(externalHttpClient, cacheStore, logger),
            webDiscoverySource = webDiscoverySource,
        )
    }

internal fun ExternalSourcesRuntimeConfig.toMovieShowtimeAcquirer(
    externalHttpClient: ExternalSourceHttpClient?,
    cacheStore: SourceCacheStore?,
    movieMetadataAcquirer: MovieMetadataAcquirer?,
    webDiscoverySource: WebDiscoverySource?,
    logger: StructuredLogger?,
): MovieShowtimeAcquirer? {
    val primary = chinaOfficialCinema?.toMovieShowtimeSource(externalHttpClient, cacheStore, logger) ?: return null
    return MovieShowtimeAcquirer(
        metadataAcquirer = movieMetadataAcquirer,
        primary = primary,
        webDiscoverySource = webDiscoverySource,
    )
}

internal fun ExternalSourcesRuntimeConfig.toFootballFixtureAcquirer(
    externalHttpClient: ExternalSourceHttpClient?,
    cacheStore: SourceCacheStore?,
    webDiscoverySource: WebDiscoverySource?,
    logger: StructuredLogger?,
): FootballFixtureAcquirer? {
    if (footballData == null && apiFootball == null) return null
    return FootballFixtureAcquirer(
        primary = footballData?.toFootballFixtureSource(externalHttpClient, cacheStore, logger),
        secondary = apiFootball?.toFootballFixtureSource(externalHttpClient, cacheStore, logger),
        webDiscoverySource = webDiscoverySource,
    )
}

internal fun ExternalSourcesRuntimeConfig.toLiveMusicEventAcquirer(
    externalHttpClient: ExternalSourceHttpClient?,
    cacheStore: SourceCacheStore?,
    webDiscoverySource: WebDiscoverySource?,
    logger: StructuredLogger?,
): LiveMusicEventAcquirer =
    LiveMusicEventAcquirer(
        primary = ticketmaster?.toLiveMusicEventSource(externalHttpClient, cacheStore, logger),
        secondary = musicBrainz.toLiveMusicEventSource(externalHttpClient, userAgent, cacheStore, logger),
        webDiscoverySource = webDiscoverySource,
    )

internal fun ExternalSourcesRuntimeConfig.toGeneralSportsEventAcquirer(
    externalHttpClient: ExternalSourceHttpClient?,
    cacheStore: SourceCacheStore?,
    webDiscoverySource: WebDiscoverySource?,
    logger: StructuredLogger?,
): GeneralSportsEventAcquirer =
    GeneralSportsEventAcquirer(
        primary = ticketmaster?.toSportsEventSource(externalHttpClient, cacheStore, logger),
        secondary = theSportsDb.toGeneralSportsEventSource(externalHttpClient, cacheStore, logger),
        webDiscoverySource = webDiscoverySource,
    )

internal fun ExternalSourcesRuntimeConfig.toOutdoorAcquirer(
    externalHttpClient: ExternalSourceHttpClient?,
    cacheStore: SourceCacheStore?,
    webDiscoverySource: WebDiscoverySource?,
    logger: StructuredLogger?,
): OutdoorAcquirer =
    OutdoorAcquirer(
        trailPrimary = overpass.toTrailDiscoverySource(externalHttpClient, cacheStore, logger),
        trailSecondary = trailSplits.toTrailDiscoverySource(externalHttpClient, cacheStore, logger),
        placePrimary = openRouteService?.toPlaceLookupSource(externalHttpClient, cacheStore, logger),
        placeSecondary = nominatim.toPlaceLookupSource(externalHttpClient, cacheStore, logger),
        routePrimary = openRouteService?.toRouteSource(externalHttpClient, cacheStore, logger),
        routeSecondary = trailSplits.toRouteSource(externalHttpClient, cacheStore, logger),
        weatherPrimary = metNo.toWeatherSource(externalHttpClient, userAgent, cacheStore, logger),
        weatherSecondary = openMeteo.toWeatherSource(externalHttpClient, cacheStore, logger),
        webDiscoverySource = webDiscoverySource,
    )

private fun TmdbRuntimeConfig.toMovieMetadataSource(
    externalHttpClient: ExternalSourceHttpClient?,
    cacheStore: SourceCacheStore?,
    logger: StructuredLogger?,
): MovieMetadataSource =
    TmdbMovieMetadataSource(
        http = externalHttpClient ?: externalSourceHttpUnavailable(),
        config = this,
        cacheStore = cacheStore,
        logger = logger,
    )

private fun TmdbRuntimeConfig.toMovieDiscoverySource(
    externalHttpClient: ExternalSourceHttpClient?,
    cacheStore: SourceCacheStore?,
    logger: StructuredLogger?,
): MovieDiscoverySource =
    TmdbMovieDiscoverySource(
        http = externalHttpClient ?: externalSourceHttpUnavailable(),
        config = this,
        cacheStore = cacheStore,
        logger = logger,
    )

private fun OmdbRuntimeConfig.toMovieMetadataSource(
    externalHttpClient: ExternalSourceHttpClient?,
    cacheStore: SourceCacheStore?,
    logger: StructuredLogger?,
): MovieMetadataSource =
    OmdbMovieMetadataSource(
        http = externalHttpClient ?: externalSourceHttpUnavailable(),
        config = this,
        cacheStore = cacheStore,
        logger = logger,
    )

private fun ChinaOfficialCinemaRuntimeConfig.toMovieShowtimeSource(
    externalHttpClient: ExternalSourceHttpClient?,
    cacheStore: SourceCacheStore?,
    logger: StructuredLogger?,
): MovieShowtimeSource =
    ChinaOfficialCinemaPageShowtimeSource(
        http = externalHttpClient ?: externalSourceHttpUnavailable(),
        config = this,
        cacheStore = cacheStore,
        logger = logger,
    )

private fun ApiFootballRuntimeConfig.toFootballFixtureSource(
    externalHttpClient: ExternalSourceHttpClient?,
    cacheStore: SourceCacheStore?,
    logger: StructuredLogger?,
): FootballFixtureSource =
    ApiFootballFixtureSource(
        http = externalHttpClient ?: externalSourceHttpUnavailable(),
        config = this,
        cacheStore = cacheStore,
        logger = logger,
    )

private fun FootballDataRuntimeConfig.toFootballFixtureSource(
    externalHttpClient: ExternalSourceHttpClient?,
    cacheStore: SourceCacheStore?,
    logger: StructuredLogger?,
): FootballFixtureSource =
    FootballDataFixtureSource(
        http = externalHttpClient ?: externalSourceHttpUnavailable(),
        config = this,
        cacheStore = cacheStore,
        logger = logger,
    )

private fun TicketmasterRuntimeConfig.toLiveMusicEventSource(
    externalHttpClient: ExternalSourceHttpClient?,
    cacheStore: SourceCacheStore?,
    logger: StructuredLogger?,
): LiveMusicEventSource =
    TicketmasterLiveMusicEventSource(
        http = externalHttpClient ?: externalSourceHttpUnavailable(),
        config = this,
        cacheStore = cacheStore,
        logger = logger,
    )

private fun MusicBrainzRuntimeConfig.toLiveMusicEventSource(
    externalHttpClient: ExternalSourceHttpClient?,
    userAgent: String,
    cacheStore: SourceCacheStore?,
    logger: StructuredLogger?,
): LiveMusicEventSource =
    MusicBrainzLiveMusicEventSource(
        http = externalHttpClient ?: externalSourceHttpUnavailable(),
        config = this,
        userAgent = userAgent,
        cacheStore = cacheStore,
        logger = logger,
    )

internal fun MusicBrainzRuntimeConfig.toMusicMetadataSource(
    externalHttpClient: ExternalSourceHttpClient?,
    userAgent: String,
    cacheStore: SourceCacheStore?,
    logger: StructuredLogger?,
): MusicMetadataSource =
    MusicBrainzMetadataSource(
        http = externalHttpClient ?: externalSourceHttpUnavailable(),
        config = this,
        userAgent = userAgent,
        cacheStore = cacheStore,
        logger = logger,
    )

private fun TheSportsDbRuntimeConfig.toGeneralSportsEventSource(
    externalHttpClient: ExternalSourceHttpClient?,
    cacheStore: SourceCacheStore?,
    logger: StructuredLogger?,
): GeneralSportsEventSource =
    TheSportsDbEventSource(
        http = externalHttpClient ?: externalSourceHttpUnavailable(),
        config = this,
        cacheStore = cacheStore,
        logger = logger,
    )

private fun TicketmasterRuntimeConfig.toSportsEventSource(
    externalHttpClient: ExternalSourceHttpClient?,
    cacheStore: SourceCacheStore?,
    logger: StructuredLogger?,
): GeneralSportsEventSource =
    TicketmasterSportsEventSource(
        http = externalHttpClient ?: externalSourceHttpUnavailable(),
        config = this,
        cacheStore = cacheStore,
        logger = logger,
    )

private fun OverpassRuntimeConfig.toTrailDiscoverySource(
    externalHttpClient: ExternalSourceHttpClient?,
    cacheStore: SourceCacheStore?,
    logger: StructuredLogger?,
): TrailDiscoverySource =
    OverpassTrailSource(
        http = externalHttpClient ?: externalSourceHttpUnavailable(),
        config = this,
        cacheStore = cacheStore,
        logger = logger,
    )

private fun TrailSplitsRuntimeConfig.toTrailDiscoverySource(
    externalHttpClient: ExternalSourceHttpClient?,
    cacheStore: SourceCacheStore?,
    logger: StructuredLogger?,
): TrailDiscoverySource =
    TrailSplitsTrailSource(
        http = externalHttpClient ?: externalSourceHttpUnavailable(),
        config = this,
        cacheStore = cacheStore,
        logger = logger,
    )

private fun OpenRouteServiceRuntimeConfig.toPlaceLookupSource(
    externalHttpClient: ExternalSourceHttpClient?,
    cacheStore: SourceCacheStore?,
    logger: StructuredLogger?,
): PlaceLookupSource =
    OpenRouteServicePlaceSource(
        http = externalHttpClient ?: externalSourceHttpUnavailable(),
        config = this,
        cacheStore = cacheStore,
        logger = logger,
    )

private fun NominatimRuntimeConfig.toPlaceLookupSource(
    externalHttpClient: ExternalSourceHttpClient?,
    cacheStore: SourceCacheStore?,
    logger: StructuredLogger?,
): PlaceLookupSource =
    NominatimPlaceSource(
        http = externalHttpClient ?: externalSourceHttpUnavailable(),
        config = this,
        cacheStore = cacheStore,
        logger = logger,
    )

private fun OpenRouteServiceRuntimeConfig.toRouteSource(
    externalHttpClient: ExternalSourceHttpClient?,
    cacheStore: SourceCacheStore?,
    logger: StructuredLogger?,
): RouteSource =
    OpenRouteServiceRouteSource(
        http = externalHttpClient ?: externalSourceHttpUnavailable(),
        config = this,
        cacheStore = cacheStore,
        logger = logger,
    )

private fun TrailSplitsRuntimeConfig.toRouteSource(
    externalHttpClient: ExternalSourceHttpClient?,
    cacheStore: SourceCacheStore?,
    logger: StructuredLogger?,
): RouteSource =
    TrailSplitsRouteSource(
        http = externalHttpClient ?: externalSourceHttpUnavailable(),
        config = this,
        cacheStore = cacheStore,
        logger = logger,
    )

private fun OpenMeteoRuntimeConfig.toWeatherSource(
    externalHttpClient: ExternalSourceHttpClient?,
    cacheStore: SourceCacheStore?,
    logger: StructuredLogger?,
): WeatherSource =
    OpenMeteoWeatherSource(
        http = externalHttpClient ?: externalSourceHttpUnavailable(),
        config = this,
        cacheStore = cacheStore,
        logger = logger,
    )

private fun MetNoRuntimeConfig.toWeatherSource(
    externalHttpClient: ExternalSourceHttpClient?,
    userAgent: String,
    cacheStore: SourceCacheStore?,
    logger: StructuredLogger?,
): WeatherSource =
    MetNoWeatherSource(
        http = externalHttpClient ?: externalSourceHttpUnavailable(),
        config = this,
        userAgent = userAgent,
        cacheStore = cacheStore,
        logger = logger,
    )

private fun externalSourceHttpUnavailable(): Nothing =
    throw IllegalStateException("External source HTTP client is required in external opportunity mode")
