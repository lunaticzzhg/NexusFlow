package com.nexusflow.app.core.config

actual fun platformRuntimeConfig(): RuntimeConfig =
    RuntimeConfig(
        apiBaseUrl = "http://10.0.2.2:8080",
        buildMode = BuildMode.DEBUG,
    )
