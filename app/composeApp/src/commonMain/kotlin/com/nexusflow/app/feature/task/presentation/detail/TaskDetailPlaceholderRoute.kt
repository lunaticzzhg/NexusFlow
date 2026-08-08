@file:Suppress("FunctionName", "ktlint:standard:function-naming")

package com.nexusflow.app.feature.task.presentation.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nexusflow.app.core.design.AppSpacing
import nexusflow.app.composeapp.generated.resources.Res
import nexusflow.app.composeapp.generated.resources.task_detail_back_home
import nexusflow.app.composeapp.generated.resources.task_detail_created
import nexusflow.app.composeapp.generated.resources.task_detail_id
import nexusflow.app.composeapp.generated.resources.task_detail_title
import org.jetbrains.compose.resources.stringResource

@Composable
fun TaskDetailPlaceholderRoute(
    taskId: String,
    title: String,
    onBackHome: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(AppSpacing.page),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.large),
    ) {
        Text(stringResource(Res.string.task_detail_created), style = MaterialTheme.typography.labelMedium)
        Text(stringResource(Res.string.task_detail_title), style = MaterialTheme.typography.displaySmall)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(
            text = stringResource(Res.string.task_detail_id, taskId),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Button(onClick = onBackHome, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.task_detail_back_home))
        }
    }
}
