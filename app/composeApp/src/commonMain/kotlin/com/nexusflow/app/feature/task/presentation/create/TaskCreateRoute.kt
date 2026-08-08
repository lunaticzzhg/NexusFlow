@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package com.nexusflow.app.feature.task.presentation.create

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.nexusflow.app.core.design.AppSpacing
import nexusflow.app.composeapp.generated.resources.Res
import nexusflow.app.composeapp.generated.resources.task_create_back_home
import nexusflow.app.composeapp.generated.resources.task_create_description
import nexusflow.app.composeapp.generated.resources.task_create_failed
import nexusflow.app.composeapp.generated.resources.task_create_hint
import nexusflow.app.composeapp.generated.resources.task_create_label
import nexusflow.app.composeapp.generated.resources.task_create_submit
import nexusflow.app.composeapp.generated.resources.task_create_submitting
import nexusflow.app.composeapp.generated.resources.task_create_title
import nexusflow.app.composeapp.generated.resources.task_retry
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun TaskCreateRoute(
    onBackHome: () -> Unit,
    onOpenTask: (String, String) -> Unit,
    viewModel: TaskCreateViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is TaskCreateEffect.OpenTask -> onOpenTask(effect.taskId.value, effect.title)
            }
        }
    }
    TaskCreateContent(
        state = state,
        onBackHome = onBackHome,
        onRequestChanged = { viewModel.dispatch(TaskCreateIntent.RequestChanged(it)) },
        onSubmit = { viewModel.dispatch(TaskCreateIntent.Submit) },
        onRetry = { viewModel.dispatch(TaskCreateIntent.RetrySubmit) },
    )
}

@Composable
fun TaskCreateContent(
    state: TaskCreateUiState,
    onBackHome: () -> Unit,
    onRequestChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(AppSpacing.page),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.large),
    ) {
        TextButton(onClick = onBackHome) {
            Text(stringResource(Res.string.task_create_back_home))
        }
        Text(stringResource(Res.string.task_create_title), style = MaterialTheme.typography.displaySmall)
        Text(
            text = stringResource(Res.string.task_create_description),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = state.requestText,
            onValueChange = onRequestChanged,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.submission !is TaskSubmission.Submitting,
            label = { Text(stringResource(Res.string.task_create_label)) },
            placeholder = { Text(stringResource(Res.string.task_create_hint)) },
            minLines = 4,
        )
        if (state.submission is TaskSubmission.Failed) {
            Text(
                text = stringResource(Res.string.task_create_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth(),
            enabled = state.canSubmit,
        ) {
            if (state.submission is TaskSubmission.Submitting) {
                CircularProgressIndicator()
                Text(stringResource(Res.string.task_create_submitting))
            } else {
                Text(stringResource(Res.string.task_create_submit))
            }
        }
        if (state.submission is TaskSubmission.Failed) {
            Button(
                onClick = onRetry,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(Res.string.task_retry))
            }
        }
    }
}
