package com.nexusflow.ai.planner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlannerTest {
    @Test
    fun `stub provider produces an approval gated structured proposal`() {
        val result = Planner().plan(
            PlanningContext(
                taskId = "task-1",
                request = "周末想看利物浦比赛",
                budget = TaskBudget(300),
                opportunities = listOf(
                    OpportunitySnapshot(
                        id = "match-1",
                        title = "利物浦 vs 阿森纳",
                        category = OpportunityCategory.SPORT,
                        estimatedCost = 200,
                        sourceName = "fixture",
                    ),
                ),
            ),
        )

        val accepted = assertIs<PlanningResult.Accepted>(result)
        assertTrue(accepted.proposal.requiresApproval)
        assertEquals("match-1", accepted.proposal.options.single().referencedOpportunityIds.single())
    }

    @Test
    fun `policy rejects a model proposal that exceeds the task budget`() {
        val expensiveProvider = ModelProvider {
            PlanProposal(
                title = "昂贵计划",
                rationale = listOf("test"),
                requiresApproval = true,
                options = listOf(PlanOption("a", "A", "summary", estimatedCost = 301)),
            )
        }
        val result = Planner(expensiveProvider).plan(
            PlanningContext(taskId = "task-1", request = "安排", budget = TaskBudget(300)),
        )

        val rejected = assertIs<PlanningResult.Rejected>(result)
        assertTrue(rejected.violations.any { it.code == PolicyViolationCode.BUDGET_EXCEEDED })
    }

    @Test
    fun `policy rejects write actions without approval`() {
        val unsafeProvider = ModelProvider {
            PlanProposal(
                title = "不安全计划",
                rationale = listOf("test"),
                requiresApproval = false,
                options = listOf(
                    PlanOption(
                        id = "a",
                        title = "A",
                        summary = "summary",
                        requestedActions = listOf(RequestedAction(RequestedActionType.CREATE_REMINDER, "提醒")),
                    ),
                ),
                riskLabels = setOf(RiskLabel.EXTERNAL_WRITE),
            )
        }
        val result = Planner(unsafeProvider).plan(PlanningContext(taskId = "task-1", request = "安排"))

        val rejected = assertIs<PlanningResult.Rejected>(result)
        assertTrue(rejected.violations.any { it.code == PolicyViolationCode.APPROVAL_REQUIRED })
    }
}
