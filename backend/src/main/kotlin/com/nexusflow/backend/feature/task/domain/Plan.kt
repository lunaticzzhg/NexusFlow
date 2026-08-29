package com.nexusflow.backend.feature.task.domain

import java.time.Instant

data class PlanningRun(
    val id: PlanningRunId,
    val taskId: TaskId,
    val clientRequestId: String,
    val taskVersion: Long,
    val createdAt: Instant,
)

data class Plan(
    val id: PlanId,
    val taskId: TaskId,
    val planningRunId: PlanningRunId,
    val direction: String,
    val title: String,
    val summary: String,
    val timeline: List<PlanTimelineItem>,
    val estimatedCost: PlanEstimatedCost?,
    val commuteMinutes: Int?,
    val satisfiedConstraintIds: List<ConstraintId>,
    val tradeoffs: List<String>,
    val reasons: List<String>,
    val sourceRefs: List<PlanSourceRef>,
    val validUntil: Instant?,
    val createdAt: Instant,
)

data class PlanTimelineItem(
    val title: String,
    val startAt: Instant?,
    val endAt: Instant?,
    val location: String?,
)

data class PlanEstimatedCost(
    val wholeUnits: Long,
    val currencyCode: String?,
)

data class PlanSourceRef(
    val label: String,
    val uri: String?,
)
