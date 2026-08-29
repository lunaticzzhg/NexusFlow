package com.nexusflow.backend.core.config

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
    val openAiApiKey: String?,
    val openAiModel: String?,
    val fixturePlanningEnabled: Boolean,
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
            openAiApiKey = environment["OPENAI_API_KEY"]?.takeIf(String::isNotBlank),
            openAiModel = environment["OPENAI_MODEL"]?.takeIf(String::isNotBlank),
            fixturePlanningEnabled = environment["ORBIT_FIXTURE_PLANNING_ENABLED"]?.toBooleanStrictOrNull() ?: false,
            devLoginEnabled = environment["ORBIT_DEV_LOGIN_ENABLED"]?.toBooleanStrictOrNull() ?: false,
            devLoginEmail = environment["ORBIT_DEV_LOGIN_EMAIL"]?.takeIf(String::isNotBlank),
            devLoginPassword = environment["ORBIT_DEV_LOGIN_PASSWORD"]?.takeIf(String::isNotBlank),
        )

        private fun required(environment: Map<String, String>, name: String): String = environment[name]
            ?.takeIf(String::isNotBlank)
            ?: error("$name must be configured")
    }
}
