package com.nexusflow.app

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowInsetsControllerCompat
import com.nexusflow.app.core.systemui.AndroidSystemUiGateway
import com.nexusflow.app.feature.auth.presentation.AuthSessionController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep edge-to-edge content while matching status-bar icon contrast to the system theme.
        val isNightMode =
            resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = !isNightMode
        val authSessionController = (application as App).koinApplication.koin.get<AuthSessionController>()
        val systemUiGateway = AndroidSystemUiGateway(this)
        setContent {
            AppRoot(
                authSessionController = authSessionController,
                systemUiGateway = systemUiGateway,
            )
        }
    }
}
