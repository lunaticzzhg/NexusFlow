package com.nexusflow.ai.runtime

import com.nexusflow.ai.provider.InvalidStructuredOutputException
import com.nexusflow.ai.provider.StreamingTurnModelProvider
import com.nexusflow.ai.provider.TurnModelRequest
import com.nexusflow.ai.provider.TurnModelResult
import com.nexusflow.contracts.backendai.common.InvalidCapabilityResultException
import com.nexusflow.observability.StructuredLogger
import com.nexusflow.observability.logFields
import kotlinx.coroutines.CancellationException

/** One local correction for a successful provider call whose turn candidate is invalid. */
class StreamingTurnCapabilityRunner(
    private val provider: StreamingTurnModelProvider,
    private val logger: StructuredLogger? = null,
) {
    suspend fun <T> execute(
        request: (Int) -> TurnModelRequest,
        onDelta: suspend (String) -> Unit,
        decode: (TurnModelResult) -> T,
        salvage: (TurnModelResult) -> T? = { null },
    ): T {
        var attempt = 1
        while (true) {
            val modelRequest = request(attempt)
            var candidate: TurnModelResult? = null
            try {
                candidate = provider.streamTurn(modelRequest, onDelta)
                val decoded = decode(candidate)
                if (attempt == 2) logCorrectionResult(modelRequest, "accepted", null)
                return decoded
            } catch (error: CancellationException) {
                throw error
            } catch (error: InvalidStructuredOutputException) {
                if (attempt == 2) {
                    logCorrectionResult(modelRequest, "exhausted", error.failureStage)
                    throw error
                }
                logCorrection(modelRequest, error.failureStage ?: "provider_invalid_turn_output")
                attempt++
            } catch (error: InvalidCapabilityResultException) {
                if (attempt == 2) {
                    candidate?.let { result ->
                        salvage(result)?.let { recovered ->
                            logCorrectionResult(modelRequest, "salvaged_research", error.failureStage)
                            return recovered
                        }
                    }
                    logCorrectionResult(modelRequest, "exhausted", error.failureStage)
                    throw error
                }
                logCorrection(modelRequest, error.failureStage ?: "invalid_turn_candidate")
                attempt++
            }
        }
    }

    private fun logCorrection(request: TurnModelRequest, stage: String) {
        logger?.warn(
            component = "ai",
            event = "conversation_turn_correction",
            fields = logFields {
                "ai_request_id" value request.metadata.requestId
                "attempt" value request.metadata.attemptNumber
                "failure_stage" value stage
                "failure_category" value "invalid_turn_candidate"
                "recovery_action" value "bounded_correction"
            },
        )
    }

    private fun logCorrectionResult(request: TurnModelRequest, outcome: String, stage: String?) {
        logger?.warn(
            component = "ai",
            event = "conversation_turn_correction_result",
            fields = logFields {
                "ai_request_id" value request.metadata.requestId
                "attempt" value request.metadata.attemptNumber
                "failure_category" value "invalid_turn_candidate"
                "failure_stage" value stage
                "recovery_action" value "bounded_correction"
                "recovery_outcome" value outcome
            },
        )
    }
}
