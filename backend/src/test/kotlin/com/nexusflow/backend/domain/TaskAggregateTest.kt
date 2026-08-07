package com.nexusflow.backend.domain

import com.nexusflow.contracts.api.CreateTaskRequest
import com.nexusflow.contracts.planning.ActionRequest
import com.nexusflow.contracts.planning.ActionType
import com.nexusflow.contracts.planning.ModelRunMetadata
import com.nexusflow.contracts.planning.OpportunityDomain
import com.nexusflow.contracts.planning.PlanItem
import com.nexusflow.contracts.planning.PlanOption
import com.nexusflow.contracts.planning.PlanProposal
import com.nexusflow.contracts.task.TaskStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TaskAggregateTest {
    @Test
    fun `action bearing proposal cannot complete validation without approval`() {
        val now = Instant.parse("2026-08-07T00:00:00Z")
        val proposal = PlanProposal(
            taskId = "task-1",
            title = "plan",
            summary = "summary",
            generatedAt = now,
            modelRun = ModelRunMetadata("stub", "stub", "v1"),
            options = listOf(
                PlanOption(
                    optionId = "option-1",
                    rank = 1,
                    title = "option",
                    summary = "summary",
                    rationale = listOf("reason"),
                    items = listOf(PlanItem("item-1", "match", OpportunityDomain.SPORTS, now, now)),
                    actionRequests = listOf(ActionRequest("action-1", ActionType.CREATE_REMINDER, "create reminder")),
                ),
            ),
        )
        val task = TaskAggregate(
            id = "task-1",
            tenantId = "tenant-1",
            ownerUserId = "user-1",
            request = CreateTaskRequest("安排", "Asia/Shanghai"),
            status = TaskStatus.VALIDATING,
            version = 4,
            createdAt = now,
            updatedAt = now,
            idempotencyKey = "key",
            requestFingerprint = "fingerprint",
            proposal = proposal,
        )

        assertFailsWith<IllegalArgumentException> { task.transitionTo(TaskStatus.COMPLETED, now) }
        assertEquals(TaskStatus.AWAITING_APPROVAL, task.afterValidation(now).status)
    }
}
