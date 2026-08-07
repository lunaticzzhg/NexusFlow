package com.nexusflow.contracts.planning

import java.time.Instant

/** AI output boundary. This describes a proposal, never permission to execute an external action. */
data class PlanProposal(
    val schemaVersion: Int = 1,
    val taskId: String,
    val title: String,
    val summary: String,
    val generatedAt: Instant,
    val options: List<PlanOption>,
    val modelRun: ModelRunMetadata,
) {
    init {
        require(schemaVersion > 0) { "schemaVersion must be positive" }
        require(taskId.isNotBlank()) { "taskId must not be blank" }
        require(title.isNotBlank()) { "title must not be blank" }
        require(summary.isNotBlank()) { "summary must not be blank" }
        require(options.isNotEmpty()) { "at least one plan option is required" }
    }
}

data class PlanOption(
    val optionId: String,
    val rank: Int,
    val title: String,
    val summary: String,
    val items: List<PlanItem>,
    val rationale: List<String>,
    val actionRequests: List<ActionRequest> = emptyList(),
) {
    init {
        require(optionId.isNotBlank()) { "optionId must not be blank" }
        require(rank > 0) { "rank must be positive" }
        require(title.isNotBlank()) { "title must not be blank" }
        require(items.isNotEmpty()) { "a plan option must have at least one item" }
    }
}

data class PlanItem(
    val itemId: String,
    val title: String,
    val domain: OpportunityDomain,
    val startAt: Instant,
    val endAt: Instant,
    val estimatedCost: Money? = null,
    val reasons: List<String> = emptyList(),
    val sources: List<SourceReference> = emptyList(),
) {
    init {
        require(itemId.isNotBlank()) { "itemId must not be blank" }
        require(title.isNotBlank()) { "title must not be blank" }
        require(!endAt.isBefore(startAt)) { "endAt must not be before startAt" }
    }
}

enum class OpportunityDomain {
    SPORTS,
    MOVIES,
    LOCAL_EVENTS,
    MARKET_INTEL,
}

data class Money(
    val amountMinor: Long,
    val currency: String,
) {
    init {
        require(amountMinor >= 0) { "amountMinor must not be negative" }
        require(currency.matches(Regex("[A-Z]{3}"))) { "currency must be ISO-4217 uppercase code" }
    }
}

data class SourceReference(
    val sourceId: String,
    val label: String,
    val url: String,
    val retrievedAt: Instant,
) {
    init {
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        require(label.isNotBlank()) { "label must not be blank" }
        require(url.startsWith("https://")) { "source url must use https" }
    }
}

data class ActionRequest(
    val actionId: String,
    val type: ActionType,
    val summary: String,
    val requiresApproval: Boolean = true,
) {
    init {
        require(actionId.isNotBlank()) { "actionId must not be blank" }
        require(summary.isNotBlank()) { "summary must not be blank" }
    }
}

enum class ActionType {
    CREATE_CALENDAR_EVENT,
    CREATE_REMINDER,
    SEND_NOTIFICATION,
}

data class ModelRunMetadata(
    val provider: String,
    val model: String,
    val promptVersion: String,
) {
    init {
        require(provider.isNotBlank()) { "provider must not be blank" }
        require(model.isNotBlank()) { "model must not be blank" }
        require(promptVersion.isNotBlank()) { "promptVersion must not be blank" }
    }
}
