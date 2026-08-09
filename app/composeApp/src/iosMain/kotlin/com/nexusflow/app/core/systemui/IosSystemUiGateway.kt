package com.nexusflow.app.core.systemui

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** UIKit host callback surface for a single visible Google Sign-In request. */
interface IosGoogleSignInExecutor {
    fun requestGoogleSignIn(request: GoogleSignInRequest)

    fun cancelGoogleSignIn(requestId: SystemUiRequestId)
}

/**
 * Connects the shared authentication flow to one UIKit window.
 *
 * The UIKit host owns presentation and the Google SDK; this bridge owns the suspend continuation
 * and accepts only the result for the active request.
 */
class IosSystemUiGateway : SystemUiGateway {
    private var executor: IosGoogleSignInExecutor? = null
    private var pendingRequest: PendingRequest? = null

    fun attach(executor: IosGoogleSignInExecutor) {
        this.executor = executor
    }

    fun detach() {
        executor = null
        completePending(GoogleSignInResult.Cancelled(pendingRequest?.request?.id ?: return))
    }

    fun complete(result: GoogleSignInResult) {
        completePending(result)
    }

    override suspend fun requestGoogleSignIn(request: GoogleSignInRequest): GoogleSignInResult =
        suspendCancellableCoroutine { continuation ->
            val currentExecutor = executor
            if (pendingRequest != null || currentExecutor == null) {
                continuation.resume(GoogleSignInResult.Unavailable(request.id))
                return@suspendCancellableCoroutine
            }

            pendingRequest = PendingRequest(request, continuation)
            continuation.invokeOnCancellation {
                val pending = pendingRequest
                if (pending?.request?.id == request.id) {
                    pendingRequest = null
                    currentExecutor.cancelGoogleSignIn(request.id)
                }
            }
            currentExecutor.requestGoogleSignIn(request)
        }

    private fun completePending(result: GoogleSignInResult) {
        val pending = pendingRequest ?: return
        if (pending.request.id != result.id) return
        pendingRequest = null
        pending.continuation.resume(result)
    }

    private data class PendingRequest(
        val request: GoogleSignInRequest,
        val continuation: CancellableContinuation<GoogleSignInResult>,
    )
}
