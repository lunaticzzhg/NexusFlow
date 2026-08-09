package com.nexusflow.app.core.network

import kotlin.time.Duration.Companion.seconds

/** Default bounds for ordinary request/response API calls. */
object NetworkDefaults {
    val requestTimeout = 30.seconds
    val connectTimeout = 10.seconds
    val socketTimeout = 30.seconds
}
