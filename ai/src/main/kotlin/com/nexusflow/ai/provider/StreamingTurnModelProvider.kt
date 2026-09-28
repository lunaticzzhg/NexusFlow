package com.nexusflow.ai.provider

import com.nexusflow.contracts.backendai.common.StructuredModelCapability
import com.nexusflow.contracts.backendai.common.StructuredModelRequestDiagnostics
import com.nexusflow.contracts.backendai.common.StructuredModelUsage
import kotlinx.serialization.json.JsonObject

fun interface StreamingTurnModelProvider {
    suspend fun streamTurn(
        request: TurnModelRequest,
        onTextDelta: suspend (String) -> Unit,
    ): TurnModelResult
}

data class TurnModelRequest(
    val systemPrompt: String,
    val userPayload: JsonObject,
    val tools: List<TurnModelTool>,
    val metadata: TurnModelRequestMetadata,
)

data class TurnModelRequestMetadata(
    val requestId: String,
    val promptVersion: String,
    val capability: StructuredModelCapability,
    val attemptNumber: Int,
    val diagnostics: StructuredModelRequestDiagnostics = StructuredModelRequestDiagnostics(),
)

data class TurnModelTool(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

sealed interface TurnModelResult {
    val metadata: TurnModelResultMetadata

    data class Text(
        val text: String,
        override val metadata: TurnModelResultMetadata,
    ) : TurnModelResult

    data class ToolCall(
        val name: String,
        val argumentsJson: String,
        override val metadata: TurnModelResultMetadata,
    ) : TurnModelResult
}

data class TurnModelResultMetadata(
    val provider: String,
    val model: String,
    val providerRequestId: String?,
    val attemptCount: Int,
    val usage: StructuredModelUsage? = null,
    val finishCategory: StructuredModelFinishCategory = StructuredModelFinishCategory.Complete,
    val requestDiagnostics: StructuredModelRequestDiagnostics = StructuredModelRequestDiagnostics(),
)
