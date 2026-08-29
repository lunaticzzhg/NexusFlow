package com.nexusflow.app.core.network

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
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
    installFirstPartyHttpInterceptors(apiBaseUrl) { null }
}

internal fun HttpClient.installFirstPartyHttpInterceptors(
    apiBaseUrl: String,
    sessionProvider: () -> FirstPartyApiSession?,
) {
    val apiOrigin = Url(apiBaseUrl)
    plugin(HttpSend).intercept { request ->
        val requestUrl = request.url.build()
        if (!requestUrl.hasSameOrigin(apiOrigin)) {
            return@intercept execute(request)
        }

        val isProtectedRequest = !requestUrl.isPublicAuthEndpoint()
        val provider = if (isProtectedRequest) sessionProvider() else null
        val firstToken = provider?.currentAccessToken()
        if (firstToken != null) {
            request.headers.remove(HttpHeaders.Authorization)
            request.headers.append(HttpHeaders.Authorization, "Bearer $firstToken")
        }

        val firstCall = execute(request)
        val finalCall =
            if (provider != null && firstToken != null && firstCall.response.status.value == HTTP_UNAUTHORIZED) {
                val replayToken =
                    when (val refresh = provider.refreshAccessTokenIfCurrent(firstToken)) {
                        is FirstPartySessionRefresh.TokenAvailable -> refresh.accessToken
                        FirstPartySessionRefresh.Unauthenticated,
                        FirstPartySessionRefresh.Unavailable,
                        -> null
                    }

                if (replayToken == null) {
                    firstCall
                } else {
                    request.headers.remove(HttpHeaders.Authorization)
                    request.headers.append(HttpHeaders.Authorization, "Bearer $replayToken")
                    execute(request).also { replayCall ->
                        if (replayCall.response.status.value == HTTP_UNAUTHORIZED) {
                            provider.clearSessionIfCurrent(replayToken)
                        }
                    }
                }
            } else {
                firstCall
            }

        if (finalCall.response.status.value !in 200..299) {
            throw HttpFailureException(
                failure = finalCall.response.status.value.toHttpFailure(),
                diagnostics = finalCall.response.bodyAsText().toHttpFailureDiagnostics(finalCall.response.status.value),
            )
        }
        finalCall
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

private fun Url.isPublicAuthEndpoint(): Boolean = encodedPath.removePrefix("/").startsWith("v1/auth/")

private const val HTTP_UNAUTHORIZED = 401
