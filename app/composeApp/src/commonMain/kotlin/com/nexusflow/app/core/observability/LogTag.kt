package com.nexusflow.app.core.observability

/** Stable technical or business context used by platform logging systems. */
class LogTag private constructor(
    val value: String,
) {
    companion object {
        fun of(value: String): LogTag {
            require(TAG_PATTERN.matches(value)) {
                "Log tags must be 1..23 ASCII letters, numbers, or underscores and start with a letter."
            }
            return LogTag(value)
        }

        private val TAG_PATTERN = Regex("[A-Za-z][A-Za-z0-9_]{0,22}")
    }
}
