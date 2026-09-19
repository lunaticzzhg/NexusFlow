package com.nexusflow.backend.feature.task.application

import com.nexusflow.backend.core.identity.ActorContext
import com.nexusflow.backend.feature.research.application.ReadToolExecutionObserver
import com.nexusflow.backend.feature.task.domain.Opportunity
import com.nexusflow.backend.feature.task.domain.Plan
import com.nexusflow.backend.feature.task.domain.PlanningContextSnapshot
import com.nexusflow.backend.feature.task.domain.TaskDetail
import kotlinx.coroutines.CancellationException
import java.time.Clock

internal class PlanningWorkflow(
    private val readiness: PlanningReadiness,
    private val optionalContextResolver: PlanningOptionalContextResolver,
    private val opportunityDiscovery: OpportunityDiscovery,
    private val planGenerator: PlanGenerator,
    private val planValidation: PlanValidationStep,
    private val planExplainer: PlanExplanationService,
    private val logger: PlanningLogger,
    private val clock: Clock,
) {
    suspend fun computeIfReady(
        actor: ActorContext,
        detail: TaskDetail,
        trigger: PlanningTrigger = PlanningTrigger.PlanningInputChanged,
        readToolObserver: ReadToolExecutionObserver? = null,
    ): PlanningComputationResult {
        val decision = readiness.decide(detail, trigger)
        if (decision != PlanningDecision.Plan) {
            logger.planningNotAttempted(detail, trigger, decision)
            return PlanningComputationResult(
                expectedTaskId = detail.task.id,
                expectedTaskRevision = detail.task.revision,
                opportunities = emptyList(),
                plans = emptyList(),
                outcome = PlanningOutcome.NotAttempted,
            )
        }
        return compute(actor, detail, trigger, readToolObserver)
    }

    private suspend fun compute(
        actor: ActorContext,
        detail: TaskDetail,
        trigger: PlanningTrigger,
        readToolObserver: ReadToolExecutionObserver?,
    ): PlanningComputationResult {
        val startedAt = clock.instant()
        var stage = "opportunity_discovery"
        var planningFailureLogged = false
        logger.planningStarted(detail, trigger)
        try {
            val now = clock.instant()
            stage = "optional_context"
            val optionalContext = optionalContextResolver.resolve(actor, detail)
            stage = "planning_research"
            val discovery = opportunityDiscovery.discover(actor, detail, now, optionalContext, readToolObserver)
            if (discovery.outcome != null) {
                planningFailureLogged = true
                logger.planningFinished(detail, startedAt, trigger, discovery.outcome, 0, 0)
                return detail.computationResult(discovery.outcome)
            }

            val opportunities = discovery.opportunities.filterVerified(now)
            if (opportunities.isEmpty()) {
                planningFailureLogged = true
                logger.planningFinished(detail, startedAt, trigger, PlanningOutcome.NoCandidates, 0, 0)
                return detail.computationResult(PlanningOutcome.NoCandidates)
            }

            stage = "plan_compose"
            val proposals = planGenerator.generate(detail, opportunities, now, optionalContext)
            val context = PlanningContextSnapshot(
                task = detail.task,
                requirements = detail.requirements,
                opportunities = opportunities,
                referenceTime = now,
            )
            stage = "plan_validation"
            val validation = planValidation.validate(context, proposals)
            if (validation.plans.isEmpty()) {
                val outcome = validation.failures.toPlanningOutcome()
                planningFailureLogged = true
                logger.planningFinished(detail, startedAt, trigger, outcome, opportunities.size, 0, validation.failures)
                return detail.computationResult(outcome, opportunities)
            }

            stage = "plan_explain"
            val finalPlans = planExplainer.explain(detail.task.id.value.toString(), validation.plans, opportunities, now)
            logger.planningFinished(
                detail = detail,
                startedAt = startedAt,
                trigger = trigger,
                outcome = PlanningOutcome.Ready,
                opportunityCount = opportunities.size,
                planCount = finalPlans.size,
                validationFailures = validation.failures,
            )
            return detail.computationResult(PlanningOutcome.Ready, opportunities, finalPlans)
        } catch (cause: CancellationException) {
            if (!planningFailureLogged) {
                logger.planningFailed(detail, startedAt, trigger, stage, "cancelled", cause)
            }
            throw cause
        } catch (cause: TaskDependencyUnavailableException) {
            if (!planningFailureLogged) {
                logger.planningUnavailable(detail, startedAt, trigger, stage, cause)
            }
            return detail.computationResult(PlanningOutcome.Unavailable)
        } catch (cause: Throwable) {
            if (!planningFailureLogged) {
                logger.planningFailed(detail, startedAt, trigger, stage, cause.safeFailureCategory(), cause)
            }
            throw cause
        }
    }

    private fun TaskDetail.computationResult(
        outcome: PlanningOutcome,
        opportunities: List<Opportunity> = emptyList(),
        plans: List<Plan> = emptyList(),
    ): PlanningComputationResult =
        PlanningComputationResult(
            expectedTaskId = task.id,
            expectedTaskRevision = task.revision,
            opportunities = opportunities,
            plans = plans,
            outcome = outcome,
        )
}
