package com.nexusflow.observability

interface LogFormatter {
    fun format(record: LogRecord): String
}

object PrettyLogFormatter : LogFormatter {
    override fun format(record: LogRecord): String =
        buildString {
            append(record.timestamp)
            append(' ')
            append(record.level.name)
            append(" component=")
            append(LogSanitizer.escape(record.component))
            append(" event=")
            append(record.event)

            appendProjectedField("action", record.fields.explicitAction(record.event))
            appendProjectedField("trace", record.traceId?.shortIdentity())
            appendProjectedField("operation_type", record.fields.values["operation_type"])
            appendProjectedField("step", record.fields.values["step"])
            appendProjectedField("operation_id", record.fields.values["operation_id"]?.shortIdentity())
            appendProjectedField("branch", record.fields.values["branch"])
            appendProjectedField("stage", record.fields.values["stage"])
            appendProjectedField("outcome", record.fields.values["outcome"])
            appendProjectedField("duration_ms", record.fields.values["duration_ms"])

            record.errorType?.let { appendProjectedField("error_type", it) }

            if (record.level == LogLevel.DEBUG) {
                appendDebugDetails(record)
            }
        }

    private fun StringBuilder.appendProjectedField(
        key: String,
        value: String?,
    ) {
        if (value.isNullOrBlank()) return
        append(' ')
        append(key)
        append('=')
        append(LogSanitizer.escape(value))
    }

    private fun StringBuilder.appendDebugDetails(record: LogRecord) {
        val detailFields = record.fields.values.filterKeys { it !in HUMAN_FIELD_KEYS }
        if (detailFields.isEmpty()) return
        append(" detail=\"")
        detailFields.entries.forEachIndexed { index, (key, value) ->
            if (index > 0) append(' ')
            append(key)
            append('=')
            append(LogSanitizer.escape(value))
        }
        append('"')
    }
}

private val HUMAN_FIELD_KEYS =
    setOf(
        "action",
        "operation_type",
        "step",
        "operation_id",
        "branch",
        "stage",
        "outcome",
        "duration_ms",
    )

private fun LogFields.explicitAction(event: String): String? =
    values["action"]?.takeUnless { it == event }

private fun String.shortIdentity(): String =
    when {
        length <= 16 -> this
        else -> take(12)
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
