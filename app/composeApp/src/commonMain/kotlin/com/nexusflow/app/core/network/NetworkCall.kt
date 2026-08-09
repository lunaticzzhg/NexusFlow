package com.nexusflow.app.core.network

import com.nexusflow.app.core.error.AppException
import com.nexusflow.app.core.observability.AppLogger
import com.nexusflow.app.core.observability.LogTag
import com.nexusflow.app.core.observability.logFields
import com.nexusflow.contracts.api.KResponse
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.serialization.ContentConvertException
import kotlinx.coroutines.CancellationException
import kotlinx.io.IOException
import kotlinx.serialization.SerializationException

/** A transport-level failure normalized from a non-successful HTTP response. */
internal sealed interface HttpFailure {
    data object Unauthorized : HttpFailure

    data object Forbidden : HttpFailure

    data object NotFound : HttpFailure

    data object Conflict : HttpFailure

    data object RateLimited : HttpFailure

    data class ClientError(
        val statusCode: Int,
    ) : HttpFailure

    data class ServerError(
        val statusCode: Int,
    ) : HttpFailure
}

/** Thrown by the shared first-party status interceptor before Ktorfit converts a response body. */
internal class HttpFailureException(
    val failure: HttpFailure,
    val diagnostics: HttpFailureDiagnostics? = null,
) : Exception()

internal data class HttpFailureDiagnostics(
    val code: Int? = null,
    val message: String? = null,
)

/** Normalizes first-party response failures and emits the sole API failure event. */
internal class ApiCallExecutor(
    private val logger: AppLogger,
) {
    suspend fun <T> execute(
        endpoint: String,
        call: suspend () -> KResponse<T>,
    ): Result<T> =
        executeResponse(endpoint, call) { response ->
            response.data?.let(Result.Companion::success) ?: Result.failure(AppException.InvalidResponse())
        }

    suspend fun executeUnit(
        endpoint: String,
        call: suspend () -> KResponse<Unit>,
    ): Result<Unit> = executeResponse(endpoint, call) { Result.success(Unit) }

    private suspend fun <T, R> executeResponse(
        endpoint: String,
        call: suspend () -> KResponse<T>,
        success: (KResponse<T>) -> Result<R>,
    ): Result<R> {
        return try {
            val response = call()
            if (response.code != HTTP_OK) {
                return apiFailure(
                    endpoint = endpoint,
                    diagnostics = response.toFailureDiagnostics(),
                    fallbackMessage = REQUEST_REJECTED_MESSAGE,
                    exception = response.code.toHttpFailure().toAppException(),
                )
            }
            success(response).also { result ->
                if (result.isFailure) {
                    logApiFailure(
                        endpoint = endpoint,
                        diagnostics = response.toFailureDiagnostics(),
                        fallbackMessage = INVALID_RESPONSE_MESSAGE,
                    )
                }
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: HttpFailureException) {
            apiFailure(
                endpoint = endpoint,
                diagnostics = cause.diagnostics ?: HttpFailureDiagnostics(code = cause.failure.statusCode()),
                fallbackMessage = cause.failure.fallbackMessage(),
                exception = cause.failure.toAppException(),
            )
        } catch (cause: HttpRequestTimeoutException) {
            apiFailure(
                endpoint = endpoint,
                diagnostics = null,
                fallbackMessage = REQUEST_TIMEOUT_MESSAGE,
                exception = AppException.Unavailable(cause),
                cause = cause,
            )
        } catch (cause: IOException) {
            apiFailure(
                endpoint = endpoint,
                diagnostics = null,
                fallbackMessage = NETWORK_UNAVAILABLE_MESSAGE,
                exception = AppException.Unavailable(cause),
                cause = cause,
            )
        } catch (cause: ContentConvertException) {
            apiFailure(
                endpoint = endpoint,
                diagnostics = null,
                fallbackMessage = INVALID_RESPONSE_MESSAGE,
                exception = AppException.InvalidResponse(cause),
                cause = cause,
            )
        } catch (cause: SerializationException) {
            apiFailure(
                endpoint = endpoint,
                diagnostics = null,
                fallbackMessage = INVALID_RESPONSE_MESSAGE,
                exception = AppException.InvalidResponse(cause),
                cause = cause,
            )
        }
    }

    private fun <T> apiFailure(
        endpoint: String,
        diagnostics: HttpFailureDiagnostics?,
        fallbackMessage: String,
        exception: AppException,
        cause: Throwable? = null,
    ): Result<T> {
        logApiFailure(endpoint, diagnostics, fallbackMessage, cause)
        return Result.failure(exception)
    }

    private fun logApiFailure(
        endpoint: String,
        diagnostics: HttpFailureDiagnostics?,
        fallbackMessage: String,
        cause: Throwable? = null,
    ) {
        logger.error(
            tag = ApiLogTag,
            event = "api_request_failed",
            fields =
                logFields {
                    "api_path" value endpoint.toApiPath()
                    "code" value diagnostics?.code
                    "message" value (diagnostics?.message ?: fallbackMessage)
                },
            cause = cause,
        )
    }
}

private fun String.toApiPath(): String = if (startsWith('/')) this else "/$this"

private fun KResponse<*>.toFailureDiagnostics(): HttpFailureDiagnostics =
    HttpFailureDiagnostics(code = code, message = message?.takeUnless(String::isEmpty))

private val ApiLogTag = LogTag.of("API")

private fun HttpFailure.toAppException(): AppException =
    when (this) {
        HttpFailure.Unauthorized -> AppException.Unauthorized()
        HttpFailure.Forbidden -> AppException.Forbidden()
        HttpFailure.NotFound -> AppException.NotFound()
        HttpFailure.Conflict -> AppException.Conflict()
        HttpFailure.RateLimited -> AppException.RateLimited()
        is HttpFailure.ClientError -> AppException.Rejected(statusCode)
        is HttpFailure.ServerError -> AppException.Unavailable()
    }

private fun HttpFailure.statusCode(): Int =
    when (this) {
        HttpFailure.Unauthorized -> 401
        HttpFailure.Forbidden -> 403
        HttpFailure.NotFound -> 404
        HttpFailure.Conflict -> 409
        HttpFailure.RateLimited -> 429
        is HttpFailure.ClientError -> statusCode
        is HttpFailure.ServerError -> statusCode
    }

private fun HttpFailure.fallbackMessage(): String =
    when (this) {
        HttpFailure.Unauthorized -> "Unauthorized"
        HttpFailure.Forbidden -> "Forbidden"
        HttpFailure.NotFound -> "Not found"
        HttpFailure.Conflict -> "Conflict"
        HttpFailure.RateLimited -> "Rate limited"
        is HttpFailure.ClientError -> "Client error"
        is HttpFailure.ServerError -> "Server error"
    }

internal fun Int.toHttpFailure(): HttpFailure =
    when (this) {
        401 -> HttpFailure.Unauthorized
        403 -> HttpFailure.Forbidden
        404 -> HttpFailure.NotFound
        409 -> HttpFailure.Conflict
        429 -> HttpFailure.RateLimited
        in 500..599 -> HttpFailure.ServerError(this)
        else -> HttpFailure.ClientError(this)
    }

private const val HTTP_OK = 200
private const val INVALID_RESPONSE_MESSAGE = "Invalid response"
private const val NETWORK_UNAVAILABLE_MESSAGE = "Network unavailable"
private const val REQUEST_REJECTED_MESSAGE = "Request rejected"
private const val REQUEST_TIMEOUT_MESSAGE = "Request timeout"
