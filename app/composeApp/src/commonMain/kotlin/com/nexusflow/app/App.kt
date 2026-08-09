package com.nexusflow.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nexusflow.app.core.design.AppSpacing
import com.nexusflow.app.core.design.AppTheme
import com.nexusflow.app.core.design.feedback.AppToastHost
import com.nexusflow.app.core.design.feedback.LocalAppToast
import com.nexusflow.app.core.design.feedback.rememberAppToastHostState
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
        val toastHostState = rememberAppToastHostState()
        CompositionLocalProvider(LocalAppToast provides toastHostState) {
            Box(modifier = Modifier.fillMaxSize()) {
                AuthGate(
                    controller = authSessionController,
                    systemUiGateway = systemUiGateway,
                )
                AppToastHost(
                    state = toastHostState,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(
                                start = AppSpacing.page,
                                end = AppSpacing.page,
                                bottom = AppBottomNavigationHeight,
                            ),
                )
            }
        }
    }
}

private val AppBottomNavigationHeight = 80.dp
