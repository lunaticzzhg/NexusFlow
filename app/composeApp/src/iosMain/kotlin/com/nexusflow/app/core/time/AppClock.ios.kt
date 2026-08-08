package com.nexusflow.app.core.time

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.time

@OptIn(ExperimentalForeignApi::class)
private object IosAppClock : AppClock {
    override fun currentTimeMillis(): Long = time(null) * 1_000
}

actual fun platformAppClock(): AppClock = IosAppClock
