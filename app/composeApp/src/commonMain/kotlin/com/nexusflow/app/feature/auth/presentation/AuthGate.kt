@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package com.nexusflow.app.feature.auth.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nexusflow.app.app.AppShell
import com.nexusflow.app.core.config.BuildMode
import com.nexusflow.app.core.config.RuntimeConfig
import com.nexusflow.app.core.design.AppSpacing
import com.nexusflow.app.core.design.feedback.AppErrorState
import com.nexusflow.app.core.design.feedback.AppFullScreenLoading
import com.nexusflow.app.core.systemui.GoogleSignInRequest
import com.nexusflow.app.core.systemui.GoogleSignInResult
import com.nexusflow.app.core.systemui.SystemUiGateway
import kotlinx.coroutines.CancellationException
import nexusflow.app.composeapp.generated.resources.Res
import nexusflow.app.composeapp.generated.resources.auth_continue_with_google
import nexusflow.app.composeapp.generated.resources.auth_dev_email_label
import nexusflow.app.composeapp.generated.resources.auth_dev_invalid_credentials
import nexusflow.app.composeapp.generated.resources.auth_dev_password_label
import nexusflow.app.composeapp.generated.resources.auth_dev_submit
import nexusflow.app.composeapp.generated.resources.auth_dev_submitting
import nexusflow.app.composeapp.generated.resources.auth_dev_title
import nexusflow.app.composeapp.generated.resources.auth_login_description
import nexusflow.app.composeapp.generated.resources.auth_login_title
import nexusflow.app.composeapp.generated.resources.auth_retry
import nexusflow.app.composeapp.generated.resources.auth_unavailable_description
import nexusflow.app.composeapp.generated.resources.auth_unavailable_title
import org.jetbrains.compose.resources.stringResource

@Composable
@Suppress("FunctionName", "FunctionNaming", "ktlint:standard:function-naming")
fun AuthGate(
    controller: AuthSessionController,
    runtimeConfig: RuntimeConfig,
    systemUiGateway: SystemUiGateway,
) {
    val state by controller.state.collectAsState()

    LaunchedEffect(controller, systemUiGateway) {
        controller.effects.collect { effect ->
            when (effect) {
                is AuthEffect.RequestGoogleSignIn -> {
                    dispatchGoogleSignInResult(systemUiGateway, effect, controller::dispatch)
                }
            }
        }
    }

    when (val current = state) {
        AuthState.Restoring,
        AuthState.AuthenticatingGoogle,
        -> AppFullScreenLoading()
        is AuthState.Unauthenticated ->
            AuthLogin(
                state = current.login,
                showDevLogin = runtimeConfig.buildMode == BuildMode.DEBUG,
                onGoogleSignIn = { controller.dispatch(AuthIntent.StartGoogleSignIn) },
                onDevLoginEmailChanged = { controller.dispatch(AuthIntent.DevLoginEmailChanged(it)) },
                onDevLoginPasswordChanged = { controller.dispatch(AuthIntent.DevLoginPasswordChanged) },
                onDevLogin = { password -> controller.dispatch(AuthIntent.SubmitDevLogin(password)) },
            )
        is AuthState.Authenticated -> {
            key(current.context.contextId) {
                AppShell(
                    onLogout = { controller.dispatch(AuthIntent.Logout) },
                )
            }
        }
        AuthState.Unavailable -> AuthUnavailable(onRetry = { controller.dispatch(AuthIntent.Retry) })
    }
}

/** Completes the pending auth request before the Route's effect owner is cancelled. */
private suspend fun dispatchGoogleSignInResult(
    systemUiGateway: SystemUiGateway,
    effect: AuthEffect.RequestGoogleSignIn,
    dispatch: (AuthIntent) -> Unit,
) {
    try {
        val result =
            systemUiGateway.requestGoogleSignIn(
                GoogleSignInRequest(effect.requestId, effect.serverClientId),
            )
        dispatch(AuthIntent.GoogleSignInResolved(effect.requestId, result.toOutcome()))
    } catch (error: CancellationException) {
        dispatch(AuthIntent.GoogleSignInResolved(effect.requestId, GoogleSignInOutcome.Cancelled))
        throw error
    }
}

@Composable
private fun AuthLogin(
    state: AuthLoginUiState,
    showDevLogin: Boolean,
    onGoogleSignIn: () -> Unit,
    onDevLoginEmailChanged: (String) -> Unit,
    onDevLoginPasswordChanged: () -> Unit,
    onDevLogin: (String) -> Unit,
) {
    var devPassword by remember { mutableStateOf("") }
    AuthPage(
        title = stringResource(Res.string.auth_login_title),
        description = stringResource(Res.string.auth_login_description),
    ) {
        Button(onClick = onGoogleSignIn) {
            Text(stringResource(Res.string.auth_continue_with_google))
        }
        if (showDevLogin) {
            DevLoginForm(
                state = state,
                password = devPassword,
                onEmailChanged = onDevLoginEmailChanged,
                onPasswordChanged = {
                    devPassword = it
                    onDevLoginPasswordChanged()
                },
                onSubmit = { onDevLogin(devPassword) },
            )
        }
    }
}

@Composable
private fun DevLoginForm(
    state: AuthLoginUiState,
    password: String,
    onEmailChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.small),
    ) {
        Text(
            text = stringResource(Res.string.auth_dev_title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.devLoginEmail,
            onValueChange = onEmailChanged,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isDevLoginSubmitting,
            singleLine = true,
            label = { Text(stringResource(Res.string.auth_dev_email_label)) },
        )
        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChanged,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isDevLoginSubmitting,
            singleLine = true,
            label = { Text(stringResource(Res.string.auth_dev_password_label)) },
            visualTransformation = PasswordVisualTransformation(),
            isError = state.showInvalidDevCredential,
        )
        if (state.showInvalidDevCredential) {
            Text(
                text = stringResource(Res.string.auth_dev_invalid_credentials),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isDevLoginSubmitting && state.devLoginEmail.isNotBlank() && password.isNotBlank(),
        ) {
            if (state.isDevLoginSubmitting) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp))
                Text(stringResource(Res.string.auth_dev_submitting))
            } else {
                Text(stringResource(Res.string.auth_dev_submit))
            }
        }
    }
}

@Composable
private fun AuthUnavailable(onRetry: () -> Unit) {
    AppErrorState(
        title = stringResource(Res.string.auth_unavailable_title),
        description = stringResource(Res.string.auth_unavailable_description),
        actionLabel = stringResource(Res.string.auth_retry),
        onAction = onRetry,
        modifier = Modifier.fillMaxSize().padding(AppSpacing.page),
    )
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
