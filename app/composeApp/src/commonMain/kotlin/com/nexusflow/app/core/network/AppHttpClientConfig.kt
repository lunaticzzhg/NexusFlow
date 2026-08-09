package com.nexusflow.app.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

fun <T : HttpClientEngineConfig> HttpClientConfig<T>.configureAppHttpClient() {
    expectSuccess = false

    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            },
        )
    }
    install(HttpTimeout) {
        requestTimeoutMillis = NetworkDefaults.requestTimeout.inWholeMilliseconds
        connectTimeoutMillis = NetworkDefaults.connectTimeout.inWholeMilliseconds
        socketTimeoutMillis = NetworkDefaults.socketTimeout.inWholeMilliseconds
    }
}

/** Converts non-2xx responses from the configured first-party API into a stable transport error. */
internal fun HttpClient.installFirstPartyHttpStatusFailureInterceptor(apiBaseUrl: String) {
    val apiOrigin = Url(apiBaseUrl)
    plugin(HttpSend).intercept { request ->
        val call = execute(request)
        if (request.url.build().hasSameOrigin(apiOrigin) && call.response.status.value !in 200..299) {
            throw HttpFailureException(
                failure = call.response.status.value.toHttpFailure(),
                diagnostics = call.response.bodyAsText().toHttpFailureDiagnostics(call.response.status.value),
            )
        }
        call
    }
}

private fun String.toHttpFailureDiagnostics(httpStatus: Int): HttpFailureDiagnostics {
    val response = runCatching { Json.parseToJsonElement(this).jsonObject }.getOrNull()
    return HttpFailureDiagnostics(
        code = response?.get("code")?.jsonPrimitive?.intOrNull ?: httpStatus,
        message = response?.get("message")?.jsonPrimitive?.contentOrNull?.takeUnless(String::isEmpty),
    )
}

private fun Url.hasSameOrigin(other: Url): Boolean =
    protocol.name.equals(other.protocol.name, ignoreCase = true) &&
        host.equals(other.host, ignoreCase = true) &&
        port == other.port
