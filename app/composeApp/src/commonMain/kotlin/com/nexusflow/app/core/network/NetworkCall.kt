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

/** Normalizes first-party response failures and records only invalid response diagnostics. */
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
                    exception = response.code.toHttpFailure().toAppException(),
                )
            }
            success(response).also { result ->
                if (result.isFailure) {
                    logApiResponseInvalid(
                        endpoint = endpoint,
                        errorType = "missing_data",
                    )
                }
            }
        } catch (cause: CancellationException) {
            throw cause
        } catch (cause: HttpFailureException) {
            apiFailure(
                exception = cause.failure.toAppException(),
            )
        } catch (cause: HttpRequestTimeoutException) {
            apiFailure(
                exception = AppException.Unavailable(cause),
            )
        } catch (cause: IOException) {
            apiFailure(
                exception = AppException.Unavailable(cause),
            )
        } catch (cause: ContentConvertException) {
            logApiResponseInvalid(endpoint, "content_convert", cause)
            apiFailure(
                exception = AppException.InvalidResponse(cause),
            )
        } catch (cause: SerializationException) {
            logApiResponseInvalid(endpoint, "serialization", cause)
            apiFailure(
                exception = AppException.InvalidResponse(cause),
            )
        }
    }

    private fun <T> apiFailure(exception: AppException): Result<T> = Result.failure(exception)

    private fun logApiResponseInvalid(
        endpoint: String,
        errorType: String,
        cause: Throwable? = null,
    ) {
        logger.error(
            tag = ApiLogTag,
            event = "api_response_invalid",
            fields =
                logFields {
                    "api_path" value endpoint.toApiPath()
                    "error_type" value errorType
                },
            cause = cause,
        )
    }
}

private fun String.toApiPath(): String = if (startsWith('/')) this else "/$this"

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
