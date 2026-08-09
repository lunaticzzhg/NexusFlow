package com.nexusflow.app.core.config

import platform.Foundation.NSBundle
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.Platform

@OptIn(ExperimentalNativeApi::class)
actual fun platformRuntimeConfig(): RuntimeConfig =
    RuntimeConfig(
        apiBaseUrl = configuredApiBaseUrl(),
        googleServerClientId = configuredString("GIDServerClientID"),
        buildMode = BuildMode.from(Platform.isDebugBinary),
    )

private fun configuredApiBaseUrl(): String = configuredString("OrbitApiBaseUrl")

private fun configuredString(key: String): String =
    (NSBundle.mainBundle.objectForInfoDictionaryKey(key) as? String)
        ?.trim()
        ?.takeUnless { it.isEmpty() || it.startsWith("$(") }
        .orEmpty()
