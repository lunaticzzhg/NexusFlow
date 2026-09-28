package com.nexusflow.backend.feature.research.application

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
            calls.map { call ->
                async {
                    semaphore.withPermit {
                        executeOne(call, context, observer)
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun executeOne(
        call: ReadToolCall,
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
            observer?.onFinished(call, definition, ReadToolOutcome.Unavailable("read tool execution failed"))
            throw error
        }
        observer?.onFinished(call, definition, outcome)
        return ReadToolExecution(
            call = call,
            outcome = outcome,
        )
    }

    private companion object {
        const val DEFAULT_MAX_CALLS_PER_TURN = 4
    }
}
