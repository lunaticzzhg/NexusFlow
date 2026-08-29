package com.nexusflow.contracts.api

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class GeneratePlansRequest(
    @SerialName("clientRequestId")
    val clientRequestId: String,
)

@Serializable
data class GeneratePlansResponse(
    @SerialName("planningRunId")
    val planningRunId: String,
    @SerialName("plans")
    val plans: List<PlanResponse>,
)

@Serializable
data class SelectPlanRequest(
    @SerialName("planId")
    val planId: String,
)

@Serializable
data class PlanResponse(
    @SerialName("id")
    val id: String,
    @SerialName("taskId")
    val taskId: String,
    @SerialName("planningRunId")
    val planningRunId: String,
    @SerialName("direction")
    val direction: String,
    @SerialName("title")
    val title: String,
    @SerialName("summary")
    val summary: String,
    @SerialName("timeline")
    val timeline: List<PlanTimelineItemResponse>,
    @SerialName("estimatedCost")
    val estimatedCost: PlanEstimatedCostResponse? = null,
    @SerialName("commuteMinutes")
    val commuteMinutes: Int? = null,
    @SerialName("satisfiedConstraintIds")
    val satisfiedConstraintIds: List<String>,
    @SerialName("tradeoffs")
    val tradeoffs: List<String>,
    @SerialName("reasons")
    val reasons: List<String>,
    @SerialName("sourceRefs")
    val sourceRefs: List<PlanSourceRefResponse>,
    @SerialName("validUntil")
    val validUntil: Instant? = null,
    @SerialName("createdAt")
    val createdAt: Instant,
)

@Serializable
data class PlanTimelineItemResponse(
    @SerialName("title")
    val title: String,
    @SerialName("startAt")
    val startAt: Instant? = null,
    @SerialName("endAt")
    val endAt: Instant? = null,
    @SerialName("location")
    val location: String? = null,
)

@Serializable
data class PlanEstimatedCostResponse(
    @SerialName("wholeUnits")
    val wholeUnits: Long,
    @SerialName("currencyCode")
    val currencyCode: String? = null,
)

@Serializable
data class PlanSourceRefResponse(
    @SerialName("label")
    val label: String,
    @SerialName("uri")
    val uri: String? = null,
)
