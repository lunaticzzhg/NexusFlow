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
import com.nexusflow.backend.feature.research.application.ReadTool
import com.nexusflow.backend.feature.research.application.ReadToolCatalog
import com.nexusflow.backend.feature.research.application.ReadToolExecutor
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
import com.nexusflow.backend.feature.conversation.domain.ConversationAnswerResultCommitter
import com.nexusflow.backend.feature.conversation.domain.ConversationRepository
import com.nexusflow.backend.feature.conversation.domain.ConversationTurnStartCommitter
import com.nexusflow.backend.feature.conversation.infrastructure.JdbcConversationAnswerCommitter
import com.nexusflow.backend.feature.responserun.domain.ResponseRunRepository
import com.nexusflow.backend.feature.responserun.domain.ResponseRunResultStore
import com.nexusflow.backend.feature.responserun.domain.ResponseRunStore
import com.nexusflow.backend.feature.responserun.infrastructure.JdbcResponseRunRepository
import com.nexusflow.backend.feature.conversation.infrastructure.JdbcConversationRepository
import com.nexusflow.backend.feature.conversation.infrastructure.JdbcConversationTurnStartCommitter
import com.nexusflow.backend.feature.conversation.application.answer.ConversationAnswerService
import com.nexusflow.backend.feature.task.application.PlanningService
import com.nexusflow.backend.feature.task.application.TaskService
import com.nexusflow.backend.feature.research.application.source.FootballFixtureAcquirer
import com.nexusflow.backend.feature.research.application.source.GeneralSportsEventAcquirer
import com.nexusflow.backend.feature.research.application.source.LiveMusicEventAcquirer
import com.nexusflow.backend.feature.research.application.source.MovieDiscoveryAcquirer
import com.nexusflow.backend.feature.research.application.source.MovieMetadataAcquirer
import com.nexusflow.backend.feature.research.application.source.MovieShowtimeAcquirer
import com.nexusflow.backend.feature.research.application.source.OutdoorAcquirer
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
import com.nexusflow.backend.feature.task.domain.PlanValidator
import com.nexusflow.backend.feature.task.domain.PlanningResultCommitter
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
import com.nexusflow.backend.feature.research.infrastructure.source.apifootball.ApiFootballFixtureSource
import com.nexusflow.backend.feature.research.infrastructure.source.footballdata.FootballDataFixtureSource
import com.nexusflow.backend.feature.research.infrastructure.source.movie.ChinaOfficialCinemaPageShowtimeSource
import com.nexusflow.backend.feature.research.infrastructure.source.musicbrainz.MusicBrainzMetadataSource
import com.nexusflow.backend.feature.research.infrastructure.source.musicbrainz.MusicBrainzLiveMusicEventSource
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
        provide<PlanningResultCommitter> {
            JdbcTaskRepository(resolve<HikariDataSource>())
        }
        provide<ConversationRepository> {
            JdbcConversationRepository(resolve<HikariDataSource>())
        }
        provide<ConversationTurnStartCommitter> {
            JdbcConversationTurnStartCommitter(resolve<HikariDataSource>())
        }
        provide<ConversationAnswerResultCommitter> {
            JdbcConversationAnswerCommitter(resolve<HikariDataSource>())
        }
        provide<ResponseRunRepository> {
            JdbcResponseRunRepository(resolve<HikariDataSource>())
        }
        provide<ResponseRunStore> {
            JdbcResponseRunRepository(resolve<HikariDataSource>())
        }
        provide<ResponseRunResultStore> {
            JdbcResponseRunRepository(resolve<HikariDataSource>())
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
                conversationAnswerCommitter = resolve(),
                planningResultCommitter = resolve(),
            )
        }
        provide {
            ResponseRunWorker(
                responseRunStore = resolve<ResponseRunStore>(),
                resultStore = resolve<ResponseRunResultStore>(),
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
                responseRunStore = resolve<ResponseRunStore>(),
                taskRepository = resolve(),
                realtimeHub = resolve(),
            )
        }
        provide {
            val config = resolve<BackendRuntimeConfig>()
            ConversationService(
                conversationRepository = resolve(),
                conversationTurnStartCommitter = resolve(),
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
