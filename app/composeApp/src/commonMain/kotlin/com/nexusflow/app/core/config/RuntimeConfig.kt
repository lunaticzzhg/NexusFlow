package com.nexusflow.app.core.config

/** Non-sensitive runtime facts. Secrets and user credentials never belong here. */
data class RuntimeConfig(
    val apiBaseUrl: String,
    val googleServerClientId: String,
    val buildMode: BuildMode,
)

enum class BuildMode {
    DEBUG,
    RELEASE,

    ;

    companion object {
        fun from(isDebug: Boolean): BuildMode = if (isDebug) DEBUG else RELEASE
    }
}

expect fun platformRuntimeConfig(): RuntimeConfig
