package com.nexusflow.backend.feature.task.application

import com.nexusflow.backend.feature.task.domain.PlanDirection
import com.nexusflow.backend.feature.task.domain.PlanDraft
import com.nexusflow.backend.feature.task.domain.PlanId
import com.nexusflow.backend.feature.task.domain.PlanValidator
import com.nexusflow.backend.feature.task.domain.PlanningContextSnapshot
import com.nexusflow.contracts.backendai.planning.PlanDirection as AiPlanDirection
import com.nexusflow.contracts.backendai.planning.PlanProposal as AiPlanProposal
import java.util.UUID

internal class PlanValidationStep(
    private val planValidator: PlanValidator,
    private val uuidFactory: () -> UUID,
    private val logger: PlanningLogger,
) {
    fun validate(
        context: PlanningContextSnapshot,
        proposals: List<AiPlanProposal>,
    ): PlanningValidationDecision {
        val drafts = proposals.map { proposal ->
            PlanDraft(
                id = PlanId(uuidFactory()),
                direction = proposal.direction.toBackendDirection(),
                opportunityRefs = proposal.opportunityRefs.map { it.toPlanningOpportunityId() },
            )
        }
        val report = planValidator.validate(context, drafts)
        logger.planValidationFinished(context, report.plans, report.failures)
        return PlanningValidationDecision(report.plans, report.failures)
    }

    private fun AiPlanDirection.toBackendDirection(): PlanDirection =
        when (this) {
            AiPlanDirection.BestMatch -> PlanDirection.BestMatch
            AiPlanDirection.MoreRelaxed -> PlanDirection.MoreRelaxed
            AiPlanDirection.NewExperience -> PlanDirection.NewExperience
        }
}
