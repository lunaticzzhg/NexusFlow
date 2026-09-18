package com.nexusflow.backend.core.external

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException

fun HttpResponse.rejectKnownExternalSourceStatus(
    provider: String,
    operation: String,
) {
    when (status) {
        HttpStatusCode.Unauthorized,
        HttpStatusCode.Forbidden,
        -> throw ExternalSourceUnauthorizedException(provider, operation)

        HttpStatusCode.TooManyRequests -> throw ExternalSourceRateLimitedException(provider, operation)
        in HttpStatusCode.InternalServerError..HttpStatusCode.GatewayTimeout ->
            throw ExternalSourceUnavailableException(provider, operation)
        else -> if (status.value !in 200..299) throw ExternalSourceUnavailableException(provider, operation)
    }
}

suspend fun <T> executeExternalSourceRequest(
    provider: String,
    operation: String,
    block: suspend () -> T,
): T =
    try {
        block()
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: HttpRequestTimeoutException) {
        throw ExternalSourceTimeoutException(provider, operation, cause)
    } catch (cause: ConnectTimeoutException) {
        throw ExternalSourceTimeoutException(provider, operation, cause)
    } catch (cause: SocketTimeoutException) {
        throw ExternalSourceTimeoutException(provider, operation, cause)
    } catch (cause: ContentConvertException) {
        throw ExternalSourceInvalidPayloadException(provider, operation, cause)
    } catch (cause: SerializationException) {
        throw ExternalSourceInvalidPayloadException(provider, operation, cause)
    } catch (cause: IllegalArgumentException) {
        throw ExternalSourceInvalidPayloadException(provider, operation, cause)
    } catch (cause: IOException) {
        throw ExternalSourceUnavailableException(provider, operation, cause)
    }
