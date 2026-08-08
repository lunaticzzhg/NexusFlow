package com.nexusflow.app.core.config

actual fun platformRuntimeConfig(): RuntimeConfig =
    RuntimeConfig(
        apiBaseUrl = "http://localhost:8080",
        googleServerClientId = "",
        buildMode = BuildMode.DEBUG,
    )
