package com.nexusflow.observability

enum class LogLevel(
    val priority: Int,
) {
    DEBUG(10),
    INFO(20),
    WARN(30),
    ERROR(40),
}

fun String.toLogLevelOrNull(): LogLevel? =
    when (trim().uppercase()) {
        "DEBUG" -> LogLevel.DEBUG
        "INFO" -> LogLevel.INFO
        "WARN", "WARNING" -> LogLevel.WARN
        "ERROR" -> LogLevel.ERROR
        else -> null
    }
