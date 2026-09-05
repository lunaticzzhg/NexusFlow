package com.nexusflow.backend.core.http

import com.nexusflow.backend.core.config.LoggingRuntimeConfig
import com.nexusflow.backend.core.observability.MDC_TRACE_ID
import com.nexusflow.backend.core.observability.backendStructuredLogger
import com.nexusflow.backend.core.observability.defaultBackendLoggingRuntimeConfig
import com.nexusflow.observability.LogFields
import com.nexusflow.observability.RandomTraceIdGenerator
import com.nexusflow.observability.StructuredLogger
import com.nexusflow.observability.TraceHeaders
import com.nexusflow.observability.TraceId
import com.nexusflow.observability.logFields
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.ContentConvertException
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.application.hooks.CallSetup
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.callid.generate
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey
import kotlinx.serialization.json.Json
import org.slf4j.MDC
import org.slf4j.event.Level

fun Application.configureHttpPlatform() {
    configureHttpPlatform(defaultBackendLoggingRuntimeConfig())
}

fun Application.configureHttpPlatform(logging: LoggingRuntimeConfig) {
    configureHttpPlatform(backendStructuredLogger(logging))
}

internal fun Application.configureHttpPlatform(logger: StructuredLogger) {
    install(CallId) {
        retrieve { call ->
            call.request.headers[TraceHeaders.TraceId]?.takeIf(TraceId::isValid)
        }
        generate { RandomTraceIdGenerator.newTraceId().value }
        verify(TraceId::isValid)
        replyToHeader(TraceHeaders.TraceId)
    }

    install(CallLogging) {
        level = Level.INFO
        mdc(MDC_TRACE_ID) { call -> call.callId }
        filter { false }
    }

    install(BackendHttpLogging) {
        this.logger = logger
    }

    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
                encodeDefaults = false
            },
        )
    }

    install(StatusPages) {
        exception<BadRequestException> { call, _ ->
            call.respondError(HttpStatusCode.UnprocessableEntity, "Request body is invalid")
        }
        exception<ContentConvertException> { call, _ ->
            call.respondError(HttpStatusCode.UnprocessableEntity, "Request body is invalid")
        }
        exception<Throwable> { call, cause ->
            logger.logUnexpectedRequestFailure(call, cause)
            call.respondError(HttpStatusCode.InternalServerError, "An unexpected error occurred")
        }
    }
}

private class BackendHttpLoggingConfig {
    lateinit var logger: StructuredLogger
}

private val BackendHttpStartedAtNanos = AttributeKey<Long>("BackendHttpStartedAtNanos")

private val BackendHttpLogging = createApplicationPlugin(
    name = "BackendHttpLogging",
    createConfiguration = ::BackendHttpLoggingConfig,
) {
    val logger = pluginConfig.logger

    on(CallSetup) { call ->
        call.attributes.put(BackendHttpStartedAtNanos, System.nanoTime())
        call.withTraceMdc {
            logger.info(
                component = HTTP_LOG_COMPONENT,
                event = "http_request_started",
                fields = call.httpLogFields(),
            )
        }
    }

    on(ResponseSent) { call ->
        call.withTraceMdc {
            logger.info(
                component = HTTP_LOG_COMPONENT,
                event = "http_request_finished",
                fields =
                    call.httpLogFields(
                        httpStatus = call.response.status()?.value ?: 0,
                        durationMs = call.elapsedMillis(),
                    ),
            )
        }
    }
}

private fun StructuredLogger.logUnexpectedRequestFailure(
    call: ApplicationCall,
    cause: Throwable,
) {
    call.withTraceMdc {
        error(
            component = HTTP_LOG_COMPONENT,
            event = "http_request_failed",
            fields =
                call.httpLogFields(
                    httpStatus = HttpStatusCode.InternalServerError.value,
                    durationMs = call.elapsedMillis(),
                ),
            cause = cause,
        )
    }
}

private fun ApplicationCall.httpLogFields(
    httpStatus: Int? = null,
    durationMs: Long? = null,
): LogFields =
    logFields {
        "http_method" value request.httpMethod.value
        "http_path" value safeHttpPath()
        "http_status" value httpStatus
        "duration_ms" value durationMs
    }

private fun ApplicationCall.elapsedMillis(): Long? =
    attributes.getOrNull(BackendHttpStartedAtNanos)
        ?.let { started -> ((System.nanoTime() - started) / NANOS_PER_MILLISECOND).coerceAtLeast(0) }

private fun ApplicationCall.withTraceMdc(block: () -> Unit) {
    val traceId = callId
    val previousTraceId = MDC.get(MDC_TRACE_ID)
    if (traceId != null) {
        MDC.put(MDC_TRACE_ID, traceId)
    } else {
        MDC.remove(MDC_TRACE_ID)
    }
    try {
        block()
    } finally {
        if (previousTraceId != null) {
            MDC.put(MDC_TRACE_ID, previousTraceId)
        } else {
            MDC.remove(MDC_TRACE_ID)
        }
    }
}

private fun ApplicationCall.safeHttpPath(): String =
    request.path()
        .split('/')
        .joinToString("/") { segment ->
            if (segment.isLikelyDynamicPathSegment()) "{id}" else segment
        }

private fun String.isLikelyDynamicPathSegment(): Boolean =
    length >= MIN_DYNAMIC_PATH_SEGMENT_LENGTH &&
        any(Char::isDigit) &&
        all { character ->
            character.isLetterOrDigit() || character == '-' || character == '_' || character == '%'
        }

private const val HTTP_LOG_COMPONENT = "http"
private const val MIN_DYNAMIC_PATH_SEGMENT_LENGTH = 8
private const val NANOS_PER_MILLISECOND = 1_000_000L
