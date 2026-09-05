package com.nexusflow.backend.core.observability

import com.nexusflow.observability.LogLevel
import com.nexusflow.observability.LogSink
import org.slf4j.LoggerFactory

internal object Slf4jLogSink : LogSink {
    private val logger = LoggerFactory.getLogger("com.nexusflow.backend.observability")

    override fun write(
        level: LogLevel,
        component: String,
        formatted: String,
    ) {
        when (level) {
            LogLevel.DEBUG -> logger.debug(formatted)
            LogLevel.INFO -> logger.info(formatted)
            LogLevel.WARN -> logger.warn(formatted)
            LogLevel.ERROR -> logger.error(formatted)
        }
    }
}

internal object StdoutLogSink : LogSink {
    override fun write(
        level: LogLevel,
        component: String,
        formatted: String,
    ) {
        println(formatted)
    }
}
