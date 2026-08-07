package com.nexusflow.app.core.config

actual fun platformRuntimeConfig(): RuntimeConfig =
    RuntimeConfig(
        apiBaseUrl = "http://localhost:8080",
        buildMode = BuildMode.DEBUG,
    )
