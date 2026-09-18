package com.nexusflow.ai.provider

import com.nexusflow.contracts.backendai.common.StructuredModelCapability
import com.nexusflow.contracts.backendai.common.StructuredModelRequestDiagnostics
import com.nexusflow.contracts.backendai.common.StructuredModelUsage
import kotlinx.serialization.json.JsonObject

fun interface StreamingTextModelProvider {
    suspend fun stream(
        request: TextModelRequest,
        onDelta: suspend (String) -> Unit,
    ): TextModelResult
}

data class TextModelRequest(
    val systemPrompt: String,
    val userPayload: JsonObject,
    val metadata: TextModelRequestMetadata,
)

data class TextModelRequestMetadata(
    val requestId: String,
    val promptVersion: String,
    val capability: StructuredModelCapability,
    val attemptNumber: Int,
    val diagnostics: StructuredModelRequestDiagnostics = StructuredModelRequestDiagnostics(),
)

data class TextModelResult(
    val outputText: String,
    val metadata: TextModelResultMetadata,
)

data class TextModelResultMetadata(
    val provider: String,
    val model: String,
    val providerRequestId: String?,
    val attemptCount: Int,
    val usage: StructuredModelUsage? = null,
    val finishCategory: StructuredModelFinishCategory = StructuredModelFinishCategory.Complete,
    val requestDiagnostics: StructuredModelRequestDiagnostics = StructuredModelRequestDiagnostics(),
)
