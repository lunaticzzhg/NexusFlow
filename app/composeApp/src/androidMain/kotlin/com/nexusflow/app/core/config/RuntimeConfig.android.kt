package com.nexusflow.app.core.config

import com.nexusflow.app.BuildConfig

actual fun platformRuntimeConfig(): RuntimeConfig =
    RuntimeConfig(
        apiBaseUrl = BuildConfig.API_BASE_URL,
        googleServerClientId = BuildConfig.GOOGLE_SERVER_CLIENT_ID,
        buildMode = BuildMode.from(BuildConfig.DEBUG),
    )
