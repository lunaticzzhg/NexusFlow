package com.nexusflow.backend.core.external

import com.nexusflow.backend.core.config.ExternalSourcesRuntimeConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.headers
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class ExternalSourceHttpClient(
    val client: HttpClient,
) : AutoCloseable {
    override fun close() {
        client.close()
    }

    companion object {
        fun create(config: ExternalSourcesRuntimeConfig): ExternalSourceHttpClient =
            ExternalSourceHttpClient(
                HttpClient(CIO) {
                    install(HttpTimeout) {
                        val timeoutMillis = config.requestTimeout.toMillis()
                        requestTimeoutMillis = timeoutMillis
                        connectTimeoutMillis = timeoutMillis
                        socketTimeoutMillis = timeoutMillis
                    }
                    install(ContentNegotiation) {
                        json(
                            Json {
                                ignoreUnknownKeys = true
                                explicitNulls = false
                            },
                        )
                    }
                    defaultRequest {
                        headers {
                            append(HttpHeaders.UserAgent, config.userAgent)
                        }
                    }
                },
            )
    }
}
