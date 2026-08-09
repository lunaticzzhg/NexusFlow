package com.nexusflow.app.core.observability

import com.nexusflow.app.core.config.BuildMode
import com.nexusflow.app.core.config.RuntimeConfig

internal class StructuredAppLogger(
    private val runtimeConfig: RuntimeConfig,
    private val sink: PlatformLogSink,
) : AppLogger {
    override fun log(
        level: LogLevel,
        tag: LogTag,
        event: String,
        fields: LogFields,
        cause: Throwable?,
    ) {
        if (!isEnabled(level)) return

        runCatching { sink.write(level, tag, format(level, event, fields, cause)) }
    }

    private fun isEnabled(level: LogLevel): Boolean = level.ordinal >= minimumLevel.ordinal

    private val minimumLevel: LogLevel
        get() = if (runtimeConfig.buildMode == BuildMode.DEBUG) LogLevel.DEBUG else LogLevel.INFO

    private fun format(
        level: LogLevel,
        event: String,
        fields: LogFields,
        cause: Throwable?,
    ): String =
        buildString {
            append("level=")
            append(level.name)
            append(" event=")
            append(event.takeIf(::isValidEvent) ?: INVALID_EVENT)

            fields.values
                .asSequence()
                .filter { (key, _) -> isValidFieldKey(key) && !isSensitiveField(key) }
                .sortedBy { (key, _) -> key }
                .take(MAX_FIELD_COUNT)
                .forEach { (key, value) ->
                    append(' ')
                    append(key)
                    append('=')
                    append(escape(value, MAX_VALUE_LENGTH))
                }

            cause?.let {
                append(" error_type=")
                append(escape(it::class.simpleName ?: UNKNOWN_THROWABLE, MAX_VALUE_LENGTH))
            }
        }

    private fun isValidEvent(value: String): Boolean = EVENT_PATTERN.matches(value)

    private fun isValidFieldKey(value: String): Boolean = FIELD_KEY_PATTERN.matches(value)

    private fun isSensitiveField(key: String): Boolean {
        val normalizedKey = key.lowercase()
        return SENSITIVE_KEY_PARTS.any(normalizedKey::contains)
    }

    private fun escape(
        value: String,
        maxLength: Int,
    ): String {
        val bounded = value.take(maxLength)
        return buildString(bounded.length) {
            bounded.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character.code < CONTROL_CHARACTER_LIMIT) append('?') else append(character)
                }
            }
        }
    }

    private companion object {
        const val MAX_FIELD_COUNT = 16
        const val MAX_VALUE_LENGTH = 256
        const val CONTROL_CHARACTER_LIMIT = 32
        const val INVALID_EVENT = "invalid_event"
        const val UNKNOWN_THROWABLE = "UnknownThrowable"

        val EVENT_PATTERN = Regex("[a-z][a-z0-9_]{0,63}")
        val FIELD_KEY_PATTERN = Regex("[a-z][a-z0-9_]{0,47}")
        val SENSITIVE_KEY_PARTS =
            setOf(
                "token",
                "credential",
                "authorization",
                "password",
                "secret",
                "cookie",
                "session",
                "user_id",
                "tenant_id",
                "email",
                "subject",
                "header",
                "body",
                "detail",
                "query",
            )
    }
}
