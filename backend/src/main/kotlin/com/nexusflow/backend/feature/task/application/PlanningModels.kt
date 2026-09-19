package com.nexusflow.backend.feature.task.application

import com.nexusflow.backend.core.aicontext.ModelContextBlock
import com.nexusflow.backend.feature.task.domain.Opportunity
import com.nexusflow.backend.feature.task.domain.Plan
import com.nexusflow.backend.feature.task.domain.PlanValidationFailure
import com.nexusflow.backend.feature.task.domain.TaskDetail
import com.nexusflow.backend.feature.task.domain.TaskId
import com.nexusflow.contracts.backendai.common.StructuredModelRequestDiagnostics

data class PlanningAttemptResult(
    val detail: TaskDetail,
    val outcome: PlanningOutcome,
)

data class PlanningComputationResult(
    val expectedTaskId: TaskId,
    val expectedTaskRevision: Long,
    val opportunities: List<Opportunity>,
    val plans: List<Plan>,
    val outcome: PlanningOutcome,
)

enum class PlanningTrigger {
    PlanningInputChanged,
    ExplicitUserRequest,
}

enum class PlanningOutcome {
    NotAttempted,
    Ready,
    NoCandidates,
    NoFeasiblePlan,
    Unavailable,
    Superseded,
}

class PlanningReadiness {
    fun decide(
        detail: TaskDetail,
        trigger: PlanningTrigger = PlanningTrigger.PlanningInputChanged,
    ): PlanningDecision {
        if (detail.requirements.isEmpty()) {
            return PlanningDecision.MissingPlanningInputs
        }
        if (trigger == PlanningTrigger.ExplicitUserRequest) {
            return PlanningDecision.Plan
        }
        return if (detail.plans.none { it.revision == detail.task.revision }) {
            PlanningDecision.Plan
        } else {
            PlanningDecision.KeepCurrentPlans
        }
    }
}

typealias PlanningReadinessPolicy = PlanningReadiness

enum class PlanningDecision {
    KeepCurrentPlans,
    MissingPlanningInputs,
    Plan,
}

internal data class PlanningOptionalContext(
    val blocks: List<ModelContextBlock> = emptyList(),
    val diagnostics: StructuredModelRequestDiagnostics = StructuredModelRequestDiagnostics(),
)

internal data class PlanningValidationDecision(
    val plans: List<Plan>,
    val failures: List<PlanValidationFailure>,
)

internal data class PlanningResearchDiscovery(
    val opportunities: List<Opportunity> = emptyList(),
    val outcome: PlanningOutcome? = null,
)
