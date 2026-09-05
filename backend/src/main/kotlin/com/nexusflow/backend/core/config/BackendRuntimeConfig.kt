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
            return AiRuntimeConfig(
                provider = providerText.toAiProvider(),
                apiKey = required(environment, "AI_API_KEY"),
                baseUrl = required(environment, "AI_BASE_URL"),
                model = required(environment, "AI_MODEL"),
                requestTimeout = environment["AI_REQUEST_TIMEOUT_MS"]
                    ?.takeIf(String::isNotBlank)
                    ?.toLong()
                    ?.also { require(it > 0) { "AI_REQUEST_TIMEOUT_MS must be positive" } }
                    ?.let(Duration::ofMillis)
                    ?: Duration.ofSeconds(30),
            )
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
) {
    override fun toString(): String =
        "AiRuntimeConfig(provider=$provider, baseUrl=$baseUrl, model=$model, requestTimeout=$requestTimeout, apiKey=<redacted>)"
}

enum class AiProvider {
    OpenAi,
    Qwen,
    DeepSeek,
}
