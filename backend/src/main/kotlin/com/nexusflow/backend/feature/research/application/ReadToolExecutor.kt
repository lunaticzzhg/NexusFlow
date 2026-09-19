package com.nexusflow.backend.feature.research.application

import kotlinx.coroutines.CancellationException

class ReadToolExecutor(
    private val catalog: ReadToolCatalog,
    val maxCallsPerTurn: Int = DEFAULT_MAX_CALLS_PER_TURN,
) {
    init {
        require(maxCallsPerTurn > 0) { "maxCallsPerTurn must be positive" }
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

        return calls.map { call ->
            val tool = catalog.tool(call.key)
                ?: throw IllegalArgumentException("Unknown read tool key: ${call.key.value}")
            val definition = tool.definition
            observer?.onStarted(call, definition)
            val outcome = try {
                tool.execute(call.arguments, context)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                observer?.onFinished(call, definition, ReadToolOutcome.Unavailable("read tool execution failed"))
                throw error
            }
            observer?.onFinished(call, definition, outcome)
            ReadToolExecution(
                call = call,
                outcome = outcome,
            )
        }
    }

    private companion object {
        const val DEFAULT_MAX_CALLS_PER_TURN = 4
    }
}
