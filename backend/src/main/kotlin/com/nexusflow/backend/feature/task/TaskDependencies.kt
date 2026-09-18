package com.nexusflow.backend.feature.task

import com.nexusflow.ai.AiTaskCapabilities
import com.nexusflow.ai.AiTaskCapabilityConfig
import com.nexusflow.ai.AiTaskCapabilityProvider
import com.nexusflow.ai.createAiTaskCapabilities
import com.nexusflow.contracts.backendai.answer.ConversationAnsweringCapability
import com.nexusflow.contracts.backendai.answer.StreamingConversationAnsweringCapability
import com.nexusflow.contracts.backendai.conversation.ConversationDecisionCapability
import com.nexusflow.contracts.backendai.planning.PlanComposer
import com.nexusflow.contracts.backendai.planning.PlanExplainer
import com.nexusflow.contracts.backendai.planning.PlanningResearchCapability
import com.nexusflow.contracts.backendai.understanding.UserMessageUnderstanding
import com.nexusflow.backend.core.aicontext.ModelContextAssembler
import com.nexusflow.backend.core.aicontext.ModelContextCatalog
import com.nexusflow.backend.core.config.AiProvider
import com.nexusflow.backend.core.config.ApiFootballRuntimeConfig
import com.nexusflow.backend.core.config.BackendRuntimeConfig
import com.nexusflow.backend.core.config.ChinaOfficialCinemaRuntimeConfig
import com.nexusflow.backend.core.config.ExternalSourcesRuntimeConfig
import com.nexusflow.backend.core.config.FootballDataRuntimeConfig
import com.nexusflow.backend.core.config.MusicBrainzRuntimeConfig
import com.nexusflow.backend.core.config.MetNoRuntimeConfig
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
import com.nexusflow.backend.core.external.InMemorySourceCacheStore
import com.nexusflow.backend.core.external.SourceCacheStore
import com.nexusflow.backend.core.readtool.ReadTool
import com.nexusflow.backend.core.readtool.ReadToolCatalog
import com.nexusflow.backend.core.readtool.ReadToolExecutor
import com.nexusflow.backend.feature.profile.application.ExplicitPreferenceModelContextResolver
import com.nexusflow.backend.feature.profile.domain.ExplicitPreferenceRepository
import com.nexusflow.backend.feature.profile.infrastructure.JdbcExplicitPreferenceRepository
import com.nexusflow.backend.feature.conversation.application.ConversationService
import com.nexusflow.backend.feature.conversation.application.ConversationTurnProcessor
import com.nexusflow.backend.feature.conversation.application.ResponseRunResultConsumer
import com.nexusflow.backend.feature.conversation.application.ResponseRunRealtimeHub
import com.nexusflow.backend.feature.conversation.application.ResponseRunService
import com.nexusflow.backend.feature.conversation.application.ResponseRunWorker
import com.nexusflow.backend.feature.conversation.application.ResponseRunWorkerConfig
import com.nexusflow.backend.feature.conversation.domain.ConversationRepository
import com.nexusflow.backend.feature.conversation.domain.PlanningResponseRunRepository
import com.nexusflow.backend.feature.conversation.infrastructure.JdbcConversationRepository
import com.nexusflow.backend.feature.task.application.ConversationAnswerService
import com.nexusflow.backend.feature.task.application.PlanningService
import com.nexusflow.backend.feature.task.application.TaskService
import com.nexusflow.backend.feature.task.application.source.FootballFixtureAcquirer
import com.nexusflow.backend.feature.task.application.source.GeneralSportsEventAcquirer
import com.nexusflow.backend.feature.task.application.source.LiveMusicEventAcquirer
import com.nexusflow.backend.feature.task.application.source.MovieDiscoveryAcquirer
import com.nexusflow.backend.feature.task.application.source.MovieMetadataAcquirer
import com.nexusflow.backend.feature.task.application.source.MovieShowtimeAcquirer
import com.nexusflow.backend.feature.task.application.source.OutdoorAcquirer
import com.nexusflow.backend.feature.task.application.readtool.MovieDetailsReadTool
import com.nexusflow.backend.feature.task.application.readtool.MovieDiscoveryReadTool
import com.nexusflow.backend.feature.task.application.readtool.MovieShowtimeReadTool
import com.nexusflow.backend.feature.task.application.readtool.MusicEventsReadTool
import com.nexusflow.backend.feature.task.application.readtool.MusicMetadataReadTool
import com.nexusflow.backend.feature.task.application.readtool.OutdoorTrailsReadTool
import com.nexusflow.backend.feature.task.application.readtool.PlacesSearchReadTool
import com.nexusflow.backend.feature.task.application.readtool.RouteEstimateReadTool
import com.nexusflow.backend.feature.task.application.readtool.SportsEventsReadTool
import com.nexusflow.backend.feature.task.application.readtool.SportsFixturesReadTool
import com.nexusflow.backend.feature.task.application.readtool.WeatherForecastReadTool
import com.nexusflow.backend.feature.task.application.readtool.WebSearchReadTool
import com.nexusflow.backend.feature.task.domain.PlanValidator
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
import com.nexusflow.backend.feature.task.domain.TaskRepository
import com.nexusflow.backend.feature.task.infrastructure.JdbcTaskRepository
import com.nexusflow.backend.feature.task.infrastructure.source.apifootball.ApiFootballFixtureSource
import com.nexusflow.backend.feature.task.infrastructure.source.footballdata.FootballDataFixtureSource
import com.nexusflow.backend.feature.task.infrastructure.source.movie.ChinaOfficialCinemaPageShowtimeSource
import com.nexusflow.backend.feature.task.infrastructure.source.musicbrainz.MusicBrainzMetadataSource
import com.nexusflow.backend.feature.task.infrastructure.source.musicbrainz.MusicBrainzLiveMusicEventSource
import com.nexusflow.backend.feature.task.infrastructure.source.omdb.OmdbMovieMetadataSource
import com.nexusflow.backend.feature.task.infrastructure.source.outdoor.MetNoWeatherSource
import com.nexusflow.backend.feature.task.infrastructure.source.outdoor.NominatimPlaceSource
import com.nexusflow.backend.feature.task.infrastructure.source.outdoor.OpenMeteoWeatherSource
import com.nexusflow.backend.feature.task.infrastructure.source.outdoor.OpenRouteServicePlaceSource
import com.nexusflow.backend.feature.task.infrastructure.source.outdoor.OpenRouteServiceRouteSource
import com.nexusflow.backend.feature.task.infrastructure.source.outdoor.OverpassTrailSource
import com.nexusflow.backend.feature.task.infrastructure.source.outdoor.TrailSplitsRouteSource
import com.nexusflow.backend.feature.task.infrastructure.source.outdoor.TrailSplitsTrailSource
import com.nexusflow.backend.feature.task.infrastructure.source.thesportsdb.TheSportsDbEventSource
import com.nexusflow.backend.feature.task.infrastructure.source.ticketmaster.TicketmasterLiveMusicEventSource
import com.nexusflow.backend.feature.task.infrastructure.source.ticketmaster.TicketmasterSportsEventSource
import com.nexusflow.backend.feature.task.infrastructure.source.tmdb.TmdbMovieDiscoverySource
import com.nexusflow.backend.feature.task.infrastructure.source.tmdb.TmdbMovieMetadataSource
import com.nexusflow.backend.feature.task.infrastructure.source.web.TavilyWebDiscoverySource
import com.nexusflow.observability.StructuredLogger
import com.nexusflow.observability.logFields
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

fun Application.configureTaskDependencies() {
    dependencies {
        provide<TaskRepository> {
            JdbcTaskRepository(resolve<HikariDataSource>())
        }
        provide<PlanningResponseRunRepository> {
            JdbcTaskRepository(resolve<HikariDataSource>())
        }
        provide<ConversationRepository> {
            JdbcConversationRepository(resolve<HikariDataSource>())
        }
        provide<ExplicitPreferenceRepository> {
            JdbcExplicitPreferenceRepository(resolve<HikariDataSource>())
        }
        provide<HttpClient> {
            val config = resolve<BackendRuntimeConfig>()
            HttpClient(CIO) {
                config.ai?.let { ai ->
                    install(HttpTimeout) {
                        requestTimeoutMillis = ai.requestTimeout.toMillis()
                    }
                }
                install(ContentNegotiation) {
                    json(
                        Json {
                            ignoreUnknownKeys = true
                            explicitNulls = false
                        },
                    )
                }
            }
        }
        provide<ExternalSourceHttpClient> {
            ExternalSourceHttpClient.create(resolve<BackendRuntimeConfig>().externalSources)
        }
        provide<SourceCacheStore> {
            InMemorySourceCacheStore()
        }
        provide<AiTaskCapabilities?> {
            val config = resolve<BackendRuntimeConfig>()
            val ai = config.ai ?: return@provide null
            createAiTaskCapabilities(
                client = resolve<HttpClient>(),
                config = AiTaskCapabilityConfig(
                    provider = ai.provider.toAiCapabilityProvider(),
                    apiKey = ai.apiKey,
                    model = ai.model,
                    baseUrl = ai.baseUrl,
                    enableThinking = ai.enableThinking ?: false,
                ),
                logger = resolve<StructuredLogger>(),
            )
        }
        provide<UserMessageUnderstanding?> {
            resolve<AiTaskCapabilities?>()?.understanding
        }
        provide<ModelContextCatalog> {
            ModelContextCatalog(
                listOf(
                    ExplicitPreferenceModelContextResolver(resolve<ExplicitPreferenceRepository>()),
                ),
            )
        }
        provide {
            ModelContextAssembler(resolve<ModelContextCatalog>())
        }
        provide<PlanComposer?> {
            resolve<AiTaskCapabilities?>()?.planComposer
        }
        provide<PlanExplainer?> {
            resolve<AiTaskCapabilities?>()?.planExplainer
        }
        provide<PlanningResearchCapability?> {
            resolve<AiTaskCapabilities?>()?.planningResearch
        }
        provide<ConversationDecisionCapability?> {
            resolve<AiTaskCapabilities?>()?.conversationDecision
        }
        provide<ConversationAnsweringCapability?> {
            resolve<AiTaskCapabilities?>()?.conversationAnswering
        }
        provide<StreamingConversationAnsweringCapability?> {
            resolve<AiTaskCapabilities?>()?.conversationAnswering as? StreamingConversationAnsweringCapability
        }
        provide<WebDiscoverySource?> {
            val config = resolve<BackendRuntimeConfig>().externalSources
            val tavily = config.tavily ?: return@provide null
            TavilyWebDiscoverySource(
                http = resolve<ExternalSourceHttpClient>(),
                config = tavily,
                cacheStore = resolve<SourceCacheStore>(),
                logger = resolve<StructuredLogger>(),
            )
        }
        provide<MovieMetadataAcquirer?> {
            val config = resolve<BackendRuntimeConfig>()
            config.externalSources.toMovieMetadataAcquirer(
                externalHttpClient = resolve<ExternalSourceHttpClient>(),
                cacheStore = resolve<SourceCacheStore>(),
                logger = resolve<StructuredLogger>(),
            )
        }
        provide<MovieDiscoveryAcquirer?> {
            val config = resolve<BackendRuntimeConfig>()
            config.externalSources.toMovieDiscoveryAcquirer(
                externalHttpClient = resolve<ExternalSourceHttpClient>(),
                cacheStore = resolve<SourceCacheStore>(),
                webDiscoverySource = resolve<WebDiscoverySource?>(),
                logger = resolve<StructuredLogger>(),
            )
        }
        provide<MovieShowtimeAcquirer?> {
            val config = resolve<BackendRuntimeConfig>()
            config.externalSources.toMovieShowtimeAcquirer(
                externalHttpClient = resolve<ExternalSourceHttpClient>(),
                cacheStore = resolve<SourceCacheStore>(),
                movieMetadataAcquirer = resolve<MovieMetadataAcquirer?>(),
                webDiscoverySource = resolve<WebDiscoverySource?>(),
                logger = resolve<StructuredLogger>(),
            )
        }
        provide<FootballCompetitionRegistry> {
            StaticFootballCompetitionRegistry()
        }
        provide<FootballFixtureAcquirer?> {
            val config = resolve<BackendRuntimeConfig>()
            config.externalSources.toFootballFixtureAcquirer(
                externalHttpClient = resolve<ExternalSourceHttpClient>(),
                cacheStore = resolve<SourceCacheStore>(),
                webDiscoverySource = resolve<WebDiscoverySource?>(),
                logger = resolve<StructuredLogger>(),
            )
        }
        provide<MusicMetadataSource?> {
            val config = resolve<BackendRuntimeConfig>()
            config.externalSources.musicBrainz.toMusicMetadataSource(
                externalHttpClient = resolve<ExternalSourceHttpClient>(),
                userAgent = config.externalSources.userAgent,
                cacheStore = resolve<SourceCacheStore>(),
                logger = resolve<StructuredLogger>(),
            )
        }
        provide<LiveMusicEventAcquirer?> {
            val config = resolve<BackendRuntimeConfig>()
            config.externalSources.toLiveMusicEventAcquirer(
                externalHttpClient = resolve<ExternalSourceHttpClient>(),
                cacheStore = resolve<SourceCacheStore>(),
                webDiscoverySource = resolve<WebDiscoverySource?>(),
                logger = resolve<StructuredLogger>(),
            )
        }
        provide<GeneralSportsEventAcquirer?> {
            val config = resolve<BackendRuntimeConfig>()
            config.externalSources.toGeneralSportsEventAcquirer(
                externalHttpClient = resolve<ExternalSourceHttpClient>(),
                cacheStore = resolve<SourceCacheStore>(),
                webDiscoverySource = resolve<WebDiscoverySource?>(),
                logger = resolve<StructuredLogger>(),
            )
        }
        provide<OutdoorAcquirer?> {
            val config = resolve<BackendRuntimeConfig>()
            config.externalSources.toOutdoorAcquirer(
                externalHttpClient = resolve<ExternalSourceHttpClient>(),
                cacheStore = resolve<SourceCacheStore>(),
                webDiscoverySource = resolve<WebDiscoverySource?>(),
                logger = resolve<StructuredLogger>(),
            )
        }
        provide<ReadToolCatalog> {
            val config = resolve<BackendRuntimeConfig>()
            readToolCatalogForExternalSources(
                config = config,
                externalHttpClient = resolve<ExternalSourceHttpClient>(),
                cacheStore = resolve<SourceCacheStore>(),
                webDiscoverySource = resolve<WebDiscoverySource?>(),
                movieMetadataAcquirer = resolve<MovieMetadataAcquirer?>(),
                movieDiscoveryAcquirer = resolve<MovieDiscoveryAcquirer?>(),
                movieShowtimeAcquirer = resolve<MovieShowtimeAcquirer?>(),
                footballFixtureAcquirer = resolve<FootballFixtureAcquirer?>(),
                footballCompetitionRegistry = resolve<FootballCompetitionRegistry>(),
                musicMetadataSource = resolve<MusicMetadataSource?>(),
                liveMusicEventAcquirer = resolve<LiveMusicEventAcquirer?>(),
                generalSportsEventAcquirer = resolve<GeneralSportsEventAcquirer?>(),
                outdoorAcquirer = resolve<OutdoorAcquirer?>(),
                logger = resolve<StructuredLogger>(),
            )
        }
        provide {
            ReadToolExecutor(resolve<ReadToolCatalog>())
        }
        provide {
                ConversationAnswerService(
                    conversationDecision = resolve<ConversationDecisionCapability?>(),
                    conversationAnswering = resolve<ConversationAnsweringCapability?>(),
                    streamingConversationAnswering = resolve<StreamingConversationAnsweringCapability?>(),
                readToolCatalog = resolve<ReadToolCatalog>(),
                readToolExecutor = resolve<ReadToolExecutor>(),
                logger = resolve<StructuredLogger>(),
            )
        }
        provide {
            PlanValidator()
        }
        provide {
            PlanningService(
                repository = resolve(),
                planValidator = resolve(),
                planningResearch = resolve<PlanningResearchCapability?>(),
                readToolCatalog = resolve<ReadToolCatalog>(),
                readToolExecutor = resolve<ReadToolExecutor>(),
                planComposer = resolve(),
                planExplainer = resolve(),
                modelContextAssembler = resolve(),
                logger = resolve<StructuredLogger>(),
            )
        }
        provide {
            TaskService(
                repository = resolve(),
                planningService = resolve(),
            )
        }
        provide {
            resolve<BackendRuntimeConfig>().responseRun.toWorkerConfig()
        }
        provide {
            ResponseRunRealtimeHub()
        }
        provide {
            ConversationTurnProcessor(
                conversationRepository = resolve<ConversationRepository>(),
                taskRepository = resolve(),
                understanding = resolve(),
                conversationAnswerService = resolve(),
                planningService = resolve(),
                realtimeHub = resolve(),
                logger = resolve<StructuredLogger>(),
            )
        }
        provide {
            ResponseRunResultConsumer(
                repository = resolve<ConversationRepository>(),
                planningResponseRunRepository = resolve(),
            )
        }
        provide {
            ResponseRunWorker(
                repository = resolve<ConversationRepository>(),
                processor = resolve(),
                resultConsumer = resolve(),
                config = resolve<ResponseRunWorkerConfig>(),
                realtimeHub = resolve(),
                logger = resolve<StructuredLogger>(),
            )
        }
        provide {
            ResponseRunService(
                conversationRepository = resolve<ConversationRepository>(),
                taskRepository = resolve(),
                realtimeHub = resolve(),
            )
        }
        provide {
            val config = resolve<BackendRuntimeConfig>()
            ConversationService(
                conversationRepository = resolve(),
                taskRepository = resolve(),
                planningService = resolve(),
                understanding = resolve(),
                conversationAnswerService = resolve(),
                responseRunMaxDuration = config.responseRun.maxDuration,
                logger = resolve<StructuredLogger>(),
            )
        }
    }
}

private fun com.nexusflow.backend.core.config.ResponseRunRuntimeConfig.toWorkerConfig(): ResponseRunWorkerConfig =
    ResponseRunWorkerConfig(
        enabled = workerEnabled,
        pollInterval = pollInterval,
        leaseDuration = leaseDuration,
        heartbeatInterval = heartbeatInterval,
        retryBackoff = retryBackoff,
        maxAttempts = maxAttempts,
    )

private fun AiProvider.toAiCapabilityProvider(): AiTaskCapabilityProvider =
    when (this) {
        AiProvider.OpenAi -> AiTaskCapabilityProvider.OpenAi
        AiProvider.Qwen -> AiTaskCapabilityProvider.Qwen
        AiProvider.DeepSeek -> AiTaskCapabilityProvider.DeepSeek
    }

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
        movieMetadataAcquirer?.let { acquirer ->
            add(MovieDetailsReadTool(acquirer))
        }
        movieDiscoveryAcquirer?.let { acquirer ->
            add(MovieDiscoveryReadTool(acquirer))
        }
        movieShowtimeAcquirer?.let { acquirer ->
            add(MovieShowtimeReadTool(acquirer))
        }
        footballFixtureAcquirer?.let { acquirer ->
            add(SportsFixturesReadTool(acquirer, footballCompetitionRegistry))
        }
        musicMetadataSource?.let { source ->
            add(MusicMetadataReadTool(source))
        }
        liveMusicEventAcquirer?.let { acquirer ->
            add(MusicEventsReadTool(acquirer))
        }
        generalSportsEventAcquirer?.let { acquirer ->
            add(SportsEventsReadTool(acquirer))
        }
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
        webDiscoverySource?.let { source ->
            add(WebSearchReadTool(source))
        }
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

private fun ExternalSourcesRuntimeConfig.toMovieMetadataAcquirer(
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

private fun ExternalSourcesRuntimeConfig.toMovieDiscoveryAcquirer(
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

private fun ExternalSourcesRuntimeConfig.toMovieShowtimeAcquirer(
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

private fun ExternalSourcesRuntimeConfig.toFootballFixtureAcquirer(
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

private fun ExternalSourcesRuntimeConfig.toLiveMusicEventAcquirer(
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

private fun ExternalSourcesRuntimeConfig.toGeneralSportsEventAcquirer(
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

private fun ExternalSourcesRuntimeConfig.toOutdoorAcquirer(
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

private fun MusicBrainzRuntimeConfig.toMusicMetadataSource(
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

private fun externalSourceHttpUnavailable(): Nothing =
    throw IllegalStateException("External source HTTP client is required in external opportunity mode")

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
