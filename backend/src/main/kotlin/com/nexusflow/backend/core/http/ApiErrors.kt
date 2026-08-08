package com.nexusflow.backend.core.http

import com.nexusflow.contracts.api.ApiErrorCode
import com.nexusflow.contracts.api.ApiErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.callid.callId
import io.ktor.server.response.respond

internal suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    code: ApiErrorCode,
    message: String,
) {
    respond(status, ApiErrorResponse(code, message, traceId()))
}

internal fun ApplicationCall.traceId(): String = requireNotNull(callId) { "CallId must be installed before handling requests" }
