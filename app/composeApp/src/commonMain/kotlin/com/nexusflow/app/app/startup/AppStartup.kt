package com.nexusflow.app.app.startup

import com.nexusflow.app.feature.auth.presentation.AuthSessionController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.KoinApplication

/** Starts process-lifetime application work once after dependency injection is ready. */
class AppStartup internal constructor(
    private val sessionRestore: suspend () -> Unit,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    constructor(
        authSessionController: AuthSessionController,
        scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ) : this(
        sessionRestore = authSessionController::restore,
        scope = scope,
    )

    private val startMutex = Mutex()
    private var started = false

    /** Non-blocking and idempotent; UI recreation must not repeat session restoration. */
    fun start() {
        scope.launch {
            val shouldStart =
                startMutex.withLock {
                    if (started) {
                        false
                    } else {
                        started = true
                        true
                    }
                }
            if (shouldStart) {
                sessionRestore()
            }
        }
    }
}

/** Called by a platform process bootstrap once application services have initialized. */
fun startAppStartup(application: KoinApplication) {
    application.koin.get<AppStartup>().start()
}
