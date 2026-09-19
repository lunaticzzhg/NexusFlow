package com.nexusflow.backend.feature.task.application

import com.nexusflow.backend.core.observability.OperationLogContext
import com.nexusflow.backend.core.observability.addOperationFields
import com.nexusflow.backend.feature.research.application.ReadToolExecution
import com.nexusflow.backend.feature.research.application.ReadToolOutcome
import com.nexusflow.backend.feature.task.domain.Plan
import com.nexusflow.backend.feature.task.domain.PlanValidationFailure
import com.nexusflow.backend.feature.task.domain.PlanningContextSnapshot
import com.nexusflow.backend.feature.task.domain.TaskDetail
import com.nexusflow.observability.StructuredLogger
import com.nexusflow.observability.logFields
import java.time.Clock
import java.time.Duration
import java.time.Instant

internal class PlanningLogger(
    private val logger: StructuredLogger?,
    private val clock: Clock,
) {
    fun planningStarted(
        detail: TaskDetail,
        trigger: PlanningTrigger,
        operationLogContext: OperationLogContext? = null,
    ) {
        logger?.info(
            component = PLANNING_LOG_COMPONENT,
            event = "planning_started",
            fields =
                logFields {
                    addOperationFields(operationLogContext, step = "planning_started", outcome = "started")
                    "response_run_id" value operationLogContext?.operationId
                    "task_id" value detail.task.id.value.toString()
                    "task_revision" value detail.task.revision
                    "planning_trigger" value trigger.logValue
                    "planning_decision" value PlanningDecision.Plan.logValue
                },
        )
    }

    fun readinessChecked(
        detail: TaskDetail,
        trigger: PlanningTrigger,
        decision: PlanningDecision,
        operationLogContext: OperationLogContext? = null,
    ) {
        logger?.info(
            component = PLANNING_LOG_COMPONENT,
            event = "planning_readiness_checked",
            fields =
                logFields {
                    addOperationFields(operationLogContext, step = "readiness_checked", outcome = decision.logValue)
                    "response_run_id" value operationLogContext?.operationId
                    "task_id" value detail.task.id.value.toString()
                    "task_revision" value detail.task.revision
                    "planning_trigger" value trigger.logValue
                    "planning_decision" value decision.logValue
                },
        )
    }

    fun planningNotAttempted(
        detail: TaskDetail,
        trigger: PlanningTrigger,
        decision: PlanningDecision,
        operationLogContext: OperationLogContext? = null,
    ) {
        logger?.info(
            component = PLANNING_LOG_COMPONENT,
            event = "planning_not_attempted",
            fields =
                logFields {
                    addOperationFields(operationLogContext, step = "planning_finished", outcome = PlanningOutcome.NotAttempted.logValue)
                    "response_run_id" value operationLogContext?.operationId
                    "task_id" value detail.task.id.value.toString()
                    "task_revision" value detail.task.revision
                    "planning_trigger" value trigger.logValue
                    "planning_decision" value decision.logValue
                },
        )
    }

    fun planningFinished(
        detail: TaskDetail,
        startedAt: Instant,
        trigger: PlanningTrigger,
        outcome: PlanningOutcome,
        opportunityCount: Int,
        planCount: Int,
        validationFailures: List<PlanValidationFailure> = emptyList(),
        operationLogContext: OperationLogContext? = null,
    ) {
        logger?.info(
            component = PLANNING_LOG_COMPONENT,
            event = "planning_finished",
            fields =
                logFields {
                    addOperationFields(operationLogContext, step = "planning_finished", outcome = outcome.logValue)
                    "response_run_id" value operationLogContext?.operationId
                    "task_id" value detail.task.id.value.toString()
                    "task_revision" value detail.task.revision
                    "duration_ms" value Duration.between(startedAt, clock.instant()).toMillis().coerceAtLeast(0)
                    "planning_trigger" value trigger.logValue
                    "planning_decision" value PlanningDecision.Plan.logValue
                    "opportunity_count" value opportunityCount
                    "plan_count" value planCount
                    "validation_failure_count" value validationFailures.size
                    "validation_failure_codes" value validationFailures.joinToString(",") { it.code.name.toSnakeCase() }
                },
        )
    }

    fun planningSupersededOnPersist(
        detail: TaskDetail,
        trigger: PlanningTrigger,
    ) {
        logger?.warn(
            component = PLANNING_LOG_COMPONENT,
            event = "planning_finished",
            fields =
                logFields {
                    "task_id" value detail.task.id.value.toString()
                    "task_revision" value detail.task.revision
                    "planning_trigger" value trigger.logValue
                    "outcome" value PlanningOutcome.Superseded.logValue
                    "stage" value "persist"
                },
        )
    }

    fun planningUnavailable(
        detail: TaskDetail,
        startedAt: Instant,
        trigger: PlanningTrigger,
        stage: String,
        cause: Throwable,
        operationLogContext: OperationLogContext? = null,
    ) {
        logger?.warn(
            component = PLANNING_LOG_COMPONENT,
            event = "planning_unavailable",
            fields =
                logFields {
                    addOperationFields(operationLogContext, step = "planning_finished", outcome = PlanningOutcome.Unavailable.logValue)
                    "response_run_id" value operationLogContext?.operationId
                    "task_id" value detail.task.id.value.toString()
                    "task_revision" value detail.task.revision
                    "duration_ms" value Duration.between(startedAt, clock.instant()).toMillis().coerceAtLeast(0)
                    "planning_trigger" value trigger.logValue
                    "planning_stage" value stage
                    "planning_decision" value PlanningDecision.Plan.logValue
                    "failure_category" value cause.safeFailureCategory()
                    "failure_reason" value (cause.message ?: "unavailable")
                },
        )
    }

    fun planningDegraded(
        requestId: String,
        taskRevision: Long?,
        stage: String,
        failureCategory: String,
    ) {
        logger?.warn(
            component = PLANNING_LOG_COMPONENT,
            event = "planning_degraded",
            fields =
                logFields {
                    "request_id" value requestId
                    "task_revision" value taskRevision
                    "stage" value stage
                    "outcome" value "degraded"
                    "failure_category" value failureCategory
                },
        )
    }

    fun planningResearchStarted(
        detail: TaskDetail,
        availableReadToolCount: Int,
    ) {
        logger?.info(
            component = PLANNING_LOG_COMPONENT,
            event = "planning_research_started",
            fields =
                logFields {
                    "task_id" value detail.task.id.value.toString()
                    "task_revision" value detail.task.revision
                    "available_read_tool_count" value availableReadToolCount
                },
        )
    }

    fun planningResearchFinished(
        detail: TaskDetail,
        executions: List<ReadToolExecution>,
        opportunityCount: Int,
    ) {
        logger?.info(
            component = PLANNING_LOG_COMPONENT,
            event = "planning_research_finished",
            fields =
                logFields {
                    "task_id" value detail.task.id.value.toString()
                    "task_revision" value detail.task.revision
                    "tool_count" value executions.size
                    "opportunity_count" value opportunityCount
                    "source_failure_count" value executions.count { it.outcome is ReadToolOutcome.Unavailable }
                    "read_tool_outcomes" value executions.joinToString(",") { execution ->
                        "${execution.call.key.value}:${execution.outcome.logValue()}"
                    }
                },
        )
    }

    fun planValidationFinished(
        context: PlanningContextSnapshot,
        plans: List<Plan>,
        failures: List<PlanValidationFailure>,
    ) {
        logger?.debug(
            component = PLANNING_LOG_COMPONENT,
            event = "plan_validation_finished",
            fields =
                logFields {
                    "task_id" value context.task.id.value.toString()
                    "task_revision" value context.task.revision
                    "plan_count" value plans.size
                    "failure_count" value failures.size
                    "failure_codes" value failures.joinToString(",") { it.code.name.toSnakeCase() }
                },
        )
    }

    fun planningFailed(
        detail: TaskDetail,
        startedAt: Instant,
        trigger: PlanningTrigger,
        stage: String,
        failureCategory: String,
        cause: Throwable? = null,
        operationLogContext: OperationLogContext? = null,
    ) {
        logger?.error(
            component = PLANNING_LOG_COMPONENT,
            event = "planning_failed",
            fields =
                logFields {
                    addOperationFields(operationLogContext, step = "planning_failed", outcome = "failed")
                    "response_run_id" value operationLogContext?.operationId
                    "task_id" value detail.task.id.value.toString()
                    "task_revision" value detail.task.revision
                    "duration_ms" value Duration.between(startedAt, clock.instant()).toMillis().coerceAtLeast(0)
                    "planning_trigger" value trigger.logValue
                    "planning_stage" value stage
                    "planning_decision" value PlanningDecision.Plan.logValue
                    "failure_category" value failureCategory
                },
            cause = cause,
        )
    }

    private fun ReadToolOutcome.logValue(): String =
        when (this) {
            is ReadToolOutcome.Success -> "success"
            ReadToolOutcome.Empty -> "empty"
            is ReadToolOutcome.MissingInput -> "missing_input"
            is ReadToolOutcome.InvalidArguments -> "invalid_arguments"
            is ReadToolOutcome.Unavailable -> "unavailable"
        }
}
