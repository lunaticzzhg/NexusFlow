package com.nexusflow.backend.core.http

import com.nexusflow.contracts.api.KResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond

internal suspend fun ApplicationCall.respondError(
    status: HttpStatusCode,
    message: String,
) {
    respond(status, KResponse<Nothing>(code = status.value, message = message))
}

internal suspend inline fun <reified T> ApplicationCall.respondSuccess(
    data: T? = null,
) {
    respond(HttpStatusCode.OK, KResponse(code = HttpStatusCode.OK.value, data = data))
}
