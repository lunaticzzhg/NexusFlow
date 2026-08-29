package com.nexusflow.backend.feature.task.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskStateTest {
    @Test
    fun `M0 allows only the executable task transitions from the work order`() {
        assertTrue(TaskState.Draft.canTransitionTo(TaskState.CollectingConstraints))
        assertTrue(TaskState.Draft.canTransitionTo(TaskState.Planning))
        assertTrue(TaskState.CollectingConstraints.canTransitionTo(TaskState.CollectingConstraints))
        assertTrue(TaskState.CollectingConstraints.canTransitionTo(TaskState.Planning))
        assertTrue(TaskState.Planning.canTransitionTo(TaskState.CollectingConstraints))
        assertTrue(TaskState.Planning.canTransitionTo(TaskState.WaitingForApproval))

        assertFalse(TaskState.Draft.canTransitionTo(TaskState.Executing))
        assertFalse(TaskState.WaitingForApproval.canTransitionTo(TaskState.Planning))
        assertFalse(TaskState.Executing.canTransitionTo(TaskState.Completed))
        assertFalse(TaskState.NeedsAttention.canTransitionTo(TaskState.Executing))
        assertFalse(TaskState.Completed.canTransitionTo(TaskState.Cancelled))
        assertFalse(TaskState.Cancelled.canTransitionTo(TaskState.Draft))
    }
}
