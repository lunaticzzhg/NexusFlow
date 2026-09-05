package com.nexusflow.observability

data class LogRecord(
    val timestamp: String,
    val level: LogLevel,
    val traceId: String?,
    val service: String,
    val component: String,
    val environment: String,
    val event: String,
    val fields: LogFields = LogFields.Empty,
    val errorType: String? = null,
)
