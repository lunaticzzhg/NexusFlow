package com.nexusflow.contracts.task

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class TaskLifecycleTest {
    @Test
    fun `allows the normal proposal and approval path`() {
        assertTrue(TaskLifecycle.canTransition(TaskStatus.QUEUED, TaskStatus.GATHERING_CONTEXT))
        assertTrue(TaskLifecycle.canTransition(TaskStatus.GATHERING_CONTEXT, TaskStatus.PLANNING))
        assertTrue(TaskLifecycle.canTransition(TaskStatus.PLANNING, TaskStatus.VALIDATING))
        assertTrue(TaskLifecycle.canTransition(TaskStatus.VALIDATING, TaskStatus.AWAITING_APPROVAL))
        assertTrue(TaskLifecycle.canTransition(TaskStatus.AWAITING_APPROVAL, TaskStatus.EXECUTING))
        assertTrue(TaskLifecycle.canTransition(TaskStatus.EXECUTING, TaskStatus.COMPLETED))
    }

    @Test
    fun `rejects execution before approval and terminal transitions`() {
        assertFalse(TaskLifecycle.canTransition(TaskStatus.PLANNING, TaskStatus.EXECUTING))
        assertFalse(TaskLifecycle.canTransition(TaskStatus.COMPLETED, TaskStatus.RETRYING))
        assertFailsWith<IllegalArgumentException> {
            TaskLifecycle.requireTransition(TaskStatus.AWAITING_APPROVAL, TaskStatus.COMPLETED)
        }
    }
}
