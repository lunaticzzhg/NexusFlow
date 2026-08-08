package com.nexusflow.app.core.time

private object AndroidAppClock : AppClock {
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}

actual fun platformAppClock(): AppClock = AndroidAppClock
