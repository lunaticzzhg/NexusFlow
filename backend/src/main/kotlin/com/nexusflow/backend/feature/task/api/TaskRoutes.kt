package com.nexusflow.backend.feature.task.api

import com.nexusflow.backend.core.http.respondError
import com.nexusflow.backend.core.http.respondSuccess
import com.nexusflow.backend.core.identity.ActorResolver
import com.nexusflow.backend.core.identity.UnauthenticatedException
import com.nexusflow.backend.feature.task.application.GeneratePlansResult
import com.nexusflow.backend.feature.task.application.InvalidTaskRequestException
import com.nexusflow.backend.feature.task.application.InvalidTaskStateException
import com.nexusflow.backend.feature.task.application.MissingTaskScopeException
import com.nexusflow.backend.feature.task.application.TaskConflictException
import com.nexusflow.backend.feature.task.application.TaskDependencyUnavailableException
import com.nexusflow.backend.feature.task.application.TaskNotFoundException
import com.nexusflow.backend.feature.task.application.TaskService
import com.nexusflow.backend.feature.task.domain.ConstraintSource
import com.nexusflow.backend.feature.task.domain.ConstraintValue
import com.nexusflow.backend.feature.task.domain.ConversationMessage
import com.nexusflow.backend.feature.task.domain.MessageRole
import com.nexusflow.backend.feature.task.domain.Plan
import com.nexusflow.backend.feature.task.domain.PlanEstimatedCost
import com.nexusflow.backend.feature.task.domain.PlanSourceRef
import com.nexusflow.backend.feature.task.domain.PlanTimelineItem
import com.nexusflow.backend.feature.task.domain.Task
import com.nexusflow.backend.feature.task.domain.TaskConstraint
import com.nexusflow.backend.feature.task.domain.TaskDetail
import com.nexusflow.backend.feature.task.domain.TaskState
import com.nexusflow.contracts.api.ConstraintKind
import com.nexusflow.contracts.api.ConstraintResponse
import com.nexusflow.contracts.api.ConstraintSource as ConstraintSourceResponse
import com.nexusflow.contracts.api.ConstraintStrength
import com.nexusflow.contracts.api.ConstraintValueResponse
import com.nexusflow.contracts.api.ConversationMessageResponse
import com.nexusflow.contracts.api.CreateTaskRequest
import com.nexusflow.contracts.api.GeneratePlansRequest
import com.nexusflow.contracts.api.GeneratePlansResponse
import com.nexusflow.contracts.api.MessageRole as MessageRoleResponse
import com.nexusflow.contracts.api.PlanEstimatedCostResponse
import com.nexusflow.contracts.api.PlanResponse
import com.nexusflow.contracts.api.PlanSourceRefResponse
import com.nexusflow.contracts.api.PlanTimelineItemResponse
import com.nexusflow.contracts.api.SelectPlanRequest
import com.nexusflow.contracts.api.SendTaskMessageRequest
import com.nexusflow.contracts.api.TaskDetailResponse
import com.nexusflow.contracts.api.TaskSummaryResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import java.time.Instant
import kotlinx.datetime.Instant as ContractInstant

fun Route.taskRoutes(
    taskService: TaskService,
    actorResolver: ActorResolver,
) {
    route("/v1/tasks") {
        post {
            call.respondTask {
                val request = call.receive<CreateTaskRequest>()
                call.respondSuccess(
                    taskService.createTask(
                        actor = actorResolver.resolve(call),
                        clientRequestId = request.clientRequestId,
                        goal = request.goal,
                    ).toResponse(),
                )
            }
        }

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

        post("/{taskId}/messages") {
            call.respondTask {
                val request = call.receive<SendTaskMessageRequest>()
                call.respondSuccess(
                    taskService.sendMessage(
                        actor = actorResolver.resolve(call),
                        taskId = call.taskIdParameter(),
                        clientMessageId = request.clientMessageId,
                        text = request.text,
                        timeZoneId = request.timeZoneId,
                    ).toResponse(),
                )
            }
        }

        post("/{taskId}/planning-runs") {
            call.respondTask {
                val request = call.receive<GeneratePlansRequest>()
                call.respondSuccess(
                    taskService.generateFixturePlans(
                        actor = actorResolver.resolve(call),
                        taskId = call.taskIdParameter(),
                        clientRequestId = request.clientRequestId,
                    ).toResponse(),
                )
            }
        }

        put("/{taskId}/selected-plan") {
            call.respondTask {
                val request = call.receive<SelectPlanRequest>()
                call.respondSuccess(
                    taskService.selectPlan(
                        actor = actorResolver.resolve(call),
                        taskId = call.taskIdParameter(),
                        planId = request.planId,
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
    } catch (_: InvalidTaskStateException) {
        respondError(HttpStatusCode.UnprocessableEntity, "Task state does not allow this operation")
    } catch (error: TaskDependencyUnavailableException) {
        respondError(HttpStatusCode.ServiceUnavailable, error.message ?: "Task dependency is temporarily unavailable")
    }
}

private fun ApplicationCall.taskIdParameter(): String =
    parameters["taskId"] ?: throw InvalidTaskRequestException("taskId is required")

private fun Task.toSummaryResponse(): TaskSummaryResponse =
    TaskSummaryResponse(
        id = id.value.toString(),
        title = title,
        currentGoal = currentGoal,
        state = state.toResponse(),
        updatedAt = updatedAt.toContractInstant(),
    )

private fun TaskDetail.toResponse(): TaskDetailResponse =
    TaskDetailResponse(
        id = task.id.value.toString(),
        title = task.title,
        currentGoal = task.currentGoal,
        state = task.state.toResponse(),
        version = task.version,
        constraints = constraints.map { it.toResponse() },
        messages = messages.map { it.toResponse() },
        plans = plans.map { it.toResponse() },
        selectedPlanId = task.selectedPlanId?.value?.toString(),
        createdAt = task.createdAt.toContractInstant(),
        updatedAt = task.updatedAt.toContractInstant(),
    )

private fun GeneratePlansResult.toResponse(): GeneratePlansResponse =
    GeneratePlansResponse(
        planningRunId = planningRun.id.value.toString(),
        plans = plans.map { it.toResponse() },
    )

private fun ConversationMessage.toResponse(): ConversationMessageResponse =
    ConversationMessageResponse(
        id = id.value.toString(),
        role = role.toResponse(),
        content = content,
        clientMessageId = clientMessageId,
        aiRequestId = aiRequestId,
        understoodAt = understoodAt?.toContractInstant(),
        createdAt = createdAt.toContractInstant(),
    )

private fun MessageRole.toResponse(): MessageRoleResponse =
    when (this) {
        MessageRole.User -> MessageRoleResponse.User
        MessageRole.Assistant -> MessageRoleResponse.Assistant
    }

private fun TaskConstraint.toResponse(): ConstraintResponse =
    ConstraintResponse(
        id = id.value.toString(),
        kind = kind.toResponse(),
        value = value.toResponse(),
        strength = strength.toResponse(),
        source = source.toResponse(),
        evidenceMessageId = evidenceMessageId.value.toString(),
        confirmedAt = confirmedAt.toContractInstant(),
        createdAt = createdAt.toContractInstant(),
        updatedAt = updatedAt.toContractInstant(),
    )

private fun com.nexusflow.backend.feature.task.domain.ConstraintKind.toResponse(): ConstraintKind =
    when (this) {
        com.nexusflow.backend.feature.task.domain.ConstraintKind.TimeWindow -> ConstraintKind.TimeWindow
        com.nexusflow.backend.feature.task.domain.ConstraintKind.BudgetLimit -> ConstraintKind.BudgetLimit
        com.nexusflow.backend.feature.task.domain.ConstraintKind.CommuteLimit -> ConstraintKind.CommuteLimit
        com.nexusflow.backend.feature.task.domain.ConstraintKind.Location -> ConstraintKind.Location
        com.nexusflow.backend.feature.task.domain.ConstraintKind.ActivityDomain -> ConstraintKind.ActivityDomain
        com.nexusflow.backend.feature.task.domain.ConstraintKind.Topic -> ConstraintKind.Topic
        com.nexusflow.backend.feature.task.domain.ConstraintKind.ExperiencePreference -> ConstraintKind.ExperiencePreference
    }

private fun ConstraintValue.toResponse(): ConstraintValueResponse =
    when (this) {
        is ConstraintValue.TimeWindow -> ConstraintValueResponse.TimeWindow(
            startAt = startAt?.toContractInstant(),
            endAt = endAt?.toContractInstant(),
            timeZoneId = timeZoneId,
            originalText = originalText,
        )
        is ConstraintValue.BudgetLimit -> ConstraintValueResponse.BudgetLimit(
            wholeUnits = wholeUnits,
            currencyCode = currencyCode,
        )
        is ConstraintValue.CommuteLimit -> ConstraintValueResponse.CommuteLimit(maxMinutes = maxMinutes)
        is ConstraintValue.Location -> ConstraintValueResponse.Location(text = text)
        is ConstraintValue.ActivityDomain -> ConstraintValueResponse.ActivityDomain(value = value)
        is ConstraintValue.Topic -> ConstraintValueResponse.Topic(text = text)
        is ConstraintValue.ExperiencePreference -> ConstraintValueResponse.ExperiencePreference(text = text)
    }

private fun com.nexusflow.backend.feature.task.domain.ConstraintStrength.toResponse(): ConstraintStrength =
    when (this) {
        com.nexusflow.backend.feature.task.domain.ConstraintStrength.Hard -> ConstraintStrength.Hard
        com.nexusflow.backend.feature.task.domain.ConstraintStrength.Soft -> ConstraintStrength.Soft
    }

private fun ConstraintSource.toResponse(): ConstraintSourceResponse =
    when (this) {
        ConstraintSource.UserExplicit -> ConstraintSourceResponse.UserExplicit
        ConstraintSource.AcceptedSuggestion -> ConstraintSourceResponse.AcceptedSuggestion
        ConstraintSource.OpportunityContext -> ConstraintSourceResponse.OpportunityContext
        ConstraintSource.SystemDerived -> ConstraintSourceResponse.SystemDerived
    }

private fun Plan.toResponse(): PlanResponse =
    PlanResponse(
        id = id.value.toString(),
        taskId = taskId.value.toString(),
        planningRunId = planningRunId.value.toString(),
        direction = direction,
        title = title,
        summary = summary,
        timeline = timeline.map { it.toResponse() },
        estimatedCost = estimatedCost?.toResponse(),
        commuteMinutes = commuteMinutes,
        satisfiedConstraintIds = satisfiedConstraintIds.map { it.value.toString() },
        tradeoffs = tradeoffs,
        reasons = reasons,
        sourceRefs = sourceRefs.map { it.toResponse() },
        validUntil = validUntil?.toContractInstant(),
        createdAt = createdAt.toContractInstant(),
    )

private fun PlanTimelineItem.toResponse(): PlanTimelineItemResponse =
    PlanTimelineItemResponse(
        title = title,
        startAt = startAt?.toContractInstant(),
        endAt = endAt?.toContractInstant(),
        location = location,
    )

private fun PlanEstimatedCost.toResponse(): PlanEstimatedCostResponse =
    PlanEstimatedCostResponse(wholeUnits = wholeUnits, currencyCode = currencyCode)

private fun PlanSourceRef.toResponse(): PlanSourceRefResponse =
    PlanSourceRefResponse(label = label, uri = uri)

private fun TaskState.toResponse(): com.nexusflow.contracts.api.TaskState =
    when (this) {
        TaskState.Draft -> com.nexusflow.contracts.api.TaskState.Draft
        TaskState.CollectingConstraints -> com.nexusflow.contracts.api.TaskState.CollectingConstraints
        TaskState.Planning -> com.nexusflow.contracts.api.TaskState.Planning
        TaskState.WaitingForApproval -> com.nexusflow.contracts.api.TaskState.WaitingForApproval
        TaskState.Executing -> com.nexusflow.contracts.api.TaskState.Executing
        TaskState.NeedsAttention -> com.nexusflow.contracts.api.TaskState.NeedsAttention
        TaskState.Completed -> com.nexusflow.contracts.api.TaskState.Completed
        TaskState.Cancelled -> com.nexusflow.contracts.api.TaskState.Cancelled
    }

private fun Instant.toContractInstant(): ContractInstant =
    ContractInstant.fromEpochSeconds(epochSecond, nano.toLong())
