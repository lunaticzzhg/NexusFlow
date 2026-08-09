package com.nexusflow.app.core.error

/** Stable failures that may cross the shared network boundary. */
sealed class AppException(
    cause: Throwable? = null,
) : IllegalStateException(cause) {
    class Unauthorized : AppException()

    class Forbidden : AppException()

    class NotFound : AppException()

    class Conflict : AppException()

    class RateLimited : AppException()

    class Rejected(
        val code: Int,
    ) : AppException()

    class Unavailable(
        cause: Throwable? = null,
    ) : AppException(cause)

    class InvalidResponse(
        cause: Throwable? = null,
    ) : AppException(cause)
}
