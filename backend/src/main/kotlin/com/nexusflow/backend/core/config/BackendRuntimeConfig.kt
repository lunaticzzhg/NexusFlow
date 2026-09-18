package com.nexusflow.backend.core.config

import com.nexusflow.observability.LogLevel
import com.nexusflow.observability.toLogLevelOrNull
import java.time.Duration

data class BackendRuntimeConfig(
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val jwtIssuer: String,
    val jwtAudience: String,
    val jwtKeyId: String,
    val jwtPrivateKeyPemBase64: String,
    val jwtPublicKeyPemBase64: String,
    val googleAllowedAudiences: Set<String>,
    val accessLifetime: Duration,
    val refreshLifetime: Duration,
    val ai: AiRuntimeConfig?,
    val responseRun: ResponseRunRuntimeConfig,
    val externalSources: ExternalSourcesRuntimeConfig,
    val logging: LoggingRuntimeConfig,
    val devLoginEnabled: Boolean,
    val devLoginEmail: String?,
    val devLoginPassword: String?,
) {
    companion object {
        fun fromEnvironment(environment: Map<String, String> = System.getenv()): BackendRuntimeConfig = BackendRuntimeConfig(
            databaseUrl = required(environment, "DATABASE_URL"),
            databaseUser = required(environment, "DATABASE_USER"),
            databasePassword = required(environment, "DATABASE_PASSWORD"),
            jwtIssuer = required(environment, "AUTH_JWT_ISSUER"),
            jwtAudience = required(environment, "AUTH_JWT_AUDIENCE"),
            jwtKeyId = required(environment, "AUTH_JWT_KEY_ID"),
            jwtPrivateKeyPemBase64 = required(environment, "AUTH_JWT_PRIVATE_KEY_PEM_BASE64"),
            jwtPublicKeyPemBase64 = required(environment, "AUTH_JWT_PUBLIC_KEY_PEM_BASE64"),
            googleAllowedAudiences = required(environment, "GOOGLE_ALLOWED_AUDIENCES")
                .split(',')
                .map(String::trim)
                .filter(String::isNotBlank)
                .toSet(),
            accessLifetime = Duration.ofSeconds(required(environment, "AUTH_ACCESS_TTL_SECONDS").toLong()),
            refreshLifetime = Duration.ofDays(required(environment, "AUTH_REFRESH_TTL_DAYS").toLong()),
            ai = aiRuntimeConfig(environment),
            responseRun = responseRunRuntimeConfig(environment),
            externalSources = externalSourcesRuntimeConfig(environment),
            logging = loggingRuntimeConfig(environment),
            devLoginEnabled = environment["ORBIT_DEV_LOGIN_ENABLED"]?.toBooleanStrictOrNull() ?: false,
            devLoginEmail = environment["ORBIT_DEV_LOGIN_EMAIL"]?.takeIf(String::isNotBlank),
            devLoginPassword = environment["ORBIT_DEV_LOGIN_PASSWORD"]?.takeIf(String::isNotBlank),
        )

        private fun required(environment: Map<String, String>, name: String): String = environment[name]
            ?.takeIf(String::isNotBlank)
            ?: error("$name must be configured")

        private fun aiRuntimeConfig(environment: Map<String, String>): AiRuntimeConfig? {
            val providerText = environment["AI_PROVIDER"]?.trim()?.takeIf(String::isNotBlank)
                ?: return null
            val provider = providerText.toAiProvider()
            return AiRuntimeConfig(
                provider = provider,
                apiKey = required(environment, "AI_API_KEY"),
                baseUrl = required(environment, "AI_BASE_URL"),
                model = required(environment, "AI_MODEL"),
                requestTimeout = environment["AI_REQUEST_TIMEOUT_MS"]
                    ?.takeIf(String::isNotBlank)
                    ?.toLong()
                    ?.also { require(it > 0) { "AI_REQUEST_TIMEOUT_MS must be positive" } }
                    ?.let(Duration::ofMillis)
                    ?: Duration.ofSeconds(90),
                enableThinking = aiEnableThinking(provider, environment),
            )
        }

        private fun aiEnableThinking(
            provider: AiProvider,
            environment: Map<String, String>,
        ): Boolean? =
            environment["AI_ENABLE_THINKING"]
                ?.takeIf(String::isNotBlank)
                ?.let { value ->
                    value.toBooleanStrictOrNull()
                        ?: error("AI_ENABLE_THINKING must be true or false")
                }
                ?: when (provider) {
                    AiProvider.Qwen -> false
                    AiProvider.OpenAi,
                    AiProvider.DeepSeek,
                    -> null
                }

        private fun externalSourcesRuntimeConfig(environment: Map<String, String>): ExternalSourcesRuntimeConfig =
            ExternalSourcesRuntimeConfig(
                mode = environment["ORBIT_OPPORTUNITY_SOURCE_MODE"]
                    ?.takeIf(String::isNotBlank)
                    ?.toOpportunitySourceMode()
                    ?: OpportunitySourceMode.External,
                requestTimeout = environment["EXTERNAL_SOURCE_REQUEST_TIMEOUT_MS"]
                    ?.takeIf(String::isNotBlank)
                    ?.toLong()
                    ?.also { require(it > 0) { "EXTERNAL_SOURCE_REQUEST_TIMEOUT_MS must be positive" } }
                    ?.let(Duration::ofMillis)
                    ?: Duration.ofSeconds(8),
                userAgent = environment["EXTERNAL_SOURCE_USER_AGENT"]?.takeIf(String::isNotBlank)
                    ?: "NexusFlow/0.1",
                tavily = environment["TAVILY_API_KEY"]
                    ?.takeIf(String::isNotBlank)
                    ?.let(::TavilyRuntimeConfig),
                tmdb = environment["TMDB_API_READ_TOKEN"]
                    ?.takeIf(String::isNotBlank)
                    ?.let(::TmdbRuntimeConfig),
                omdb = environment["OMDB_API_KEY"]
                    ?.takeIf(String::isNotBlank)
                    ?.let(::OmdbRuntimeConfig),
                apiFootball = environment["API_FOOTBALL_KEY"]
                    ?.takeIf(String::isNotBlank)
                    ?.let(::ApiFootballRuntimeConfig),
                footballData = environment["FOOTBALL_DATA_API_TOKEN"]
                    ?.takeIf(String::isNotBlank)
                    ?.let(::FootballDataRuntimeConfig),
                ticketmaster = environment["TICKETMASTER_API_KEY"]
                    ?.takeIf(String::isNotBlank)
                    ?.let(::TicketmasterRuntimeConfig),
                musicBrainz = MusicBrainzRuntimeConfig(
                    baseUrl = environment["MUSICBRAINZ_BASE_URL"]?.takeIf(String::isNotBlank)
                        ?: MusicBrainzRuntimeConfig().baseUrl,
                ),
                theSportsDb = TheSportsDbRuntimeConfig(
                    apiKey = environment["THESPORTSDB_API_KEY"]?.takeIf(String::isNotBlank)
                        ?: TheSportsDbRuntimeConfig().apiKey,
                    baseUrl = environment["THESPORTSDB_BASE_URL"]?.takeIf(String::isNotBlank)
                        ?: TheSportsDbRuntimeConfig().baseUrl,
                ),
                openRouteService = environment["ORS_API_KEY"]
                    ?.takeIf(String::isNotBlank)
                    ?.let { apiKey ->
                        OpenRouteServiceRuntimeConfig(
                            apiKey = apiKey,
                            routingBaseUrl = environment["ORS_ROUTING_BASE_URL"]?.takeIf(String::isNotBlank)
                                ?: OpenRouteServiceRuntimeConfig(apiKey).routingBaseUrl,
                            geocodeBaseUrl = environment["ORS_GEOCODE_BASE_URL"]?.takeIf(String::isNotBlank)
                                ?: OpenRouteServiceRuntimeConfig(apiKey).geocodeBaseUrl,
                        )
                    },
                overpass = OverpassRuntimeConfig(
                    baseUrl = environment["OVERPASS_BASE_URL"]?.takeIf(String::isNotBlank)
                        ?: OverpassRuntimeConfig().baseUrl,
                ),
                trailSplits = TrailSplitsRuntimeConfig(
                    baseUrl = environment["TRAILSPLITS_BASE_URL"]?.takeIf(String::isNotBlank)
                        ?: TrailSplitsRuntimeConfig().baseUrl,
                ),
                nominatim = NominatimRuntimeConfig(
                    baseUrl = environment["NOMINATIM_BASE_URL"]?.takeIf(String::isNotBlank)
                        ?: NominatimRuntimeConfig().baseUrl,
                ),
                openMeteo = OpenMeteoRuntimeConfig(
                    baseUrl = environment["OPEN_METEO_BASE_URL"]?.takeIf(String::isNotBlank)
                        ?: OpenMeteoRuntimeConfig().baseUrl,
                ),
                metNo = MetNoRuntimeConfig(
                    baseUrl = environment["MET_NO_BASE_URL"]?.takeIf(String::isNotBlank)
                        ?: MetNoRuntimeConfig().baseUrl,
                ),
                chinaOfficialCinema = environment["CHINA_OFFICIAL_CINEMA_PAGE_URLS"]
                    ?.split(',')
                    ?.map(String::trim)
                    ?.filter(String::isNotBlank)
                    ?.takeIf(List<String>::isNotEmpty)
                    ?.let(::ChinaOfficialCinemaRuntimeConfig),
            )

        private fun responseRunRuntimeConfig(environment: Map<String, String>): ResponseRunRuntimeConfig =
            ResponseRunRuntimeConfig(
                workerEnabled = environment["RESPONSE_RUN_WORKER_ENABLED"]?.toBooleanStrictOrNull() ?: false,
                pollInterval = positiveMillis(environment, "RESPONSE_RUN_POLL_INTERVAL_MS", Duration.ofSeconds(1)),
                leaseDuration = positiveMillis(environment, "RESPONSE_RUN_LEASE_DURATION_MS", Duration.ofSeconds(30)),
                heartbeatInterval = positiveMillis(environment, "RESPONSE_RUN_HEARTBEAT_INTERVAL_MS", Duration.ofSeconds(10)),
                retryBackoff = nonNegativeMillis(environment, "RESPONSE_RUN_RETRY_BACKOFF_MS", Duration.ofSeconds(5)),
                maxAttempts = environment["RESPONSE_RUN_MAX_ATTEMPTS"]
                    ?.takeIf(String::isNotBlank)
                    ?.toInt()
                    ?.also { require(it > 0) { "RESPONSE_RUN_MAX_ATTEMPTS must be positive" } }
                    ?: 3,
                maxDuration = positiveMillis(environment, "RESPONSE_RUN_MAX_DURATION_MS", Duration.ofMinutes(30)),
            )

        private fun positiveMillis(
            environment: Map<String, String>,
            name: String,
            default: Duration,
        ): Duration =
            environment[name]
                ?.takeIf(String::isNotBlank)
                ?.toLong()
                ?.also { require(it > 0) { "$name must be positive" } }
                ?.let(Duration::ofMillis)
                ?: default

        private fun nonNegativeMillis(
            environment: Map<String, String>,
            name: String,
            default: Duration,
        ): Duration =
            environment[name]
                ?.takeIf(String::isNotBlank)
                ?.toLong()
                ?.also { require(it >= 0) { "$name must not be negative" } }
                ?.let(Duration::ofMillis)
                ?: default

        private fun String.toOpportunitySourceMode(): OpportunitySourceMode =
            when (trim().lowercase()) {
                "external" -> OpportunitySourceMode.External
                else -> error("ORBIT_OPPORTUNITY_SOURCE_MODE must be external")
            }

        private fun String.toAiProvider(): AiProvider =
            when (lowercase()) {
                "openai" -> AiProvider.OpenAi
                "qwen" -> AiProvider.Qwen
                "deepseek" -> AiProvider.DeepSeek
                else -> error("AI_PROVIDER must be one of openai, qwen, deepseek")
            }

        private fun loggingRuntimeConfig(environment: Map<String, String>): LoggingRuntimeConfig =
            LoggingRuntimeConfig(
                environment = environment["APP_ENV"]?.toRuntimeEnvironment() ?: RuntimeEnvironment.Local,
                level = environment["LOG_LEVEL"]?.toLogLevelOrNull() ?: LogLevel.INFO,
                format = environment["LOG_FORMAT"]?.toLogFormat() ?: LogFormat.Pretty,
                serviceName = environment["SERVICE_NAME"]?.takeIf(String::isNotBlank) ?: "nexusflow-backend",
            )

        private fun String.toRuntimeEnvironment(): RuntimeEnvironment =
            when (trim().lowercase()) {
                "local" -> RuntimeEnvironment.Local
                "staging" -> RuntimeEnvironment.Staging
                "prod", "production" -> RuntimeEnvironment.Prod
                else -> error("APP_ENV must be one of local, staging, prod")
            }

        private fun String.toLogFormat(): LogFormat =
            when (trim().lowercase()) {
                "pretty" -> LogFormat.Pretty
                "json" -> LogFormat.Json
                else -> error("LOG_FORMAT must be one of pretty, json")
            }
    }
}

data class ResponseRunRuntimeConfig(
    val workerEnabled: Boolean,
    val pollInterval: Duration,
    val leaseDuration: Duration,
    val heartbeatInterval: Duration,
    val retryBackoff: Duration,
    val maxAttempts: Int,
    val maxDuration: Duration,
) {
    init {
        require(heartbeatInterval < leaseDuration) { "RESPONSE_RUN_HEARTBEAT_INTERVAL_MS must be shorter than lease duration" }
    }
}

data class LoggingRuntimeConfig(
    val environment: RuntimeEnvironment,
    val level: LogLevel,
    val format: LogFormat,
    val serviceName: String,
)

enum class RuntimeEnvironment(
    val value: String,
) {
    Local("local"),
    Staging("staging"),
    Prod("prod"),
}

enum class LogFormat {
    Pretty,
    Json,
}

data class AiRuntimeConfig(
    val provider: AiProvider,
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val requestTimeout: Duration,
    val enableThinking: Boolean?,
) {
    override fun toString(): String =
        "AiRuntimeConfig(provider=$provider, baseUrl=$baseUrl, model=$model, requestTimeout=$requestTimeout, " +
            "enableThinking=$enableThinking, apiKey=<redacted>)"
}

enum class AiProvider {
    OpenAi,
    Qwen,
    DeepSeek,
}

data class ExternalSourcesRuntimeConfig(
    val mode: OpportunitySourceMode,
    val requestTimeout: Duration,
    val userAgent: String,
    val tavily: TavilyRuntimeConfig?,
    val tmdb: TmdbRuntimeConfig? = null,
    val omdb: OmdbRuntimeConfig? = null,
    val apiFootball: ApiFootballRuntimeConfig? = null,
    val footballData: FootballDataRuntimeConfig? = null,
    val ticketmaster: TicketmasterRuntimeConfig? = null,
    val musicBrainz: MusicBrainzRuntimeConfig = MusicBrainzRuntimeConfig(),
    val theSportsDb: TheSportsDbRuntimeConfig = TheSportsDbRuntimeConfig(),
    val openRouteService: OpenRouteServiceRuntimeConfig? = null,
    val overpass: OverpassRuntimeConfig = OverpassRuntimeConfig(),
    val trailSplits: TrailSplitsRuntimeConfig = TrailSplitsRuntimeConfig(),
    val nominatim: NominatimRuntimeConfig = NominatimRuntimeConfig(),
    val openMeteo: OpenMeteoRuntimeConfig = OpenMeteoRuntimeConfig(),
    val metNo: MetNoRuntimeConfig = MetNoRuntimeConfig(),
    val chinaOfficialCinema: ChinaOfficialCinemaRuntimeConfig? = null,
)

enum class OpportunitySourceMode {
    External,
}

data class TavilyRuntimeConfig(
    val apiKey: String,
    val baseUrl: String = "https://api.tavily.com",
) {
    override fun toString(): String = "TavilyRuntimeConfig(baseUrl=$baseUrl, apiKey=<redacted>)"
}

data class TmdbRuntimeConfig(
    val apiReadToken: String,
    val baseUrl: String = "https://api.themoviedb.org/3",
) {
    override fun toString(): String = "TmdbRuntimeConfig(baseUrl=$baseUrl, apiReadToken=<redacted>)"
}

data class OmdbRuntimeConfig(
    val apiKey: String,
    val baseUrl: String = "https://www.omdbapi.com/",
) {
    override fun toString(): String = "OmdbRuntimeConfig(baseUrl=$baseUrl, apiKey=<redacted>)"
}

data class ApiFootballRuntimeConfig(
    val apiKey: String,
    val baseUrl: String = "https://v3.football.api-sports.io",
) {
    override fun toString(): String = "ApiFootballRuntimeConfig(baseUrl=$baseUrl, apiKey=<redacted>)"
}

data class FootballDataRuntimeConfig(
    val apiToken: String,
    val baseUrl: String = "https://api.football-data.org/v4",
) {
    override fun toString(): String = "FootballDataRuntimeConfig(baseUrl=$baseUrl, apiToken=<redacted>)"
}

data class TicketmasterRuntimeConfig(
    val apiKey: String,
    val baseUrl: String = "https://app.ticketmaster.com/discovery/v2",
) {
    override fun toString(): String = "TicketmasterRuntimeConfig(baseUrl=$baseUrl, apiKey=<redacted>)"
}

data class MusicBrainzRuntimeConfig(
    val baseUrl: String = "https://musicbrainz.org/ws/2",
)

data class TheSportsDbRuntimeConfig(
    val apiKey: String = "3",
    val baseUrl: String = "https://www.thesportsdb.com",
) {
    override fun toString(): String = "TheSportsDbRuntimeConfig(baseUrl=$baseUrl, apiKey=<redacted>)"
}

data class OpenRouteServiceRuntimeConfig(
    val apiKey: String,
    val routingBaseUrl: String = "https://api.heigit.org/openrouteservice",
    val geocodeBaseUrl: String = "https://api.heigit.org/pelias/v1",
) {
    override fun toString(): String =
        "OpenRouteServiceRuntimeConfig(routingBaseUrl=$routingBaseUrl, geocodeBaseUrl=$geocodeBaseUrl, apiKey=<redacted>)"
}

data class OverpassRuntimeConfig(
    val baseUrl: String = "https://overpass-api.de/api",
)

data class TrailSplitsRuntimeConfig(
    val baseUrl: String = "https://api.trailsplits.com",
)

data class NominatimRuntimeConfig(
    val baseUrl: String = "https://nominatim.openstreetmap.org",
)

data class OpenMeteoRuntimeConfig(
    val baseUrl: String = "https://api.open-meteo.com",
)

data class MetNoRuntimeConfig(
    val baseUrl: String = "https://api.met.no",
)

data class ChinaOfficialCinemaRuntimeConfig(
    val pageUrls: List<String>,
) {
    init {
        require(pageUrls.isNotEmpty()) { "pageUrls must not be empty" }
        require(pageUrls.all(String::isNotBlank)) { "pageUrls must not contain blank values" }
    }
}
