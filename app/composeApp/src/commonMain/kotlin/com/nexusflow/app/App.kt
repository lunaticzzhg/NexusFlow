package com.nexusflow.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.nexusflow.app.core.design.AppTheme
import com.nexusflow.app.core.systemui.SystemUiGateway
import com.nexusflow.app.feature.auth.presentation.AuthGate
import com.nexusflow.app.feature.auth.presentation.AuthSessionController

@Composable
@Suppress("FunctionNaming", "ktlint:standard:function-naming")
fun AppRoot(
    authSessionController: AuthSessionController,
    systemUiGateway: SystemUiGateway,
) {
    AppTheme {
        LaunchedEffect(authSessionController) {
            authSessionController.restore()
        }
        AuthGate(
            controller = authSessionController,
            systemUiGateway = systemUiGateway,
        )
    }
}
