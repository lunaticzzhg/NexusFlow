package com.nexusflow.app

import androidx.compose.ui.window.ComposeUIViewController
import com.nexusflow.app.app.initKoin
import com.nexusflow.app.core.config.platformRuntimeConfig
import com.nexusflow.app.core.network.platformHttpClient
import com.nexusflow.app.core.security.IosKeychainExecutor
import com.nexusflow.app.core.security.IosSecureStore
import com.nexusflow.app.core.systemui.IosSystemUiGateway
import com.nexusflow.app.core.time.platformAppClock
import com.nexusflow.app.feature.auth.presentation.AuthSessionController
import platform.UIKit.UIViewController

fun mainViewController(
    systemUiGateway: IosSystemUiGateway,
    keychainExecutor: IosKeychainExecutor,
): UIViewController {
    val koinApplication =
        initKoin(
            runtimeConfig = platformRuntimeConfig(),
            secureStore = IosSecureStore(keychainExecutor),
            appClock = platformAppClock(),
            httpClient = platformHttpClient(),
        )
    val authSessionController = koinApplication.koin.get<AuthSessionController>()
    return ComposeUIViewController {
        AppRoot(
            authSessionController = authSessionController,
            systemUiGateway = systemUiGateway,
        )
    }
}
