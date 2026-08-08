package com.nexusflow.app.core.time

interface AppClock {
    fun currentTimeMillis(): Long
}

expect fun platformAppClock(): AppClock
