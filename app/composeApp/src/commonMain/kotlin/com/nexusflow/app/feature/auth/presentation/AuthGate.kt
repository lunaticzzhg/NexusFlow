@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package com.nexusflow.app.feature.auth.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.nexusflow.app.app.AppShell
import com.nexusflow.app.core.design.AppSpacing
import com.nexusflow.app.core.systemui.GoogleSignInRequest
import com.nexusflow.app.core.systemui.GoogleSignInResult
import com.nexusflow.app.core.systemui.SystemUiGateway
import nexusflow.app.composeapp.generated.resources.Res
import nexusflow.app.composeapp.generated.resources.auth_continue_with_google
import nexusflow.app.composeapp.generated.resources.auth_login_description
import nexusflow.app.composeapp.generated.resources.auth_login_title
import nexusflow.app.composeapp.generated.resources.auth_retry
import nexusflow.app.composeapp.generated.resources.auth_unavailable_description
import org.jetbrains.compose.resources.stringResource

@Composable
@Suppress("FunctionName", "FunctionNaming", "ktlint:standard:function-naming")
fun AuthGate(
    controller: AuthSessionController,
    systemUiGateway: SystemUiGateway,
) {
    val state by controller.state.collectAsState()

    LaunchedEffect(controller, systemUiGateway) {
        controller.effects.collect { effect ->
            when (effect) {
                is AuthEffect.RequestGoogleSignIn -> {
                    val result =
                        systemUiGateway.requestGoogleSignIn(
                            GoogleSignInRequest(effect.requestId, effect.serverClientId),
                        )
                    controller.dispatch(
                        AuthIntent.GoogleSignInResolved(
                            requestId = effect.requestId,
                            result = result.toOutcome(),
                        ),
                    )
                }
            }
        }
    }

    when (val current = state) {
        AuthState.Restoring,
        AuthState.AuthenticatingGoogle,
        -> AuthLoading()
        AuthState.Unauthenticated -> AuthLogin(onGoogleSignIn = { controller.dispatch(AuthIntent.StartGoogleSignIn) })
        is AuthState.Authenticated -> {
            key(current.context.contextId) {
                AppShell(onLogout = { controller.dispatch(AuthIntent.Logout) })
            }
        }
        AuthState.Unavailable -> AuthUnavailable(onRetry = { controller.dispatch(AuthIntent.Retry) })
    }
}

@Composable
private fun AuthLoading() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun AuthLogin(onGoogleSignIn: () -> Unit) {
    AuthPage(
        title = stringResource(Res.string.auth_login_title),
        description = stringResource(Res.string.auth_login_description),
    ) {
        Button(onClick = onGoogleSignIn) {
            Text(stringResource(Res.string.auth_continue_with_google))
        }
    }
}

@Composable
private fun AuthUnavailable(onRetry: () -> Unit) {
    AuthPage(
        title = stringResource(Res.string.auth_login_title),
        description = stringResource(Res.string.auth_unavailable_description),
    ) {
        Button(onClick = onRetry) {
            Text(stringResource(Res.string.auth_retry))
        }
    }
}

@Composable
private fun AuthPage(
    title: String,
    description: String,
    action: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(AppSpacing.page),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.medium, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = MaterialTheme.typography.headlineMedium)
        Text(text = description, style = MaterialTheme.typography.bodyLarge)
        action()
    }
}

private fun GoogleSignInResult.toOutcome(): GoogleSignInOutcome =
    when (this) {
        is GoogleSignInResult.Success -> GoogleSignInOutcome.Success(idToken)
        is GoogleSignInResult.Cancelled -> GoogleSignInOutcome.Cancelled
        is GoogleSignInResult.Unavailable -> GoogleSignInOutcome.Unavailable
        is GoogleSignInResult.Failed -> GoogleSignInOutcome.Failed
    }
