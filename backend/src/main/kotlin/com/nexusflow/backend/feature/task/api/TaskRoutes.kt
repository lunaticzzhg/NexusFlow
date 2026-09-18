package com.nexusflow.backend.feature.task.api

import com.nexusflow.backend.core.http.respondError
import com.nexusflow.backend.core.http.respondSuccess
import com.nexusflow.backend.core.identity.ActorResolver
import com.nexusflow.backend.core.identity.UnauthenticatedException
import com.nexusflow.backend.feature.task.application.InvalidTaskRequestException
import com.nexusflow.backend.feature.task.application.InvalidTaskOperationException
import com.nexusflow.backend.feature.task.application.MissingTaskScopeException
import com.nexusflow.backend.feature.task.application.PlanningOutcome
import com.nexusflow.backend.feature.task.application.PlanningService
import com.nexusflow.backend.feature.task.application.TaskConflictException
import com.nexusflow.backend.feature.task.application.TaskDependencyUnavailableException
import com.nexusflow.backend.feature.task.application.TaskMutationResult
import com.nexusflow.backend.feature.task.application.TaskNotFoundException
import com.nexusflow.backend.feature.task.application.TaskService
import com.nexusflow.backend.feature.task.domain.ActivityModeValue
import com.nexusflow.backend.feature.task.domain.CommutePreferenceValue
import com.nexusflow.backend.feature.task.domain.Plan
import com.nexusflow.backend.feature.task.domain.PlanDirection
import com.nexusflow.backend.feature.task.domain.PlanEstimatedCost
import com.nexusflow.backend.feature.task.domain.PlanSourceRef
import com.nexusflow.backend.feature.task.domain.PlanTimelineItem
import com.nexusflow.backend.feature.task.domain.Requirement
import com.nexusflow.backend.feature.task.domain.RequirementEvaluation
import com.nexusflow.backend.feature.task.domain.RequirementEvaluationResult
import com.nexusflow.backend.feature.task.domain.RequirementEvidence
import com.nexusflow.backend.feature.task.domain.RequirementKind
import com.nexusflow.backend.feature.task.domain.RequirementSource
import com.nexusflow.backend.feature.task.domain.RequirementStrength
import com.nexusflow.backend.feature.task.domain.RequirementValue
import com.nexusflow.backend.feature.task.domain.Task
import com.nexusflow.backend.feature.task.domain.TaskDetail
import com.nexusflow.contracts.appbackend.task.ActivityModeValue as ActivityModeValueResponse
import com.nexusflow.contracts.appbackend.task.CommutePreferenceValue as CommutePreferenceValueResponse
import com.nexusflow.contracts.appbackend.plan.PlanDirection as PlanDirectionResponse
import com.nexusflow.contracts.appbackend.plan.PlanEstimatedCostResponse
import com.nexusflow.contracts.appbackend.plan.PlanResponse
import com.nexusflow.contracts.appbackend.plan.PlanSourceRefResponse
import com.nexusflow.contracts.appbackend.plan.PlanTimelineItemResponse
import com.nexusflow.contracts.appbackend.task.PlanningStatus
import com.nexusflow.contracts.appbackend.task.PlanningStatusResponse
import com.nexusflow.contracts.appbackend.plan.RequirementEvaluationResponse
import com.nexusflow.contracts.appbackend.plan.RequirementEvaluationResult as RequirementEvaluationResultResponse
import com.nexusflow.contracts.appbackend.task.RequirementKind as RequirementKindResponse
import com.nexusflow.contracts.appbackend.task.RequirementResponse
import com.nexusflow.contracts.appbackend.task.RequirementSource as RequirementSourceResponse
import com.nexusflow.contracts.appbackend.task.RequirementStrength as RequirementStrengthResponse
import com.nexusflow.contracts.appbackend.task.RequirementSummaryResponse
import com.nexusflow.contracts.appbackend.task.RequirementValueResponse
import com.nexusflow.contracts.appbackend.task.TaskDetailResponse
import com.nexusflow.contracts.appbackend.task.TaskResponse
import com.nexusflow.contracts.appbackend.task.TaskSummaryResponse
import com.nexusflow.contracts.appbackend.task.UpdateRequirementRequest
import com.nexusflow.contracts.appbackend.conversation.ConversationCurrentTaskResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.time.Instant
import kotlinx.datetime.Instant as ContractInstant

fun Route.taskRoutes(
    taskService: TaskService,
    planningService: PlanningService,
    actorResolver: ActorResolver,
) {
    route("/v1/tasks") {
        get {
            call.respondTask {
                call.respondSuccess(taskService.listTasks(actorResolver.resolve(call)).map { it.toSummaryResponse() })
            }
        }

        get("/{taskId}") {
            call.respondTask {
                call.respondSuccess(
                    taskService.getTask(
                        actor = actorResolver.resolve(call),
                        taskId = call.taskIdParameter(),
                    ).toResponse(),
                )
            }
        }

        put("/{taskId}/requirements/{requirementId}") {
            call.respondTask {
                val request = call.receive<UpdateRequirementRequest>()
                call.respondSuccess(
                    taskService.updateRequirement(
                        actor = actorResolver.resolve(call),
                        taskId = call.taskIdParameter(),
                        requirementId = call.requirementIdParameter(),
                        kind = request.kind.toDomain(),
                        value = request.value.toDomain(),
                        strength = request.strength.toDomain(),
                    ).toResponse(),
                )
            }
        }

        delete("/{taskId}/requirements/{requirementId}") {
            call.respondTask {
                call.respondSuccess(
                    taskService.deleteRequirement(
                        actor = actorResolver.resolve(call),
                        taskId = call.taskIdParameter(),
                        requirementId = call.requirementIdParameter(),
                    ).toResponse(),
                )
            }
        }

        post("/{taskId}/plans/{planId}/select") {
            call.respondTask {
                call.respondSuccess(
                    planningService.selectPlan(
                        actor = actorResolver.resolve(call),
                        taskId = call.taskIdParameter(),
                        planId = call.planIdParameter(),
                    ).toResponse(),
                )
            }
        }
    }
}

private suspend fun ApplicationCall.respondTask(block: suspend () -> Unit) {
    try {
        block()
    } catch (_: UnauthenticatedException) {
        respondError(HttpStatusCode.Unauthorized, "Authentication is required")
    } catch (_: MissingTaskScopeException) {
        respondError(HttpStatusCode.Forbidden, "Required task scope is missing")
    } catch (_: TaskNotFoundException) {
        respondError(HttpStatusCode.NotFound, "Task was not found")
    } catch (_: TaskConflictException) {
        respondError(HttpStatusCode.Conflict, "Task request conflicts with existing state")
    } catch (_: InvalidTaskRequestException) {
        respondError(HttpStatusCode.UnprocessableEntity, "Invalid request")
    } catch (_: InvalidTaskOperationException) {
        respondError(HttpStatusCode.UnprocessableEntity, "Task operation is not allowed")
    } catch (error: TaskDependencyUnavailableException) {
        respondError(HttpStatusCode.ServiceUnavailable, error.message ?: "Task dependency is temporarily unavailable")
    }
}

private fun ApplicationCall.taskIdParameter(): String =
    parameters["taskId"] ?: throw InvalidTaskRequestException("taskId is required")

private fun ApplicationCall.requirementIdParameter(): String =
    parameters["requirementId"] ?: throw InvalidTaskRequestException("requirementId is required")

private fun ApplicationCall.planIdParameter(): String =
    parameters["planId"] ?: throw InvalidTaskRequestException("planId is required")

private fun TaskDetail.toSummaryResponse(): TaskSummaryResponse =
    TaskSummaryResponse(
        id = task.id.value.toString(),
        intent = task.intent,
        requirements = requirements.map { it.toSummaryResponse() },
        selectedPlanId = task.selectedPlanId?.value?.toString(),
        updatedAt = task.updatedAt.toContractInstant(),
        conversationId = task.conversationId?.value?.toString(),
    )

private fun TaskDetail.toResponse(): TaskDetailResponse {
    val currentPlans = plans.filter { it.revision == task.revision }
    return toResponse(currentPlans, currentPlans.defaultPlanningStatus())
}

private fun TaskMutationResult.toResponse(): TaskDetailResponse {
    val currentPlans = detail.plans.filter { it.revision == detail.task.revision }
    return detail.toResponse(currentPlans, planningOutcome.toResponseStatus(currentPlans))
}

internal fun TaskDetail.toConversationCurrentTaskResponse(planningOutcome: PlanningOutcome = PlanningOutcome.NotAttempted): ConversationCurrentTaskResponse {
    val currentPlans = plans.filter { it.revision == task.revision }
    return ConversationCurrentTaskResponse(
        task = task.toResponse(currentPlans.mapTo(mutableSetOf()) { it.id }),
        requirements = requirements.map { it.toResponse() },
        plans = currentPlans.map { it.toResponse() },
        planning = PlanningStatusResponse(planningOutcome.toResponseStatus(currentPlans)),
    )
}

private fun TaskDetail.toResponse(
    currentPlans: List<Plan>,
    planningStatus: PlanningStatus,
): TaskDetailResponse {
    val currentPlanIds = currentPlans.mapTo(mutableSetOf()) { it.id }
    return TaskDetailResponse(
        task = task.toResponse(currentPlanIds),
        requirements = requirements.map { it.toResponse() },
        plans = currentPlans.map { it.toResponse() },
        planning = PlanningStatusResponse(planningStatus),
    )
}

private fun PlanningOutcome.toResponseStatus(currentPlans: List<Plan>): PlanningStatus =
    when (this) {
        PlanningOutcome.NotAttempted,
        PlanningOutcome.Superseded,
        -> currentPlans.defaultPlanningStatus()
        PlanningOutcome.Ready -> PlanningStatus.Ready
        PlanningOutcome.NoCandidates -> PlanningStatus.NoCandidates
        PlanningOutcome.NoFeasiblePlan -> PlanningStatus.NoFeasiblePlan
        PlanningOutcome.Unavailable -> PlanningStatus.Unavailable
    }

private fun List<Plan>.defaultPlanningStatus(): PlanningStatus =
    if (isEmpty()) PlanningStatus.Idle else PlanningStatus.Ready

private fun Task.toResponse(currentPlanIds: Set<com.nexusflow.backend.feature.task.domain.PlanId>): TaskResponse =
    TaskResponse(
        id = id.value.toString(),
        intent = intent,
        revision = revision,
        selectedPlanId = selectedPlanId?.takeIf { it in currentPlanIds }?.value?.toString(),
        createdAt = createdAt.toContractInstant(),
        updatedAt = updatedAt.toContractInstant(),
    )

private fun Requirement.toSummaryResponse(): RequirementSummaryResponse =
    RequirementSummaryResponse(
        id = id.value.toString(),
        label = value.summary(),
        strength = strength.toResponse(),
    )

private fun Requirement.toResponse(): RequirementResponse =
    RequirementResponse(
        id = id.value.toString(),
        kind = kind.toResponse(),
        value = value.toResponse(),
        strength = strength.toResponse(),
        source = source.toResponse(),
        evidenceMessageId = (evidence as? RequirementEvidence.UserMessage)?.messageId?.value?.toString(),
        createdAt = createdAt.toContractInstant(),
        updatedAt = updatedAt.toContractInstant(),
    )

private fun RequirementKind.toResponse(): RequirementKindResponse =
    when (this) {
        RequirementKind.TimeWindow -> RequirementKindResponse.TimeWindow
        RequirementKind.BudgetLimit -> RequirementKindResponse.BudgetLimit
        RequirementKind.CommuteLimit -> RequirementKindResponse.CommuteLimit
        RequirementKind.CommutePreference -> RequirementKindResponse.CommutePreference
        RequirementKind.Location -> RequirementKindResponse.Location
        RequirementKind.ActivityDomain -> RequirementKindResponse.ActivityDomain
        RequirementKind.ActivityMode -> RequirementKindResponse.ActivityMode
        RequirementKind.Topic -> RequirementKindResponse.Topic
        RequirementKind.ExperiencePreference -> RequirementKindResponse.ExperiencePreference
    }

private fun RequirementKindResponse.toDomain(): RequirementKind =
    when (this) {
        RequirementKindResponse.TimeWindow -> RequirementKind.TimeWindow
        RequirementKindResponse.BudgetLimit -> RequirementKind.BudgetLimit
        RequirementKindResponse.CommuteLimit -> RequirementKind.CommuteLimit
        RequirementKindResponse.CommutePreference -> RequirementKind.CommutePreference
        RequirementKindResponse.Location -> RequirementKind.Location
        RequirementKindResponse.ActivityDomain -> RequirementKind.ActivityDomain
        RequirementKindResponse.ActivityMode -> RequirementKind.ActivityMode
        RequirementKindResponse.Topic -> RequirementKind.Topic
        RequirementKindResponse.ExperiencePreference -> RequirementKind.ExperiencePreference
    }

private fun RequirementValue.toResponse(): RequirementValueResponse =
    when (this) {
        is RequirementValue.TimeWindow -> RequirementValueResponse.TimeWindow(
            startAt = startAt?.toContractInstant(),
            endAt = endAt?.toContractInstant(),
            timeZoneId = timeZoneId,
            originalText = originalText,
        )
        is RequirementValue.BudgetLimit -> RequirementValueResponse.BudgetLimit(wholeUnits = wholeUnits, currencyCode = currencyCode)
        is RequirementValue.CommuteLimit -> RequirementValueResponse.CommuteLimit(maxMinutes = maxMinutes)
        is RequirementValue.CommutePreference -> RequirementValueResponse.CommutePreference(value.toResponse())
        is RequirementValue.Location -> RequirementValueResponse.Location(text = text)
        is RequirementValue.ActivityDomain -> RequirementValueResponse.ActivityDomain(value = value)
        is RequirementValue.ActivityMode -> RequirementValueResponse.ActivityMode(value.toResponse())
        is RequirementValue.Topic -> RequirementValueResponse.Topic(text = text)
        is RequirementValue.ExperiencePreference -> RequirementValueResponse.ExperiencePreference(text = text)
    }

private fun RequirementValueResponse.toDomain(): RequirementValue =
    when (this) {
        is RequirementValueResponse.TimeWindow ->
            RequirementValue.TimeWindow(startAt?.toJavaInstant(), endAt?.toJavaInstant(), timeZoneId, originalText)
        is RequirementValueResponse.BudgetLimit -> RequirementValue.BudgetLimit(wholeUnits, currencyCode)
        is RequirementValueResponse.CommuteLimit -> RequirementValue.CommuteLimit(maxMinutes)
        is RequirementValueResponse.CommutePreference -> RequirementValue.CommutePreference(value.toDomain())
        is RequirementValueResponse.Location -> RequirementValue.Location(text)
        is RequirementValueResponse.ActivityDomain -> RequirementValue.ActivityDomain(value)
        is RequirementValueResponse.ActivityMode -> RequirementValue.ActivityMode(value.toDomain())
        is RequirementValueResponse.Topic -> RequirementValue.Topic(text)
        is RequirementValueResponse.ExperiencePreference -> RequirementValue.ExperiencePreference(text)
    }

private fun RequirementValue.summary(): String =
    when (this) {
        is RequirementValue.TimeWindow -> originalText
        is RequirementValue.BudgetLimit -> listOfNotNull(wholeUnits.toString(), currencyCode).joinToString(" ")
        is RequirementValue.CommuteLimit -> "$maxMinutes minutes"
        is RequirementValue.CommutePreference -> value.name
        is RequirementValue.Location -> text
        is RequirementValue.ActivityDomain -> value
        is RequirementValue.ActivityMode -> value.name
        is RequirementValue.Topic -> text
        is RequirementValue.ExperiencePreference -> text
    }

private fun CommutePreferenceValue.toResponse(): CommutePreferenceValueResponse =
    when (this) {
        CommutePreferenceValue.PreferShorter -> CommutePreferenceValueResponse.PreferShorter
    }

private fun CommutePreferenceValueResponse.toDomain(): CommutePreferenceValue =
    when (this) {
        CommutePreferenceValueResponse.PreferShorter -> CommutePreferenceValue.PreferShorter
    }

private fun ActivityModeValue.toResponse(): ActivityModeValueResponse =
    when (this) {
        ActivityModeValue.AtHome -> ActivityModeValueResponse.AtHome
        ActivityModeValue.OutOfHome -> ActivityModeValueResponse.OutOfHome
    }

private fun ActivityModeValueResponse.toDomain(): ActivityModeValue =
    when (this) {
        ActivityModeValueResponse.AtHome -> ActivityModeValue.AtHome
        ActivityModeValueResponse.OutOfHome -> ActivityModeValue.OutOfHome
    }

private fun RequirementStrength.toResponse(): RequirementStrengthResponse =
    when (this) {
        RequirementStrength.Must -> RequirementStrengthResponse.Must
        RequirementStrength.Prefer -> RequirementStrengthResponse.Prefer
    }

private fun RequirementStrengthResponse.toDomain(): RequirementStrength =
    when (this) {
        RequirementStrengthResponse.Must -> RequirementStrength.Must
        RequirementStrengthResponse.Prefer -> RequirementStrength.Prefer
    }

private fun RequirementSource.toResponse(): RequirementSourceResponse =
    when (this) {
        RequirementSource.UserExplicit -> RequirementSourceResponse.UserExplicit
        RequirementSource.SystemDerived -> RequirementSourceResponse.SystemDerived
    }

private fun Plan.toResponse(): PlanResponse =
    PlanResponse(
        id = id.value.toString(),
        taskId = taskId.value.toString(),
        revision = revision,
        direction = direction.toResponse(),
        title = title,
        summary = summary,
        timeline = timeline.map { it.toResponse() },
        estimatedCost = estimatedCost?.toResponse(),
        commuteMinutes = commuteMinutes,
        requirementEvaluations = requirementEvaluations.map { it.toResponse() },
        tradeoffs = tradeoffs,
        reasons = reasons,
        sourceRefs = sourceRefs.map { it.toResponse() },
        opportunityRefs = opportunityRefs.map { it.value.toString() },
        validUntil = validUntil?.toContractInstant(),
        createdAt = createdAt.toContractInstant(),
    )

private fun PlanDirection.toResponse(): PlanDirectionResponse =
    when (this) {
        PlanDirection.BestMatch -> PlanDirectionResponse.BestMatch
        PlanDirection.MoreRelaxed -> PlanDirectionResponse.MoreRelaxed
        PlanDirection.NewExperience -> PlanDirectionResponse.NewExperience
    }

private fun PlanTimelineItem.toResponse(): PlanTimelineItemResponse =
    PlanTimelineItemResponse(
        title = title,
        startAt = startAt?.toContractInstant(),
        endAt = endAt?.toContractInstant(),
        location = location,
    )

private fun PlanEstimatedCost.toResponse(): PlanEstimatedCostResponse =
    PlanEstimatedCostResponse(wholeUnits = wholeUnits, currencyCode = currencyCode)

private fun RequirementEvaluation.toResponse(): RequirementEvaluationResponse =
    RequirementEvaluationResponse(
        requirementId = requirementId.value.toString(),
        result =
            when (result) {
                RequirementEvaluationResult.Satisfied -> RequirementEvaluationResultResponse.Satisfied
                RequirementEvaluationResult.NotApplicable -> RequirementEvaluationResultResponse.NotApplicable
            },
        explanation = explanation,
    )

private fun PlanSourceRef.toResponse(): PlanSourceRefResponse =
    PlanSourceRefResponse(label = label, uri = uri, sourceUpdatedAt = sourceUpdatedAt?.toContractInstant())

private fun Instant.toContractInstant(): ContractInstant =
    ContractInstant.fromEpochSeconds(epochSecond, nano.toLong())

private fun ContractInstant.toJavaInstant(): Instant =
    Instant.ofEpochSecond(epochSeconds, nanosecondsOfSecond.toLong())
