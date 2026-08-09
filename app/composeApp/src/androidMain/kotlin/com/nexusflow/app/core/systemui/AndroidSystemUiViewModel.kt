package com.nexusflow.app.core.systemui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Activity-scoped owner for foreground system UI requests.
 *
 * It intentionally has no Activity reference; [com.nexusflow.app.MainActivity] observes requests
 * and delegates execution to its Activity-owned [AndroidSystemUiHost].
 */
internal class AndroidSystemUiViewModel :
    ViewModel(),
    SystemUiGateway,
    SystemUiTaskSource {
    private val gateway = DefaultSystemUiGateway()

    override val requests: Flow<GoogleSignInRequest> = gateway.requests
    override val cancellations: Flow<SystemUiRequestId> = gateway.cancellations

    override suspend fun requestGoogleSignIn(request: GoogleSignInRequest): GoogleSignInResult {
        return gateway.requestGoogleSignIn(request)
    }

    override suspend fun isActive(id: SystemUiRequestId): Boolean = gateway.isActive(id)

    override suspend fun complete(result: GoogleSignInResult): Boolean = gateway.complete(result)

    override suspend fun cancelActive() = gateway.cancelActive()

    fun cancelForHostDetach() {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { cancelActive() }
    }

    override fun onCleared() {
        cancelForHostDetach()
    }
}
