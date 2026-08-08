package com.nexusflow.app.core.design.feedback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.nexusflow.app.core.design.AppSpacing
import kotlinx.coroutines.delay

sealed interface AppToast {
    val message: String
    val durationMillis: Long

    data class Success(
        override val message: String,
    ) : AppToast {
        override val durationMillis: Long = SUCCESS_DURATION_MILLIS
    }

    data class Error(
        override val message: String,
    ) : AppToast {
        override val durationMillis: Long = ERROR_DURATION_MILLIS
    }

    data class Action(
        override val message: String,
        val actionLabel: String,
        val onAction: () -> Unit,
    ) : AppToast {
        override val durationMillis: Long = ACTION_DURATION_MILLIS
    }

    private companion object {
        const val SUCCESS_DURATION_MILLIS = 3_000L
        const val ERROR_DURATION_MILLIS = 4_000L
        const val ACTION_DURATION_MILLIS = 5_000L
    }
}

private data class AppToastEntry(
    val id: Long,
    val toast: AppToast,
)

@Stable
class AppToastHostState {
    private var nextToastId = 0L
    private var currentEntry by mutableStateOf<AppToastEntry?>(null)

    val currentToast: AppToast?
        get() = currentEntry?.toast

    fun show(toast: AppToast) {
        currentEntry = AppToastEntry(id = nextToastId++, toast = toast)
    }

    fun dismiss() {
        currentEntry = null
    }

    internal fun dismiss(toastId: Long) {
        if (currentEntry?.id == toastId) {
            dismiss()
        }
    }

    internal fun currentToastId(): Long? = currentEntry?.id
}

@Composable
fun rememberAppToastHostState(): AppToastHostState = remember { AppToastHostState() }

val LocalAppToast =
    compositionLocalOf<AppToastHostState> {
        error("AppToastHostState is not available. Add AppToastHost at AppRoot.")
    }

@Composable
@Suppress("FunctionName", "ktlint:standard:function-naming")
fun AppToastHost(
    state: AppToastHostState,
    modifier: Modifier = Modifier,
) {
    val toast = state.currentToast
    val toastId = state.currentToastId()

    LaunchedEffect(toastId) {
        if (toastId != null && toast != null) {
            delay(toast.durationMillis)
            state.dismiss(toastId)
        }
    }

    if (toast != null && toastId != null) {
        Row(
            modifier =
                modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
                    .background(
                        color = MaterialTheme.colorScheme.inverseSurface,
                        shape = MaterialTheme.shapes.small,
                    ).semantics {
                        liveRegion = LiveRegionMode.Polite
                    }.padding(horizontal = AppSpacing.large, vertical = AppSpacing.medium),
            horizontalArrangement = Arrangement.spacedBy(AppSpacing.medium),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector =
                    when (toast) {
                        is AppToast.Error -> Icons.Outlined.ErrorOutline
                        is AppToast.Success,
                        is AppToast.Action,
                        -> Icons.Outlined.CheckCircle
                    },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.inverseOnSurface,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = toast.message,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (toast is AppToast.Action) {
                TextButton(
                    onClick = {
                        state.dismiss(toastId)
                        toast.onAction()
                    },
                ) {
                    Text(
                        text = toast.actionLabel,
                        color = MaterialTheme.colorScheme.inversePrimary,
                    )
                }
            }
        }
    }
}
