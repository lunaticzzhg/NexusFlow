package com.nexusflow.app.feature.task.data

import com.nexusflow.app.feature.task.domain.MessageRole
import com.nexusflow.app.feature.task.domain.PlanDirection
import com.nexusflow.app.feature.task.domain.PlanEstimatedCost
import com.nexusflow.app.feature.task.domain.PlanId
import com.nexusflow.app.feature.task.domain.PlanSourceRef
import com.nexusflow.app.feature.task.domain.PlanTimelineItem
import com.nexusflow.app.feature.task.domain.PlanningState
import com.nexusflow.app.feature.task.domain.RequirementId
import com.nexusflow.app.feature.task.domain.RequirementKind
import com.nexusflow.app.feature.task.domain.RequirementSource
import com.nexusflow.app.feature.task.domain.RequirementStrength
import com.nexusflow.app.feature.task.domain.RequirementSummary
import com.nexusflow.app.feature.task.domain.RequirementValue
import com.nexusflow.app.feature.task.domain.TaskDetail
import com.nexusflow.app.feature.task.domain.TaskId
import com.nexusflow.app.feature.task.domain.TaskMessage
import com.nexusflow.app.feature.task.domain.TaskPlan
import com.nexusflow.app.feature.task.domain.TaskRequirement
import com.nexusflow.app.feature.task.domain.TaskSummary
import kotlinx.datetime.Instant

object TaskFixtures {
    private val timeRequirement =
        TaskRequirement(
            id = RequirementId("requirement-time"),
            kind = RequirementKind.TimeWindow,
            value = RequirementValue.TimeWindow("this weekend", "Europe/London"),
            strength = RequirementStrength.Must,
            source = RequirementSource.UserExplicit,
        )

    val success =
        listOf(
            TaskSummary(
                id = TaskId("task-liverpool-night"),
                intent = "Create a calendar event and a pre-match reminder",
                requirements =
                    listOf(
                        RequirementSummary(
                            id = timeRequirement.id,
                            label = "this weekend",
                            strength = RequirementStrength.Must,
                        ),
                    ),
                selectedPlanId = null,
            ),
        )

    val currentPlans =
        listOf(
            TaskPlan(
                id = PlanId("plan-m1-best"),
                revision = 1,
                direction = PlanDirection.BestMatch,
                title = "Anfield match night",
                summary = "Walk near Anfield, grab a simple dinner, then watch the match.",
                timeline =
                    listOf(
                        PlanTimelineItem(
                            title = "Anfield walk",
                            startAt = Instant.parse("2026-08-29T17:00:00Z"),
                            endAt = Instant.parse("2026-08-29T18:00:00Z"),
                            location = "Anfield",
                        ),
                    ),
                estimatedCost = PlanEstimatedCost(wholeUnits = 120, currencyCode = "GBP"),
                commuteMinutes = 20,
                requirementEvaluations = emptyList(),
                tradeoffs = listOf("Weather dependent"),
                reasons = listOf("Fits the requested match-night timing"),
                sourceRefs =
                    listOf(
                        PlanSourceRef(
                            label = "Controlled sports source",
                            sourceUpdatedAt = Instant.parse("2026-08-28T10:15:00Z"),
                            uri = null,
                        ),
                    ),
                opportunityRefs = listOf("sports-liverpool-1"),
                validUntil = Instant.parse("2026-08-29T16:00:00Z"),
            ),
        )

    val detail =
        TaskDetail(
            id = success.first().id,
            intent = success.first().intent,
            revision = 1,
            requirements = listOf(timeRequirement),
            messages = listOf(TaskMessage(MessageRole.User, "Watch Liverpool this weekend")),
            plans = currentPlans,
            selectedPlanId = null,
            planningState = PlanningState.Idle,
        )
}

enum class TaskSummaryFixture {
    Success,
    Empty,
    Failure,
}
