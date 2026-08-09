package com.nexusflow.app.core.observability

import platform.Foundation.NSLog

internal actual fun createPlatformLogSink(): PlatformLogSink = IosLogSink

private object IosLogSink : PlatformLogSink {
    override fun write(
        level: LogLevel,
        tag: LogTag,
        message: String,
    ) {
        NSLog("tag=${tag.value} ${message.replace("%", "%%")}")
    }
}
