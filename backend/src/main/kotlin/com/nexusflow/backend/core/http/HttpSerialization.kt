package com.nexusflow.backend.core.http

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.ContentConvertException
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.callid.generate
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import kotlinx.serialization.json.Json
import org.slf4j.event.Level
import java.util.UUID

fun Application.configureHttpPlatform() {
    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)
        generate { UUID.randomUUID().toString() }
        verify { callId -> callId.matches(RequestIdPattern) }
        replyToHeader(HttpHeaders.XRequestId)
    }

    install(CallLogging) {
        level = Level.INFO
        mdc("requestId") { call -> call.callId }
        format { call ->
            "${call.request.httpMethod.value} ${call.request.path()} ${call.response.status()?.value ?: 0}"
        }
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
            call.application.environment.log.error(
                "Unhandled request failure [requestId=${call.callId}, type=${cause::class.simpleName}]",
                cause,
            )
            call.respondError(HttpStatusCode.InternalServerError, "An unexpected error occurred")
        }
    }
}

private val RequestIdPattern = Regex("[A-Za-z0-9-]{8,128}")
