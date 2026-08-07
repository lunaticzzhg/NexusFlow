package com.nexusflow.contracts.task

import com.nexusflow.contracts.planning.ActionRequest
import com.nexusflow.contracts.planning.ActionType
import com.nexusflow.contracts.planning.ModelRunMetadata
import com.nexusflow.contracts.planning.OpportunityDomain
import com.nexusflow.contracts.planning.PlanItem
import com.nexusflow.contracts.planning.PlanOption
import com.nexusflow.contracts.planning.PlanProposal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskTransitionPolicyTest {
    @Test
    fun `a proposal with external actions must await approval`() {
        val proposal = proposalWith(
            ActionRequest(
                actionId = "calendar-1",
                type = ActionType.CREATE_CALENDAR_EVENT,
                summary = "Add the match to calendar",
            ),
        )

        assertEquals(TaskStatus.AWAITING_APPROVAL, TaskTransitionPolicy.afterValidation(proposal))
    }

    @Test
    fun `a purely advisory proposal can complete after validation`() {
        assertEquals(TaskStatus.COMPLETED, TaskTransitionPolicy.afterValidation(proposalWith()))
    }

    private fun proposalWith(vararg actions: ActionRequest): PlanProposal = PlanProposal(
        taskId = "task-1",
        title = "Weekend plan",
        summary = "Watch Liverpool",
        generatedAt = Instant.parse("2026-08-08T10:00:00Z"),
        modelRun = ModelRunMetadata("stub", "planner", "v1"),
        options = listOf(
            PlanOption(
                optionId = "option-1",
                rank = 1,
                title = "Plan A",
                summary = "A plan",
                items = listOf(
                    PlanItem(
                        itemId = "item-1",
                        title = "Liverpool match",
                        domain = OpportunityDomain.SPORTS,
                        startAt = Instant.parse("2026-08-08T12:00:00Z"),
                        endAt = Instant.parse("2026-08-08T14:00:00Z"),
                    ),
                ),
                rationale = listOf("Matches preference"),
                actionRequests = actions.toList(),
            ),
        ),
    )
}
