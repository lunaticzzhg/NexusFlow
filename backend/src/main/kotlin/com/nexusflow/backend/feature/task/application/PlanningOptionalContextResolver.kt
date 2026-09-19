package com.nexusflow.backend.feature.task.application

import com.nexusflow.backend.core.aicontext.ModelContextAllowance
import com.nexusflow.backend.core.aicontext.ModelContextAssembler
import com.nexusflow.backend.core.aicontext.ModelContextKey
import com.nexusflow.backend.core.aicontext.ModelContextLifecycle
import com.nexusflow.backend.core.aicontext.ModelContextResolveRequest
import com.nexusflow.backend.core.identity.ActorContext
import com.nexusflow.backend.feature.task.domain.RequirementKind
import com.nexusflow.backend.feature.task.domain.TaskDetail
import com.nexusflow.contracts.backendai.common.StructuredModelCapability

internal class PlanningOptionalContextResolver(
    private val modelContextAssembler: ModelContextAssembler?,
) {
    suspend fun resolve(
        actor: ActorContext,
        detail: TaskDetail,
    ): PlanningOptionalContext {
        if (detail.selectedContextKeys.isEmpty()) return PlanningOptionalContext()
        val assembler = modelContextAssembler ?: return PlanningOptionalContext()
        return try {
            val selectedKeys = detail.selectedContextKeys.map(::ModelContextKey)
            val allowance = ModelContextAllowance(
                capability = StructuredModelCapability.CreatePlans,
                lifecycles = setOf(ModelContextLifecycle.Task),
            )
            val request = ModelContextResolveRequest(
                actor = actor,
                allowance = allowance,
                taskId = detail.task.id.value.toString(),
                taskVersion = detail.task.revision,
                shadowedKeys = detail.requirements.mapNotNullTo(mutableSetOf()) { it.kind.profileContextKeyOrNull() },
            )
            val assembled = assembler.assemble(request, selectedKeys)
            PlanningOptionalContext(
                blocks = assembled.optionalContext,
                diagnostics = assembled.diagnostics.toAiRequestDiagnostics(),
            )
        } catch (_: IllegalArgumentException) {
            throw TaskDependencyUnavailableException("Planning is temporarily unavailable")
        }
    }

    private fun RequirementKind.profileContextKeyOrNull(): ModelContextKey? =
        when (this) {
            RequirementKind.TimeWindow -> ModelContextKey("profile.preference.time_window")
            RequirementKind.BudgetLimit -> ModelContextKey("profile.preference.budget_limit")
            RequirementKind.CommuteLimit -> ModelContextKey("profile.preference.commute_limit")
            RequirementKind.CommutePreference -> ModelContextKey("profile.preference.commute_mode")
            RequirementKind.Location -> ModelContextKey("profile.preference.location")
            RequirementKind.ActivityDomain -> ModelContextKey("profile.preference.activity_domain")
            RequirementKind.ActivityMode -> ModelContextKey("profile.preference.activity_mode")
            RequirementKind.Topic -> ModelContextKey("profile.preference.topic")
            RequirementKind.ExperiencePreference -> ModelContextKey("profile.preference.experience")
        }
}
