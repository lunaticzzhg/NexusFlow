package com.nexusflow.app.core.observability

import android.util.Log

internal actual fun createPlatformLogSink(): PlatformLogSink = AndroidLogSink

private object AndroidLogSink : PlatformLogSink {
    override fun write(
        level: LogLevel,
        tag: LogTag,
        message: String,
    ) {
        when (level) {
            LogLevel.DEBUG -> Log.d(tag.value, message)
            LogLevel.INFO -> Log.i(tag.value, message)
            LogLevel.ERROR -> Log.e(tag.value, message)
        }
    }
}
