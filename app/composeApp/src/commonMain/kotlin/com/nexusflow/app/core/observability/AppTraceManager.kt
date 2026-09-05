package com.nexusflow.app.core.observability

import com.nexusflow.observability.CoroutineTraceContext
import com.nexusflow.observability.RandomTraceIdGenerator
import com.nexusflow.observability.TraceContext
import com.nexusflow.observability.TraceContextElement
import com.nexusflow.observability.TraceContextStorage
import com.nexusflow.observability.TraceId
import com.nexusflow.observability.TraceIdGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlin.time.TimeMark
import kotlin.time.TimeSource

interface AppTraceManager : TraceContext {
    suspend fun <T> withNewTrace(
        operation: String,
        trigger: String? = null,
        block: suspend () -> T,
    ): T

    suspend fun <T> withTrace(
        traceId: TraceId,
        operation: String? = null,
        block: suspend () -> T,
    ): T

    suspend fun <T> withNewResultTrace(
        operation: String,
        trigger: String? = null,
        block: suspend () -> Result<T>,
    ): Result<T>
}

object PassthroughAppTraceManager : AppTraceManager {
    override fun currentTraceId(): TraceId? = null

    override suspend fun <T> withNewTrace(
        operation: String,
        trigger: String?,
        block: suspend () -> T,
    ): T = block()

    override suspend fun <T> withTrace(
        traceId: TraceId,
        operation: String?,
        block: suspend () -> T,
    ): T = block()

    override suspend fun <T> withNewResultTrace(
        operation: String,
        trigger: String?,
        block: suspend () -> Result<T>,
    ): Result<T> = block()
}

internal class DefaultAppTraceManager(
    private val logger: AppLogger,
    private val traceIdGenerator: TraceIdGenerator = RandomTraceIdGenerator,
) : AppTraceManager {
    override fun currentTraceId(): TraceId? = CoroutineTraceContext.currentTraceId()

    override suspend fun <T> withNewTrace(
        operation: String,
        trigger: String?,
        block: suspend () -> T,
    ): T = withTrace(traceIdGenerator.newTraceId(), operation, trigger, block)

    override suspend fun <T> withTrace(
        traceId: TraceId,
        operation: String?,
        block: suspend () -> T,
    ): T = withTrace(traceId, operation, trigger = null, block)

    override suspend fun <T> withNewResultTrace(
        operation: String,
        trigger: String?,
        block: suspend () -> Result<T>,
    ): Result<T> =
        withInstalledTrace(traceIdGenerator.newTraceId()) {
            val started = TimeSource.Monotonic.markNow()
            try {
                logStarted(operation, trigger)
                val result = block()
                if (result.isSuccess) {
                    logFinished(operation, started)
                } else {
                    logFailed(operation, started, result.exceptionOrNull())
                }
                result
            } catch (cause: CancellationException) {
                logFailed(operation, started, cause)
                throw cause
            } catch (cause: Throwable) {
                logFailed(operation, started, cause)
                throw cause
            }
        }

    private suspend fun <T> withTrace(
        traceId: TraceId,
        operation: String?,
        trigger: String?,
        block: suspend () -> T,
    ): T =
        withInstalledTrace(traceId) {
            if (operation == null) {
                block()
            } else {
                val started = TimeSource.Monotonic.markNow()
                try {
                    logStarted(operation, trigger)
                    block().also {
                        logFinished(operation, started)
                    }
                } catch (cause: CancellationException) {
                    logFailed(operation, started, cause)
                    throw cause
                } catch (cause: Throwable) {
                    logFailed(operation, started, cause)
                    throw cause
                }
            }
        }

    private suspend fun <T> withInstalledTrace(
        traceId: TraceId,
        block: suspend () -> T,
    ): T =
        withContext(TraceContextElement(traceId)) {
            val previousTraceId = TraceContextStorage.replace(traceId)
            try {
                block()
            } finally {
                TraceContextStorage.replace(previousTraceId)
            }
        }

    private fun logStarted(
        operation: String,
        trigger: String?,
    ) {
        logger.info(
            tag = OperationLogTag,
            event = "operation_started",
            fields =
                logFields {
                    "operation" value operation
                    "trigger" value trigger
                },
        )
    }

    private fun logFinished(
        operation: String,
        started: TimeMark,
    ) {
        logger.info(
            tag = OperationLogTag,
            event = "operation_finished",
            fields =
                logFields {
                    "operation" value operation
                    "duration_ms" value started.elapsedNow().inWholeMilliseconds
                },
        )
    }

    private fun logFailed(
        operation: String,
        started: TimeMark,
        cause: Throwable?,
    ) {
        logger.error(
            tag = OperationLogTag,
            event = "operation_failed",
            fields =
                logFields {
                    "operation" value operation
                    "duration_ms" value started.elapsedNow().inWholeMilliseconds
                },
            cause = cause,
        )
    }

    private companion object {
        val OperationLogTag = LogTag.of("operation")
    }
}
