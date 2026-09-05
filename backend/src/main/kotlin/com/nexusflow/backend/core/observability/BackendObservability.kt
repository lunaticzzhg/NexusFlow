package com.nexusflow.backend.core.observability

import com.nexusflow.backend.core.config.LogFormat
import com.nexusflow.backend.core.config.LoggingRuntimeConfig
import com.nexusflow.backend.core.config.RuntimeEnvironment
import com.nexusflow.observability.DefaultStructuredLogger
import com.nexusflow.observability.JsonLogFormatter
import com.nexusflow.observability.LogLevel
import com.nexusflow.observability.PrettyLogFormatter
import com.nexusflow.observability.StructuredLogger

internal fun defaultBackendLoggingRuntimeConfig(): LoggingRuntimeConfig =
    LoggingRuntimeConfig(
        environment = RuntimeEnvironment.Local,
        level = LogLevel.INFO,
        format = LogFormat.Pretty,
        serviceName = "nexusflow-backend",
    )

internal fun backendStructuredLogger(config: LoggingRuntimeConfig): StructuredLogger =
    DefaultStructuredLogger(
        traceContext = BackendTraceContext,
        formatter =
            when (config.format) {
                LogFormat.Pretty -> PrettyLogFormatter
                LogFormat.Json -> JsonLogFormatter
            },
        sink =
            when (config.format) {
                LogFormat.Pretty -> Slf4jLogSink
                LogFormat.Json -> StdoutLogSink
            },
        serviceName = config.serviceName,
        environment = config.environment.value,
        minimumLevel = config.level,
    )
