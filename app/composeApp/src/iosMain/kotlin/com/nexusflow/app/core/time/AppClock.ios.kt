package com.nexusflow.app.core.time

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

private object IosAppClock : AppClock {
    override fun currentTimeMillis(): Long = epochSecondsToMillis(NSDate().timeIntervalSince1970)
}

actual fun platformAppClock(): AppClock = IosAppClock

internal fun epochSecondsToMillis(epochSeconds: Double): Long = (epochSeconds * 1_000.0).toLong()
