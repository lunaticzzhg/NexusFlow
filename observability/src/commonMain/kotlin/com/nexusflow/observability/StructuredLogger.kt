package com.nexusflow.observability

import kotlinx.datetime.Clock

interface LogSink {
    fun write(
        level: LogLevel,
        component: String,
        formatted: String,
    )
}

interface StructuredLogger {
    fun log(
        level: LogLevel,
        component: String,
        event: String,
        fields: LogFields = LogFields.Empty,
        cause: Throwable? = null,
    )

    fun debug(
        component: String,
        event: String,
        fields: LogFields = LogFields.Empty,
    ) = log(LogLevel.DEBUG, component, event, fields)

    fun info(
        component: String,
        event: String,
        fields: LogFields = LogFields.Empty,
    ) = log(LogLevel.INFO, component, event, fields)

    fun warn(
        component: String,
        event: String,
        fields: LogFields = LogFields.Empty,
    ) = log(LogLevel.WARN, component, event, fields)

    fun error(
        component: String,
        event: String,
        fields: LogFields = LogFields.Empty,
        cause: Throwable? = null,
    ) = log(LogLevel.ERROR, component, event, fields, cause)
}

class DefaultStructuredLogger(
    private val traceContext: TraceContext,
    private val formatter: LogFormatter,
    private val sink: LogSink,
    private val serviceName: String,
    private val environment: String,
    private val minimumLevel: LogLevel,
    private val clock: Clock = Clock.System,
) : StructuredLogger {
    override fun log(
        level: LogLevel,
        component: String,
        event: String,
        fields: LogFields,
        cause: Throwable?,
    ) {
        if (level.priority < minimumLevel.priority) return

        val safeComponent = LogSanitizer.sanitizeComponent(component)
        val record =
            LogRecord(
                timestamp = clock.now().toString(),
                level = level,
                traceId = traceContext.currentTraceId()?.value,
                service = LogSanitizer.sanitizeServiceName(serviceName),
                component = safeComponent,
                environment = LogSanitizer.sanitizeEnvironment(environment),
                event = LogSanitizer.sanitizeEvent(event),
                fields = LogSanitizer.sanitizeFields(fields),
                errorType = cause?.let { it::class.simpleName ?: "UnknownThrowable" },
            )

        runCatching {
            sink.write(level = level, component = safeComponent, formatted = formatter.format(record))
        }
    }
}
