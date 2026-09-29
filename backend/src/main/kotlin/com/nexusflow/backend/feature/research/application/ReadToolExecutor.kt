package com.nexusflow.backend.feature.research.application

import com.nexusflow.observability.StructuredLogger
import com.nexusflow.observability.logFields
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class ReadToolExecutor(
    private val catalog: ReadToolCatalog,
    val maxCallsPerTurn: Int = DEFAULT_MAX_CALLS_PER_TURN,
    private val maxConcurrentCalls: Int = maxCallsPerTurn,
    private val logger: StructuredLogger? = null,
) {
    init {
        require(maxCallsPerTurn > 0) { "maxCallsPerTurn must be positive" }
        require(maxConcurrentCalls > 0) { "maxConcurrentCalls must be positive" }
    }

    suspend fun execute(
        calls: List<ReadToolCall>,
        context: ReadToolExecutionContext,
        observer: ReadToolExecutionObserver? = null,
    ): List<ReadToolExecution> {
        require(calls.size <= maxCallsPerTurn) {
            "Read tool call count exceeds per-turn limit: $maxCallsPerTurn"
        }

        val duplicateCall = calls
            .groupBy { call -> call.key to call.arguments }
            .entries
            .firstOrNull { (_, duplicateCalls) -> duplicateCalls.size > 1 }
            ?.value
            ?.first()
        require(duplicateCall == null) {
            "Duplicate read tool call in same turn: ${duplicateCall?.key?.value}"
        }

        val semaphore = Semaphore(maxConcurrentCalls)
        return coroutineScope {
            calls.mapIndexed { index, call ->
                async {
                    semaphore.withPermit {
                        executeOne(call, index + 1, context, observer)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun executeOne(
        call: ReadToolCall,
        callIndex: Int,
        context: ReadToolExecutionContext,
        observer: ReadToolExecutionObserver?,
    ): ReadToolExecution {
        val tool = catalog.tool(call.key)
            ?: throw IllegalArgumentException("Unknown read tool key: ${call.key.value}")
        val definition = tool.definition
        observer?.onStarted(call, definition)
        val outcome = try {
            tool.execute(call.arguments, context)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            logger?.error(
                component = "read_tool",
                event = "read_tool_execution_failed",
                fields = logFields {
                    "response_run_id" value context.responseRunId
                    "conversation_id" value context.conversationId
                    "tool_key" value definition.key.value
                    "tool_call_index" value callIndex
                    "failure_stage" value "tool_execute"
                    "error_origin" value error.stackTrace.firstOrNull { it.className.startsWith("com.nexusflow.") }
                        ?.let { "${it.className}.${it.methodName}:${it.lineNumber}" }
                },
                cause = error,
            )
            observer?.onFinished(call, definition, ReadToolOutcome.Unavailable("read tool execution failed"))
            throw error
        }
        observer?.onFinished(call, definition, outcome)
        logger?.info(
            component = "read_tool",
            event = "read_tool_execution_finished",
            fields = logFields {
                "response_run_id" value context.responseRunId
                "conversation_id" value context.conversationId
                "tool_key" value definition.key.value
                "tool_call_index" value callIndex
                "outcome" value when (outcome) {
                    is ReadToolOutcome.Success -> "success"
                    ReadToolOutcome.Empty -> "empty"
                    is ReadToolOutcome.Unavailable -> "unavailable"
                    is ReadToolOutcome.MissingInput -> "missing_input"
                    is ReadToolOutcome.InvalidArguments -> "invalid_arguments"
                }
                "evidence_count" value (outcome as? ReadToolOutcome.Success)?.payload?.evidence?.size
            },
        )
        return ReadToolExecution(
            call = call,
            outcome = outcome,
        )
    }

    private companion object {
        const val DEFAULT_MAX_CALLS_PER_TURN = 4
    }
}
