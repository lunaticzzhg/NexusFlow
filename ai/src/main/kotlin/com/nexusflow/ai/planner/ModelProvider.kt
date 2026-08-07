package com.nexusflow.ai.planner

/**
 * Model-provider boundary. Implementations may call an LLM in a separate
 * runtime, but this core interface stays synchronous, deterministic in tests,
 * and free of transport concerns.
 */
fun interface ModelProvider {
    fun propose(context: PlanningContext): PlanProposal
}

/** Safe deterministic development provider; it never calls an external model. */
class DeterministicStubModelProvider : ModelProvider {
    override fun propose(context: PlanningContext): PlanProposal {
        val selected = context.opportunities.firstOrNull()
        val action = RequestedAction(
            type = RequestedActionType.CREATE_REMINDER,
            displayName = "创建观赛/观影提醒",
            parameters = mapOf("taskId" to context.taskId),
        )
        val option = PlanOption(
            id = "option-primary",
            title = selected?.title ?: "根据你的偏好安排本周末",
            summary = selected?.let { "围绕「${it.title}」安排，并保留可调整空间。" }
                ?: "目前没有足够的机会数据，先确认时间与预算后再安排。",
            estimatedCost = selected?.estimatedCost ?: context.budget?.maximumAmount,
            currency = context.budget?.currency,
            referencedOpportunityIds = selected?.let { listOf(it.id) } ?: emptyList(),
            requestedActions = listOf(action),
        )
        return PlanProposal(
            title = "个性化休闲计划",
            rationale = listOf("根据当前请求生成", "任务：${context.request}"),
            requiresApproval = true,
            options = listOf(option),
            followUpQuestions = if (selected == null) listOf("你更偏好哪一天和什么时间段？") else emptyList(),
            riskLabels = setOf(RiskLabel.EXTERNAL_WRITE) +
                (if (selected == null) setOf(RiskLabel.INCOMPLETE_CONTEXT) else emptySet()),
        )
    }
}
