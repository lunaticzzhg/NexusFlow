package com.nexusflow.app.core.observability

import com.nexusflow.app.core.config.BuildMode
import com.nexusflow.app.core.config.RuntimeConfig
import com.nexusflow.observability.CoroutineTraceContext
import com.nexusflow.observability.LogRecord
import com.nexusflow.observability.LogSanitizer
import com.nexusflow.observability.PrettyLogFormatter
import com.nexusflow.observability.TraceContext
import com.nexusflow.observability.TraceId
import kotlinx.datetime.Clock

internal class StructuredAppLogger(
    private val runtimeConfig: RuntimeConfig,
    private val sink: PlatformLogSink,
    private val traceContext: TraceContext = CoroutineTraceContext,
    private val clock: Clock = Clock.System,
) : AppLogger {
    override fun log(
        level: LogLevel,
        tag: LogTag,
        event: String,
        fields: LogFields,
        cause: Throwable?,
    ) {
        if (!isEnabled(level)) return

        runCatching { sink.write(level, tag, format(level, tag, event, fields, cause)) }
    }

    private fun isEnabled(level: LogLevel): Boolean = level.priority >= minimumLevel.priority

    private val minimumLevel: LogLevel
        get() = if (runtimeConfig.buildMode == BuildMode.DEBUG) LogLevel.DEBUG else LogLevel.INFO

    private fun format(
        level: LogLevel,
        tag: LogTag,
        event: String,
        fields: LogFields,
        cause: Throwable?,
    ): String {
        val traceId =
            fields.values[TRACE_ID_FIELD]?.takeIf(TraceId::isValid)
                ?: traceContext.currentTraceId()?.value
        val record =
            LogRecord(
                timestamp = clock.now().toString(),
                level = level,
                traceId = traceId,
                service = SERVICE_NAME,
                component = LogSanitizer.sanitizeComponent(tag.value),
                environment = if (runtimeConfig.buildMode == BuildMode.DEBUG) "debug" else "release",
                event = LogSanitizer.sanitizeEvent(event),
                fields = LogSanitizer.sanitizeFields(fields.without(TRACE_ID_FIELD)),
                errorType = cause?.let { it::class.simpleName ?: UNKNOWN_THROWABLE },
            )
        return PrettyLogFormatter.format(record)
    }

    private companion object {
        const val TRACE_ID_FIELD = "trace_id"
        const val SERVICE_NAME = "nexusflow-app"
        const val UNKNOWN_THROWABLE = "UnknownThrowable"
    }
}
