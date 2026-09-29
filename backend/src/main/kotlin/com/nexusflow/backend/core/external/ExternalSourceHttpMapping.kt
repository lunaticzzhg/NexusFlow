package com.nexusflow.backend.core.external

import com.nexusflow.observability.StructuredLogger
import com.nexusflow.observability.logFields
import io.ktor.client.call.NoTransformationFoundException
import io.ktor.client.call.body
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
        -> throw ExternalSourceUnauthorizedException(provider, operation, status.value, safeContentType())

        HttpStatusCode.TooManyRequests -> throw ExternalSourceRateLimitedException(provider, operation, safeContentType())
        in HttpStatusCode.InternalServerError..HttpStatusCode.GatewayTimeout ->
            throw ExternalSourceUnavailableException(provider, operation, httpStatus = status.value, contentType = safeContentType())
        else -> if (status.value !in 200..299) throw ExternalSourceUnavailableException(provider, operation, httpStatus = status.value, contentType = safeContentType())
    }
}

private class ExternalPayloadDecodeFailure(
    val httpStatus: Int,
    val responseContentType: String?,
    cause: Throwable,
) : RuntimeException(cause)

suspend inline fun <reified T> HttpResponse.externalBody(): T =
    try {
        body<T>()
    } catch (cause: NoTransformationFoundException) {
        throw externalPayloadDecodeFailure(cause)
    } catch (cause: ContentConvertException) {
        throw externalPayloadDecodeFailure(cause)
    } catch (cause: SerializationException) {
        throw externalPayloadDecodeFailure(cause)
    }

@PublishedApi
internal fun HttpResponse.externalPayloadDecodeFailure(cause: Throwable): RuntimeException =
    ExternalPayloadDecodeFailure(status.value, safeContentType(), cause)

private fun HttpResponse.safeContentType(): String? = contentType()?.let { "${it.contentType}/${it.contentSubtype}" }

suspend fun <T> executeExternalSourceRequest(
    logger: StructuredLogger?,
    provider: String,
    operation: String,
    block: suspend () -> T,
): T =
    try {
        block()
    } catch (cause: CancellationException) {
        throw cause
    } catch (cause: ExternalSourceException) {
        logger.logExternalFailure(cause)
        throw cause
    } catch (cause: ExternalPayloadDecodeFailure) {
        val failure = ExternalSourceInvalidPayloadException(provider, operation, cause.cause, cause.httpStatus, cause.responseContentType)
        logger.logExternalFailure(failure)
        throw failure
    } catch (cause: HttpRequestTimeoutException) {
        logger.raiseExternalFailure(ExternalSourceTimeoutException(provider, operation, cause))
    } catch (cause: ConnectTimeoutException) {
        logger.raiseExternalFailure(ExternalSourceTimeoutException(provider, operation, cause))
    } catch (cause: SocketTimeoutException) {
        logger.raiseExternalFailure(ExternalSourceTimeoutException(provider, operation, cause))
    } catch (cause: NoTransformationFoundException) {
        logger.raiseExternalFailure(ExternalSourceInvalidPayloadException(provider, operation, cause))
    } catch (cause: ContentConvertException) {
        logger.raiseExternalFailure(ExternalSourceInvalidPayloadException(provider, operation, cause))
    } catch (cause: SerializationException) {
        logger.raiseExternalFailure(ExternalSourceInvalidPayloadException(provider, operation, cause))
    } catch (cause: IllegalArgumentException) {
        logger.raiseExternalFailure(ExternalSourceInvalidPayloadException(provider, operation, cause))
    } catch (cause: IOException) {
        logger.raiseExternalFailure(ExternalSourceUnavailableException(provider, operation, cause))
    }

private fun StructuredLogger?.raiseExternalFailure(failure: ExternalSourceException): Nothing {
    logExternalFailure(failure)
    throw failure
}

private fun StructuredLogger?.logExternalFailure(failure: ExternalSourceException) {
    this?.warn(
        component = "external_source",
        event = "source_acquisition_failed",
        fields = logFields {
            "source_id" value failure.provider
            "operation" value failure.operation
            "failure_category" value failure.failureCategory
            "http_status" value failure.httpStatus
            "content_type" value failure.contentType
            "cause_class" value failure.cause?.javaClass?.simpleName
        },
    )
}
