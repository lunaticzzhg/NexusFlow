package com.nexusflow.app.feature.task.presentation.detail

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal sealed interface TaskTranscriptScrollCommand {
    data object None : TaskTranscriptScrollCommand

    data object FollowLatest : TaskTranscriptScrollCommand
}

@Stable
internal class TaskTranscriptScrollState {
    val listState = LazyListState()
    var autoFollowEnabled: Boolean by mutableStateOf(true)
        private set
    private var lastTailKey: String? = null
    private var lastFollowSignal: TaskTranscriptFollowSignal? = null

    fun nextCommand(
        items: List<TaskTranscriptItem>,
        followSignal: TaskTranscriptFollowSignal?,
    ): TaskTranscriptScrollCommand {
        val tailKey = items.lastOrNull(TaskTranscriptItem::isContentAnchor)?.key
        val tailChanged = tailKey != lastTailKey
        val activeResponseAdvanced = followSignal != null && followSignal != lastFollowSignal
        lastTailKey = tailKey
        lastFollowSignal = followSignal
        return if (autoFollowEnabled && items.isNotEmpty() && (tailChanged || activeResponseAdvanced)) {
            TaskTranscriptScrollCommand.FollowLatest
        } else {
            TaskTranscriptScrollCommand.None
        }
    }

    fun pauseAutoFollow() {
        autoFollowEnabled = false
    }

    fun resumeAutoFollow() {
        autoFollowEnabled = true
    }
}

private fun TaskTranscriptItem.isContentAnchor(): Boolean =
    when (this) {
        is TaskTranscriptItem.ActiveResponse,
        is TaskTranscriptItem.FailedMessage,
        is TaskTranscriptItem.Message,
        is TaskTranscriptItem.PendingMessage,
        is TaskTranscriptItem.Planning,
        is TaskTranscriptItem.PlanningNotice,
        -> true
        is TaskTranscriptItem.OperationFailure -> false
    }
