package com.nexusflow.app.core.observability

internal interface PlatformLogSink {
    fun write(
        level: LogLevel,
        tag: LogTag,
        message: String,
    )
}

internal expect fun createPlatformLogSink(): PlatformLogSink
