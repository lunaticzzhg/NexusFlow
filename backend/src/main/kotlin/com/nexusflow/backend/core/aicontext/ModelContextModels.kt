package com.nexusflow.backend.core.aicontext

import com.nexusflow.ai.provider.StructuredModelCapability
import com.nexusflow.backend.core.identity.ActorContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@JvmInline
value class ModelContextKey(val value: String) {
    init {
        require(value.length in 1..MAX_CONTEXT_KEY_LENGTH) {
            "Model context key length must be between 1 and $MAX_CONTEXT_KEY_LENGTH"
        }
        require(CONTEXT_KEY_PATTERN.matches(value)) {
            "Model context key must be a lowercase dotted semantic name"
        }
    }
}

data class ModelContextDefinition(
    val key: ModelContextKey,
    val description: String,
    val selectionHint: String,
    val lifecycle: ModelContextLifecycle,
    val priority: ModelContextPriority,
    val maxContentChars: Int,
    val schemaVersion: Int,
    val allowedCapabilities: Set<StructuredModelCapability> = StructuredModelCapability.entries.toSet(),
) {
    init {
        require(description.isNotBlank()) { "Model context definition description must be nonblank" }
        require(selectionHint.isNotBlank()) { "Model context definition selectionHint must be nonblank" }
        require(maxContentChars > 0) { "Model context definition maxContentChars must be positive" }
        require(schemaVersion > 0) { "Model context definition schemaVersion must be positive" }
        require(allowedCapabilities.isNotEmpty()) { "Model context definition must allow at least one capability" }
    }
}

enum class ModelContextLifecycle {
    Request,
    Execution,
    Task,
}

enum class ModelContextPriority {
    Critical,
    High,
    Normal,
    Low,
}

@Serializable
enum class ModelContextTrust {
    UserProfile,
    TaskDerived,
    BackendAuthoritative,
    BackendDerived,
    ExternalFiltered,
}

data class ModelContextProvenance(
    val source: String,
    val sourceVersion: String? = null,
)

data class ResolvedModelContextBlock(
    val key: ModelContextKey,
    val trust: ModelContextTrust,
    val content: JsonObject,
    val provenance: ModelContextProvenance?,
    val priority: ModelContextPriority,
)

data class ModelContextAllowance(
    val capability: StructuredModelCapability,
    val lifecycles: Set<ModelContextLifecycle> = ModelContextLifecycle.entries.toSet(),
    val allowedKeys: Set<ModelContextKey>? = null,
) {
    init {
        require(lifecycles.isNotEmpty()) { "Model context allowance lifecycles must be nonempty" }
    }
}

data class ModelContextResolveRequest(
    val actor: ActorContext,
    val allowance: ModelContextAllowance,
    val taskId: String? = null,
    val taskVersion: Long? = null,
    val shadowedKeys: Set<ModelContextKey> = emptySet(),
)

@Serializable
data class ModelContextBlock(
    val key: String,
    val trust: ModelContextTrust,
    val content: JsonObject,
)

@Serializable
data class SelectableModelContextDefinition(
    val key: String,
    val description: String,
    val selectionHint: String,
)

fun ModelContextDefinition.toSelectable(): SelectableModelContextDefinition =
    SelectableModelContextDefinition(
        key = key.value,
        description = description,
        selectionHint = selectionHint,
    )

internal fun ModelContextDefinition.isAllowedBy(allowance: ModelContextAllowance): Boolean =
    allowance.capability in allowedCapabilities &&
        lifecycle in allowance.lifecycles &&
        allowance.allowedKeys?.let { key in it } != false

private const val MAX_CONTEXT_KEY_LENGTH = 128
private val CONTEXT_KEY_PATTERN = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")
