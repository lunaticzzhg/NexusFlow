package com.nexusflow.backend.feature.conversation.application

import com.nexusflow.backend.core.observability.OperationLogContext
import com.nexusflow.backend.core.observability.addOperationFields
import com.nexusflow.backend.feature.responserun.domain.ClaimNextResponseRunCommand
import com.nexusflow.backend.feature.responserun.domain.ClaimedResponseRun
import com.nexusflow.backend.feature.responserun.domain.ConsumeResponseRunIgnoreReason
import com.nexusflow.backend.feature.responserun.domain.ConsumeResponseRunResult
import com.nexusflow.backend.feature.responserun.domain.HeartbeatResponseRunLeaseCommand
import com.nexusflow.backend.feature.responserun.domain.MarkResponseRunRetryableCommand
import com.nexusflow.backend.feature.responserun.domain.ResponseRun
import com.nexusflow.backend.feature.responserun.domain.ResponseRunFailureCategory
import com.nexusflow.backend.feature.responserun.domain.ResponseRunResult
import com.nexusflow.backend.feature.responserun.domain.ResponseRunResultPayload
import com.nexusflow.backend.feature.responserun.domain.ResponseRunResultType
import com.nexusflow.backend.feature.responserun.domain.ResponseRunStage
import com.nexusflow.backend.feature.responserun.domain.ResponseRunResultStore
import com.nexusflow.backend.feature.responserun.domain.ResponseRunStatus
import com.nexusflow.backend.feature.responserun.domain.ResponseRunStore
import com.nexusflow.backend.feature.responserun.domain.StoreResponseRunResult
import com.nexusflow.backend.feature.responserun.domain.StoreResponseRunResultCommand
import com.nexusflow.contracts.backendai.common.AiCapabilityException
import com.nexusflow.observability.RandomTraceIdGenerator
import com.nexusflow.observability.StructuredLogger
import com.nexusflow.observability.TraceContextElement
import com.nexusflow.observability.TraceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Duration
import java.time.Duration.between
import java.util.UUID

class ResponseRunWorker(
    private val responseRunStore: ResponseRunStore,
    private val resultStore: ResponseRunResultStore,
    private val processor: ConversationTurnProcessor,
    private val resultConsumer: ResponseRunResultConsumer,
    private val config: ResponseRunWorkerConfig,
    private val realtimeHub: ResponseRunRealtimeHub? = null,
    private val logger: StructuredLogger? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val workerId: String = "response-run-worker-${UUID.randomUUID()}",
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : AutoCloseable {
    private var loopJob: Job? = null

    fun start() {
        if (loopJob != null) return
        loopJob = scope.launch {
            while (isActive) {
                val claimed = runOnce()
                if (!claimed) delay(config.pollInterval.toMillis())
            }
        }
    }

    suspend fun runOnce(): Boolean {
        val claimed = responseRunStore.claimNextResponseRun(
            ClaimNextResponseRunCommand(
                workerId = workerId,
                now = clock.instant(),
                leaseDuration = config.leaseDuration,
                maxAttempts = config.maxAttempts,
            ),
        ) ?: return false
        withContext(TraceContextElement(claimed.run.workerTraceId())) {
            logClaimed(claimed)
            executeClaimedRun(claimed)
        }
        return true
    }

    private suspend fun executeClaimedRun(claimed: ClaimedResponseRun) =
        coroutineScope {
            realtimeHub?.recordRun(claimed.run, "Response run processing")
            logger?.info(
                component = LOG_COMPONENT,
                event = "response_run_processing_started",
                fields = claimed.run.baseLogFields(step = "started", outcome = "started"),
            )
            val leaseLost = CompletableDeferred<ResponseRunLeaseLostException>()
            val heartbeat = launch {
                while (isActive) {
                    delay(config.heartbeatInterval.toMillis())
                    val renewed = responseRunStore.heartbeatResponseRunLease(
                        HeartbeatResponseRunLeaseCommand(
                            responseRunId = claimed.run.id,
                            attempt = claimed.run.attempt,
                            workerId = workerId,
                            now = clock.instant(),
                            leaseDuration = config.leaseDuration,
                        ),
                    )
                    if (renewed) {
                        logger?.debug(
                            component = LOG_COMPONENT,
                            event = "response_run_lease_renewed",
                            fields = logFields {
                                addRunFields(claimed.run, step = "lease_renewed", outcome = "renewed")
                                "lease_duration_ms" value config.leaseDuration.toMillis()
                            },
                        )
                    } else {
                        logger?.error(
                            component = LOG_COMPONENT,
                            event = "response_run_lease_lost",
                            fields = logFields {
                                addRunFields(claimed.run, step = "lease_lost", outcome = "failed")
                                "lease_duration_ms" value config.leaseDuration.toMillis()
                            },
                        )
                        leaseLost.complete(ResponseRunLeaseLostException(claimed.run.id.value.toString(), claimed.run.attempt))
                        return@launch
                    }
                }
            }
            val processorResult = async {
                runCatching {
                    processor.process(claimed)
                }
            }
            try {
                val startedAt = clock.instant()
                val payload = select<ResponseRunResultPayload> {
                    processorResult.onAwait { it.getOrThrow() }
                    leaseLost.onAwait { throw it }
                }
                val finishedAt = clock.instant()
                logger?.debug(
                    component = LOG_COMPONENT,
                    event = "response_run_processor_finished",
                    fields = logFields {
                        addRunFields(claimed.run, branch = payload.resultType().operationBranch(), step = "processor_finished")
                        "result_type" value payload.resultType().logValue()
                        "outcome" value payload.resultType().logValue()
                        "duration_ms" value between(startedAt, finishedAt).toMillis()
                    },
                )
                val stored = resultStore.storeResponseRunResult(
                    StoreResponseRunResultCommand(
                        responseRunId = claimed.run.id,
                        attempt = claimed.run.attempt,
                        payload = payload,
                        now = finishedAt,
                    ),
                )
                when (stored) {
                    is StoreResponseRunResult.Stored -> {
                        logResultStored(claimed.run, stored.result, "stored")
                        consumeStoredResult(claimed, stored.result)
                    }
                    is StoreResponseRunResult.Existing -> {
                        logResultStored(claimed.run, stored.result, "existing")
                        consumeStoredResult(claimed, stored.result)
                    }
                    StoreResponseRunResult.StaleAttempt -> {
                        logger?.warn(
                            component = LOG_COMPONENT,
                            event = "response_run_result_stale_attempt",
                            fields = logFields {
                                addRunFields(claimed.run, step = "result_stale_attempt", outcome = "stale_attempt")
                                "store_outcome" value "stale_attempt"
                            },
                        )
                        responseRunStore.findResponseRun(claimed.run.id)?.let {
                            logStateObserved(it)
                            realtimeHub?.recordRun(it)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                val now = clock.instant()
                val failureCategory = error.toFailureCategory()
                val retryable = responseRunStore.markResponseRunRetryable(
                    MarkResponseRunRetryableCommand(
                        responseRunId = claimed.run.id,
                        attempt = claimed.run.attempt,
                        now = now,
                        retryAt = now.plus(config.retryBackoff),
                        failureCategory = failureCategory,
                    )
                )
                if (retryable) {
                    logger?.warn(
                        component = LOG_COMPONENT,
                        event = "response_run_retry_scheduled",
                        fields = logFields {
                            addRunFields(claimed.run, step = "retry", outcome = "retry_scheduled")
                            "failure_category" value failureCategory.logValue()
                            "retry_at" value now.plus(config.retryBackoff).toString()
                        },
                    )
                    responseRunStore.findResponseRun(claimed.run.id)?.let {
                        logStateObserved(it)
                        realtimeHub?.recordRun(it, "Response run will retry")
                    }
                } else {
                    responseRunStore.findResponseRun(claimed.run.id)?.let {
                        logTerminal(it)
                        logStateObserved(it)
                        realtimeHub?.recordRun(it)
                    }
                }
            } finally {
                processorResult.cancelAndJoin()
                heartbeat.cancelAndJoin()
            }
        }

    private suspend fun consumeStoredResult(
        claimed: ClaimedResponseRun,
        result: ResponseRunResult,
    ) {
        logger?.debug(
            component = LOG_COMPONENT,
            event = "response_run_result_consume_started",
            fields = resultLogFields(claimed.run, result, step = "result_consume_started"),
        )
        when (val consumed = resultConsumer.consume(result)) {
            is ConsumeResponseRunResult.Consumed -> {
                logger?.debug(
                    component = LOG_COMPONENT,
                    event = "response_run_result_consumed",
                    fields = resultLogFields(claimed.run, result, outcome = "consumed", step = "result_consumed"),
                )
                logPlanningQueued(claimed.run, consumed.detail.responseRuns)
                val current = responseRunStore.findResponseRun(claimed.run.id)
                    ?: error("response run missing after consumed result")
                logStateObserved(current)
                realtimeHub?.recordRun(current)
                requireTerminalConvergence(current, result)
            }
            is ConsumeResponseRunResult.AlreadyConsumed -> {
                val current = responseRunStore.findResponseRun(claimed.run.id)
                    ?: error("response run missing after already-consumed result")
                logger?.debug(
                    component = LOG_COMPONENT,
                    event = "response_run_result_already_consumed",
                    fields = resultLogFields(claimed.run, result, outcome = "already_consumed", step = "result_consumed") {
                        "already_consumed_reason" value consumed.reason
                    },
                )
                logStateObserved(current)
                realtimeHub?.recordRun(current)
                requireTerminalConvergence(current, result)
            }
            is ConsumeResponseRunResult.Ignored -> handleIgnoredConsumption(claimed, result, consumed.reason)
        }
    }

    private suspend fun handleIgnoredConsumption(
        claimed: ClaimedResponseRun,
        result: ResponseRunResult,
        reason: ConsumeResponseRunIgnoreReason,
    ) {
        val current = responseRunStore.findResponseRun(claimed.run.id)
        val fields = resultLogFields(claimed.run, result, outcome = "ignored", step = "result_ignored") {
            "ignore_reason" value reason.logValue()
            "current_status" value current?.status?.logValue()
        }
        if (reason.isAcceptableStaleOrTerminal(current, result)) {
            logger?.warn(
                component = LOG_COMPONENT,
                event = "response_run_result_ignored",
                fields = fields,
            )
            current?.let {
                logStateObserved(it)
                realtimeHub?.recordRun(it)
            }
            return
        }
        logger?.error(
            component = LOG_COMPONENT,
            event = "response_run_result_ignored",
            fields = fields,
        )
        throw ResponseRunResultConsumptionException(reason)
    }

    private fun requireTerminalConvergence(
        current: ResponseRun,
        result: ResponseRunResult,
    ) {
        if (
            result.resultType == ResponseRunResultType.ConversationAnswer &&
            (current.status == ResponseRunStatus.Processing || current.status == ResponseRunStatus.Streaming)
        ) {
            logger?.error(
                component = LOG_COMPONENT,
                event = "response_run_terminal_convergence_failed",
                fields = resultLogFields(current, result) {
                    "step" value "terminal_convergence"
                    "outcome" value "failed"
                    "status" value current.status.logValue()
                },
            )
            error("conversation answer result consumed without terminal response run convergence")
        }
        logTerminal(current)
    }

    private fun logClaimed(claimed: ClaimedResponseRun) {
        logger?.debug(
            component = LOG_COMPONENT,
            event = "response_run_claimed",
            fields = logFields {
                addRunFields(claimed.run, step = "claimed", outcome = "claimed")
                "status" value claimed.run.status.logValue()
                "deadline_at" value claimed.run.deadlineAt.toString()
                "lease_expires_at" value claimed.run.leaseExpiresAt?.toString()
            },
        )
    }

    private fun logResultStored(
        run: ResponseRun,
        result: ResponseRunResult,
        outcome: String,
    ) {
        logger?.debug(
            component = LOG_COMPONENT,
            event = "response_run_result_stored",
            fields = resultLogFields(run, result) {
                "step" value "result_stored"
                "outcome" value outcome
                "store_outcome" value outcome
            },
        )
    }

    private fun logStateObserved(run: ResponseRun) {
        logger?.debug(
            component = LOG_COMPONENT,
            event = "response_run_state_observed",
            fields = logFields {
                addRunFields(run, step = "state_observed", outcome = run.status.logValue())
                "status" value run.status.logValue()
                "assistant_message_id" value run.assistantMessageId?.value?.toString()
                "failure_category" value run.failureCategory?.logValue()
                "completed_at" value run.completedAt?.toString()
            },
        )
    }

    private fun logPlanningQueued(
        sourceRun: ResponseRun,
        runs: List<ResponseRun>,
    ) {
        runs
            .asSequence()
            .filter { run ->
                run.id == sourceRun.id &&
                    run.stage == ResponseRunStage.Planning &&
                    run.status == ResponseRunStatus.Queued
            }
            .forEach { run ->
                logger?.info(
                    component = LOG_COMPONENT,
                    event = "response_run_planning_queued",
                    fields = logFields {
                        addRunFields(run, branch = OPERATION_BRANCH_PLANNING, step = "planning_queued", outcome = "queued")
                        "status" value run.status.logValue()
                        "source_response_run_id" value sourceRun.id.value.toString()
                    },
                )
            }
    }

    private fun logTerminal(run: ResponseRun) {
        val event = when (run.status) {
            ResponseRunStatus.Completed -> "response_run_completed"
            ResponseRunStatus.Failed -> "response_run_failed"
            ResponseRunStatus.Cancelled -> "response_run_cancelled"
            ResponseRunStatus.TimedOut -> "response_run_timed_out"
            ResponseRunStatus.Queued,
            ResponseRunStatus.Processing,
            ResponseRunStatus.Streaming,
            ResponseRunStatus.FailedRetryable,
            -> return
        }
        logger?.info(
            component = LOG_COMPONENT,
            event = event,
            fields = logFields {
                addRunFields(run, branch = run.terminalBranch(), step = run.status.terminalStep(), outcome = run.status.logValue())
                "status" value run.status.logValue()
                "assistant_message_id" value run.assistantMessageId?.value?.toString()
                "duration_ms" value run.startedAt?.let { between(it, run.completedAt ?: run.updatedAt).toMillis() }
            },
        )
    }

    override fun close() {
        loopJob?.cancel()
        loopJob = null
        scope.cancel()
    }
}

class ResponseRunResultConsumptionException(
    val reason: ConsumeResponseRunIgnoreReason,
) : IllegalStateException("response run result consumption ignored: ${reason.logValue()}")

class ResponseRunLeaseLostException(
    responseRunId: String,
    attempt: Int,
) : IllegalStateException("response run lease lost: responseRunId=$responseRunId attempt=$attempt")

private fun Throwable.toFailureCategory(): ResponseRunFailureCategory =
    when (this) {
        is AiCapabilityException -> ResponseRunFailureCategory.ProviderTemporary
        is ResponseRunLeaseLostException -> ResponseRunFailureCategory.WorkerLost
        else -> ResponseRunFailureCategory.InternalInvariant
    }

private const val LOG_COMPONENT = "response_run_worker"

private fun logFields(
    block: com.nexusflow.observability.LogFieldsBuilder.() -> Unit,
) = com.nexusflow.observability.logFields(block)

private fun com.nexusflow.observability.LogFieldsBuilder.addRunFields(
    run: ResponseRun,
    branch: String? = run.operationBranch(),
    step: String? = null,
    outcome: String? = null,
) {
    addOperationFields(run.operationLogContext(branch), step = step, outcome = outcome)
    "response_run_id" value run.id.value.toString()
    "conversation_id" value run.conversationId.value.toString()
    "task_id" value run.expectedTaskId?.value?.toString()
    "user_message_id" value run.userMessageId.value.toString()
    "origin_trace_id" value run.originTraceId
    "turn_index" value run.turnIndex
    "attempt" value run.attempt
    "worker_id" value run.leaseOwner
}

private fun ResponseRun.operationLogContext(branch: String? = operationBranch()): OperationLogContext =
    OperationLogContext(
        operationType = OPERATION_TYPE_CONVERSATION_TURN,
        operationId = id.value.toString(),
        branch = branch,
        stage = stage.logValue(),
    )

private fun ResponseRun.operationBranch(): String? =
    when (stage) {
        ResponseRunStage.Planning -> OPERATION_BRANCH_PLANNING
        ResponseRunStage.Turn -> null
    }

private fun ResponseRun.terminalBranch(): String? =
    when {
        stage == ResponseRunStage.Planning -> OPERATION_BRANCH_PLANNING
        status == ResponseRunStatus.Completed && assistantMessageId != null -> OPERATION_BRANCH_CHAT_ANSWER
        else -> operationBranch()
    }

private fun ResponseRun.workerTraceId(): TraceId =
    originTraceId?.let(TraceId::parse) ?: RandomTraceIdGenerator.newTraceId()

private fun ResponseRun.baseLogFields(
    step: String? = null,
    outcome: String? = null,
) =
    logFields {
        addRunFields(this@baseLogFields, step = step, outcome = outcome)
    }

private fun resultLogFields(
    run: ResponseRun,
    result: ResponseRunResult,
    outcome: String? = null,
    step: String? = null,
    extra: com.nexusflow.observability.LogFieldsBuilder.() -> Unit = {},
) = logFields {
    addRunFields(run, branch = result.resultType.operationBranch(), step = step, outcome = outcome)
    "result_type" value result.resultType.logValue()
    "ai_request_id" value result.payload.aiRequestId()
    "consume_outcome" value outcome
    extra()
}

private fun ResponseRunResultPayload.resultType(): ResponseRunResultType =
    when (this) {
        is ResponseRunResultPayload.ConversationAnswer -> ResponseRunResultType.ConversationAnswer
        is ResponseRunResultPayload.PlanningUnderstanding -> ResponseRunResultType.PlanningUnderstanding
        is ResponseRunResultPayload.PlanningResult -> ResponseRunResultType.PlanningResult
    }

private fun ResponseRunResultType.operationBranch(): String =
    when (this) {
        ResponseRunResultType.ConversationAnswer -> OPERATION_BRANCH_CHAT_ANSWER
        ResponseRunResultType.PlanningUnderstanding,
        ResponseRunResultType.PlanningResult,
        -> OPERATION_BRANCH_PLANNING
    }

private fun ResponseRunResultPayload.aiRequestId(): String =
    when (this) {
        is ResponseRunResultPayload.ConversationAnswer -> aiRequestId
        is ResponseRunResultPayload.PlanningUnderstanding -> aiRequestId
        is ResponseRunResultPayload.PlanningResult -> aiRequestId
    }

private fun ConsumeResponseRunIgnoreReason.isAcceptableStaleOrTerminal(
    current: ResponseRun?,
    result: ResponseRunResult,
): Boolean =
    when (this) {
        ConsumeResponseRunIgnoreReason.AttemptMismatch -> current != null && current.attempt != result.attempt
        ConsumeResponseRunIgnoreReason.RunNotConsumable,
        -> current?.status?.isTerminalOrRetryable() == true
        ConsumeResponseRunIgnoreReason.ResultMissing,
        ConsumeResponseRunIgnoreReason.ResultTypeMismatch,
        ConsumeResponseRunIgnoreReason.RunMissing,
        ConsumeResponseRunIgnoreReason.PayloadConversationMismatch,
        ConsumeResponseRunIgnoreReason.PayloadUserMessageMismatch,
        ConsumeResponseRunIgnoreReason.ConversationMissing,
        ConsumeResponseRunIgnoreReason.UserMessageMissing,
        ConsumeResponseRunIgnoreReason.UserMessageRoleMismatch,
        ConsumeResponseRunIgnoreReason.AiRequestMismatch,
        ConsumeResponseRunIgnoreReason.TurnIndexMismatch,
        ConsumeResponseRunIgnoreReason.AssistantMessageConflict,
        ConsumeResponseRunIgnoreReason.PlanningPreconditionMismatch,
        ConsumeResponseRunIgnoreReason.TaskRevisionMismatch,
        ConsumeResponseRunIgnoreReason.DetailReloadFailed,
        -> false
    }

private fun ResponseRunStatus.isTerminalOrRetryable(): Boolean =
    when (this) {
        ResponseRunStatus.Completed,
        ResponseRunStatus.FailedRetryable,
        ResponseRunStatus.Failed,
        ResponseRunStatus.TimedOut,
        ResponseRunStatus.Cancelled,
        -> true
        ResponseRunStatus.Queued,
        ResponseRunStatus.Processing,
        ResponseRunStatus.Streaming,
        -> false
    }

private fun ResponseRunStatus.terminalStep(): String =
    when (this) {
        ResponseRunStatus.Completed -> "finished"
        ResponseRunStatus.Failed -> "failed"
        ResponseRunStatus.Cancelled -> "cancelled"
        ResponseRunStatus.TimedOut -> "timed_out"
        ResponseRunStatus.Queued,
        ResponseRunStatus.Processing,
        ResponseRunStatus.Streaming,
        ResponseRunStatus.FailedRetryable,
        -> logValue()
    }

private fun Enum<*>.logValue(): String =
    name.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase()

private const val OPERATION_TYPE_CONVERSATION_TURN = "conversation_turn"
private const val OPERATION_BRANCH_CHAT_ANSWER = "chat_answer"
private const val OPERATION_BRANCH_PLANNING = "planning"

data class ResponseRunWorkerConfig(
    val enabled: Boolean,
    val pollInterval: Duration,
    val leaseDuration: Duration,
    val heartbeatInterval: Duration,
    val retryBackoff: Duration,
    val maxAttempts: Int,
) {
    init {
        require(!pollInterval.isNegative && !pollInterval.isZero) { "pollInterval must be positive" }
        require(!leaseDuration.isNegative && !leaseDuration.isZero) { "leaseDuration must be positive" }
        require(!heartbeatInterval.isNegative && !heartbeatInterval.isZero) { "heartbeatInterval must be positive" }
        require(heartbeatInterval < leaseDuration) { "heartbeatInterval must be shorter than leaseDuration" }
        require(!retryBackoff.isNegative) { "retryBackoff must not be negative" }
        require(maxAttempts > 0) { "maxAttempts must be positive" }
    }
}
