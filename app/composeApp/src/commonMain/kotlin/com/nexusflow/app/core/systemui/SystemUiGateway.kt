package com.nexusflow.app.core.systemui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SystemUiRequestId(val value: String)

data class GoogleSignInRequest(
    val id: SystemUiRequestId,
    val serverClientId: String,
)

sealed interface GoogleSignInResult {
    val id: SystemUiRequestId

    data class Success(
        override val id: SystemUiRequestId,
        val idToken: String,
    ) : GoogleSignInResult

    data class Cancelled(
        override val id: SystemUiRequestId,
    ) : GoogleSignInResult

    data class Unavailable(
        override val id: SystemUiRequestId,
    ) : GoogleSignInResult

    data class Failed(
        override val id: SystemUiRequestId,
    ) : GoogleSignInResult
}

/** A window-scoped bridge for one foreground system UI operation at a time. */
interface SystemUiGateway {
    suspend fun requestGoogleSignIn(request: GoogleSignInRequest): GoogleSignInResult
}

/** Platform-entry contract for executing and resolving requests from [SystemUiGateway]. */
interface SystemUiTaskSource {
    val requests: Flow<GoogleSignInRequest>

    /** Requests whose caller or host has gone away, so the matching native operation can stop. */
    val cancellations: Flow<SystemUiRequestId>

    /** True only while [id] still names the active request. */
    suspend fun isActive(id: SystemUiRequestId): Boolean

    /** Returns false for a stale or already-cancelled result. */
    suspend fun complete(result: GoogleSignInResult): Boolean

    /** Cancels the active request when its Activity or window detaches. */
    suspend fun cancelActive()
}

/**
 * Coordinates one foreground Google sign-in request for an Activity or window.
 *
 * A second request is rejected instead of queuing an operation whose Activity owner may no
 * longer exist. Android uses [SystemUiTaskSource] to execute the request; iOS retains its
 * existing UIKit continuation bridge.
 */
class DefaultSystemUiGateway :
    SystemUiGateway,
    SystemUiTaskSource {
    private val mutex = Mutex()
    private val requestChannel = Channel<GoogleSignInRequest>(Channel.BUFFERED)
    private val cancellationChannel = Channel<SystemUiRequestId>(Channel.BUFFERED)
    private var active: ActiveRequest? = null

    override val requests: Flow<GoogleSignInRequest> = requestChannel.receiveAsFlow()
    override val cancellations: Flow<SystemUiRequestId> = cancellationChannel.receiveAsFlow()

    override suspend fun isActive(id: SystemUiRequestId): Boolean = mutex.withLock { active?.id == id }

    override suspend fun requestGoogleSignIn(request: GoogleSignInRequest): GoogleSignInResult {
        val completion = CompletableDeferred<GoogleSignInResult>()
        val accepted =
            mutex.withLock {
                if (active != null) {
                    false
                } else {
                    active = ActiveRequest(request.id, completion)
                    true
                }
            }
        if (!accepted) return GoogleSignInResult.Unavailable(request.id)

        if (requestChannel.trySend(request).isFailure) {
            complete(GoogleSignInResult.Unavailable(request.id))
        }
        return try {
            completion.await()
        } finally {
            if (!completion.isCompleted) cancelRequest(request.id)
        }
    }

    override suspend fun complete(result: GoogleSignInResult): Boolean =
        mutex.withLock {
            val current = active ?: return@withLock false
            if (current.id != result.id) return@withLock false
            active = null
            current.completion.complete(result)
            true
        }

    override suspend fun cancelActive() {
        val id = mutex.withLock { active?.id } ?: return
        cancelRequest(id)
    }

    private suspend fun cancelRequest(id: SystemUiRequestId) {
        withContext(NonCancellable) {
            val cancelled =
                mutex.withLock {
                    val current = active ?: return@withLock null
                    if (current.id != id) return@withLock null
                    active = null
                    current.completion.complete(GoogleSignInResult.Cancelled(current.id))
                    current.id
                }
            if (cancelled != null) cancellationChannel.trySend(cancelled)
        }
    }

    private data class ActiveRequest(
        val id: SystemUiRequestId,
        val completion: CompletableDeferred<GoogleSignInResult>,
    )
}
