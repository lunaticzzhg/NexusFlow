package com.nexusflow.backend.feature.task

import com.nexusflow.backend.feature.task.application.TaskUnderstandingFailureEvent
import com.nexusflow.observability.LogFields
import com.nexusflow.observability.LogLevel
import com.nexusflow.observability.StructuredLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TaskDependenciesLoggingTest {
    @Test
    fun `understanding failure uses structured safe fields`() {
        val logger = RecordingStructuredLogger()

        logger.logTaskUnderstandingFailure(
            TaskUnderstandingFailureEvent(
                taskId = "task-123",
                taskRevision = 7,
                aiRequestId = "understand-123",
                failureType = "ProviderUnavailableException",
            ),
        )

        val entry = logger.entries.single()
        assertEquals(LogLevel.WARN, entry.level)
        assertEquals("task", entry.component)
        assertEquals("task_understanding_failed", entry.event)
        assertEquals("task-123", entry.fields.values["task_id"])
        assertEquals("7", entry.fields.values["task_revision"])
        assertEquals("understand-123", entry.fields.values["ai_request_id"])
        assertEquals("ProviderUnavailableException", entry.fields.values["failure_type"])

        val rendered = entry.fields.values.entries.joinToString("|") { (key, value) -> "$key=$value" }
        assertFalse(rendered.contains("prompt"))
        assertFalse(rendered.contains("Authorization"))
        assertFalse(rendered.contains("token="))
        assertFalse(rendered.contains("secret"))
    }

    private class RecordingStructuredLogger : StructuredLogger {
        val entries = mutableListOf<Entry>()

        override fun log(
            level: LogLevel,
            component: String,
            event: String,
            fields: LogFields,
            cause: Throwable?,
        ) {
            entries += Entry(level, component, event, fields)
        }
    }

    private data class Entry(
        val level: LogLevel,
        val component: String,
        val event: String,
        val fields: LogFields,
    )
}
