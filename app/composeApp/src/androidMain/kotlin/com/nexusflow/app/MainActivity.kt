package com.nexusflow.app

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.nexusflow.app.core.systemui.AndroidSystemUiHost
import com.nexusflow.app.core.systemui.AndroidSystemUiViewModel
import com.nexusflow.app.core.systemui.GoogleSignInResult
import com.nexusflow.app.core.systemui.SystemUiRequestId
import com.nexusflow.app.feature.auth.presentation.AuthSessionController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private lateinit var systemUiHost: AndroidSystemUiHost
    private val systemUiViewModel: AndroidSystemUiViewModel by viewModels()
    private var activeSystemUiRequestId: SystemUiRequestId? = null
    private var activeSystemUiJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep edge-to-edge content while matching status-bar icon contrast to the system theme.
        val isNightMode =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !isNightMode
        systemUiHost = AndroidSystemUiHost()
        systemUiHost.attach(this)
        collectSystemUiRequests()
        val authSessionController = (application as App).koinApplication.koin.get<AuthSessionController>()
        setContent {
            AppRoot(
                authSessionController = authSessionController,
                systemUiGateway = systemUiViewModel,
            )
        }
    }

    override fun onDestroy() {
        systemUiViewModel.cancelForHostDetach()
        activeSystemUiJob?.cancel()
        if (::systemUiHost.isInitialized) systemUiHost.detach()
        super.onDestroy()
    }

    private fun collectSystemUiRequests() {
        lifecycleScope.launch {
            launch {
                systemUiViewModel.requests.collect { request ->
                    if (!systemUiViewModel.isActive(request.id)) return@collect
                    if (activeSystemUiJob?.isActive == true) {
                        systemUiViewModel.complete(GoogleSignInResult.Unavailable(request.id))
                        return@collect
                    }
                    activeSystemUiRequestId = request.id
                    activeSystemUiJob =
                        launch {
                            try {
                                systemUiViewModel.complete(systemUiHost.execute(request))
                            } catch (error: CancellationException) {
                                withContext(NonCancellable) {
                                    systemUiViewModel.complete(GoogleSignInResult.Cancelled(request.id))
                                }
                                throw error
                            } catch (_: Throwable) {
                                systemUiViewModel.complete(GoogleSignInResult.Failed(request.id))
                            } finally {
                                if (activeSystemUiRequestId == request.id) {
                                    activeSystemUiRequestId = null
                                    activeSystemUiJob = null
                                }
                            }
                        }
                }
            }
            launch {
                systemUiViewModel.cancellations.collect { requestId ->
                    if (activeSystemUiRequestId == requestId) activeSystemUiJob?.cancel()
                }
            }
        }
    }
}
