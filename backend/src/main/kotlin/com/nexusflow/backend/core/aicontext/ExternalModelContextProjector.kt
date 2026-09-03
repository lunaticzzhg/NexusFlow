package com.nexusflow.backend.core.aicontext

import com.nexusflow.backend.core.identity.ActorContext
import kotlinx.serialization.json.JsonObject

/**
 * Marker for source-owned, typed external payloads after protocol/tool decoding.
 *
 * Raw MCP/API strings, JsonObject values, or maps are intentionally not model
 * context sources; each integration must first decode into a narrow DTO.
 */
interface ExternalModelContextSourcePayload

interface ExternalModelContextProjector<in T : ExternalModelContextSourcePayload> {
    fun project(
        source: T,
        request: ExternalContextProjectionRequest,
    ): ResolvedModelContextBlock?
}

data class ExternalContextProjectionRequest(
    val actor: ActorContext,
    val definition: ModelContextDefinition,
    val maxItems: Int,
    val maxTextChars: Int,
) {
    init {
        require(maxItems > 0) { "External context maxItems must be positive" }
        require(maxTextChars > 0) { "External context maxTextChars must be positive" }
        require(maxTextChars <= definition.maxContentChars) {
            "External context maxTextChars must not exceed definition maxContentChars"
        }
    }
}

fun externalFilteredModelContextBlock(
    definition: ModelContextDefinition,
    content: JsonObject,
    provenance: ModelContextProvenance,
): ResolvedModelContextBlock =
    ResolvedModelContextBlock(
        key = definition.key,
        trust = ModelContextTrust.ExternalFiltered,
        content = content,
        provenance = provenance,
        priority = definition.priority,
    )

fun distillExternalText(
    value: String?,
    maxChars: Int,
): String? {
    require(maxChars > 0) { "External text maxChars must be positive" }
    val cleaned = value
        ?.replace(markupTagRegex, " ")
        ?.replace(controlCharRegex, " ")
        ?.replace(markdownFenceRegex, " ")
        ?.replace(whitespaceRegex, " ")
        ?.trim()
        ?.take(maxChars)
        ?.trim()

    return cleaned?.ifBlank { null }
}

fun <T> List<T>.takeExternalContextItems(maxItems: Int): List<T> {
    require(maxItems > 0) { "External context maxItems must be positive" }
    return take(maxItems)
}

private val markupTagRegex = Regex("<[^>]+>")
private val controlCharRegex = Regex("[\\u0000-\\u001F\\u007F]")
private val markdownFenceRegex = Regex("```+")
private val whitespaceRegex = Regex("\\s+")
