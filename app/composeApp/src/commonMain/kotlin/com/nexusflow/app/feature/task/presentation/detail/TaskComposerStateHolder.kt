package com.nexusflow.app.feature.task.presentation.detail

internal data class TaskComposerState(
    val draft: String = "",
    val pendingMessage: PendingTaskMessage? = null,
    val failedMessage: PendingTaskMessage? = null,
)

internal class TaskComposerStateHolder {
    var state: TaskComposerState = TaskComposerState()
        private set

    fun updateDraft(text: String) {
        state = state.copy(draft = text)
    }

    fun beginSend(
        clientMessageId: String,
        operation: TaskDetailOperation,
    ): PendingTaskMessage? {
        if (operation != TaskDetailOperation.Idle) return null
        val text = state.draft.trim()
        if (text.isBlank()) return null
        val pending = PendingTaskMessage(clientMessageId, text)
        state = state.copy(draft = "", pendingMessage = pending, failedMessage = null)
        return pending
    }

    fun beginRetry(operation: TaskDetailOperation): PendingTaskMessage? {
        if (operation != TaskDetailOperation.Idle) return null
        val pending = state.failedMessage ?: return null
        state = state.copy(pendingMessage = pending, failedMessage = null)
        return pending
    }

    fun finishAccepted(pending: PendingTaskMessage) {
        if (state.pendingMessage?.clientMessageId == pending.clientMessageId) {
            state = state.copy(pendingMessage = null)
        }
    }

    fun finishFailed(pending: PendingTaskMessage) {
        if (state.pendingMessage?.clientMessageId != pending.clientMessageId) return
        state = state.copy(pendingMessage = null, failedMessage = pending)
    }

    fun restoreFromProjection(content: TaskDetailContent.Success) {
        state =
            TaskComposerState(
                draft = content.draft,
                pendingMessage = content.pendingMessage,
                failedMessage = content.failedMessage,
            )
    }
}
