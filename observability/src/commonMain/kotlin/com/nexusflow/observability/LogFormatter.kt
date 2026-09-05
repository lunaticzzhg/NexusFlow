package com.nexusflow.observability

interface LogFormatter {
    fun format(record: LogRecord): String
}

object PrettyLogFormatter : LogFormatter {
    override fun format(record: LogRecord): String =
        buildString {
            append("timestamp=")
            append(LogSanitizer.escape(record.timestamp))
            append(" level=")
            append(record.level.name)
            record.traceId?.let {
                append(" trace_id=")
                append(it)
            }
            append(" service=")
            append(LogSanitizer.escape(record.service))
            append(" component=")
            append(LogSanitizer.escape(record.component))
            append(" environment=")
            append(LogSanitizer.escape(record.environment))
            append(" event=")
            append(record.event)
            record.fields.values.forEach { (key, value) ->
                append(' ')
                append(key)
                append('=')
                append(value)
            }
            record.errorType?.let {
                append(" error_type=")
                append(LogSanitizer.escape(it))
            }
        }
}

object JsonLogFormatter : LogFormatter {
    override fun format(record: LogRecord): String =
        buildString {
            append('{')
            appendJsonField("timestamp", record.timestamp, first = true)
            appendJsonField("level", record.level.name)
            record.traceId?.let { appendJsonField("trace_id", it) }
            appendJsonField("service", record.service)
            appendJsonField("component", record.component)
            appendJsonField("environment", record.environment)
            appendJsonField("event", record.event)
            record.fields.values.forEach { (key, value) -> appendJsonField(key, value) }
            record.errorType?.let { appendJsonField("error_type", it) }
            append('}')
        }

    private fun StringBuilder.appendJsonField(
        key: String,
        value: String,
        first: Boolean = false,
    ) {
        if (!first) append(',')
        append('"')
        append(jsonEscape(key))
        append("\":\"")
        append(jsonEscape(value))
        append('"')
    }

    private fun jsonEscape(value: String): String =
        buildString(value.length) {
            value.forEach { character ->
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (character.code < 0x20) {
                            append("\\u")
                            append(character.code.toString(16).padStart(4, '0'))
                        } else {
                            append(character)
                        }
                    }
                }
            }
        }
}
