package com.nexusflow.observability

object LogSanitizer {
    const val MaxFieldCount = 16
    const val MaxValueLength = 256

    private const val ControlCharacterLimit = 32
    private const val InvalidEvent = "invalid_event"

    private val EventPattern = Regex("[a-z][a-z0-9_]{0,63}")
    private val FieldKeyPattern = Regex("[a-z][a-z0-9_]{0,47}")
    private val SensitiveKeyParts =
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

    fun sanitizeEvent(value: String): String = value.takeIf(EventPattern::matches) ?: InvalidEvent

    fun sanitizeComponent(value: String): String =
        value
            .lowercase()
            .map { character ->
                when {
                    character in 'a'..'z' || character in '0'..'9' -> character
                    else -> '_'
                }
            }.joinToString("")
            .trim('_')
            .take(48)
            .ifBlank { "unknown" }

    fun sanitizeServiceName(value: String): String =
        value
            .lowercase()
            .mapNotNull { character ->
                when {
                    character in 'a'..'z' || character in '0'..'9' || character == '-' || character == '_' -> character
                    else -> null
                }
            }.joinToString("")
            .take(64)
            .ifBlank { "unknown-service" }

    fun sanitizeEnvironment(value: String): String =
        value
            .lowercase()
            .mapNotNull { character ->
                when {
                    character in 'a'..'z' || character in '0'..'9' || character == '-' || character == '_' -> character
                    else -> null
                }
            }.joinToString("")
            .take(32)
            .ifBlank { "unknown" }

    fun sanitizeFields(fields: LogFields): LogFields =
        LogFields.from(
            fields.values
                .asSequence()
                .filter { (key, _) -> isValidFieldKey(key) && !isSensitiveField(key) }
                .sortedBy { (key, _) -> key }
                .take(MaxFieldCount)
                .associate { (key, value) -> key to escape(value, MaxValueLength) },
        )

    fun escape(
        value: String,
        maxLength: Int = MaxValueLength,
    ): String {
        val bounded = value.take(maxLength)
        return buildString(bounded.length) {
            bounded.forEach { character ->
                when (character) {
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> if (character.code < ControlCharacterLimit) append('?') else append(character)
                }
            }
        }
    }

    private fun isValidFieldKey(value: String): Boolean = FieldKeyPattern.matches(value)

    private fun isSensitiveField(key: String): Boolean {
        val normalizedKey = key.lowercase()
        return SensitiveKeyParts.any(normalizedKey::contains)
    }
}
