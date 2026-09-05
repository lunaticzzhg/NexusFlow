package com.nexusflow.app.core.observability

typealias LogLevel = com.nexusflow.observability.LogLevel

/** Records safe, structured technical events for app diagnostics. */
interface AppLogger {
    fun log(
        level: LogLevel,
        tag: LogTag,
        event: String,
        fields: LogFields = LogFields.Empty,
        cause: Throwable? = null,
    )

    fun debug(
        tag: LogTag,
        event: String,
        fields: LogFields = LogFields.Empty,
    ) {
        log(level = LogLevel.DEBUG, tag = tag, event = event, fields = fields)
    }

    fun info(
        tag: LogTag,
        event: String,
        fields: LogFields = LogFields.Empty,
    ) {
        log(level = LogLevel.INFO, tag = tag, event = event, fields = fields)
    }

    fun warn(
        tag: LogTag,
        event: String,
        fields: LogFields = LogFields.Empty,
    ) {
        log(level = LogLevel.WARN, tag = tag, event = event, fields = fields)
    }

    fun error(
        tag: LogTag,
        event: String,
        fields: LogFields = LogFields.Empty,
        cause: Throwable? = null,
    ) {
        log(level = LogLevel.ERROR, tag = tag, event = event, fields = fields, cause = cause)
    }
}
