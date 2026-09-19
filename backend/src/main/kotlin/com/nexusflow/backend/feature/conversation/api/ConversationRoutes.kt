package com.nexusflow.backend.feature.conversation.api

import com.nexusflow.backend.core.http.respondError
import com.nexusflow.backend.core.http.respondSuccess
import com.nexusflow.backend.core.identity.ActorResolver
import com.nexusflow.backend.core.identity.UnauthenticatedException
import com.nexusflow.backend.feature.conversation.application.ConversationMutationResult
import com.nexusflow.backend.feature.conversation.application.ConversationService
import com.nexusflow.backend.feature.conversation.application.ResponseRunActivity
import com.nexusflow.backend.feature.conversation.application.ResponseRunActivityKind
import com.nexusflow.backend.feature.conversation.application.ResponseRunEvent
import com.nexusflow.backend.feature.conversation.application.ResponseRunEventPayload
import com.nexusflow.backend.feature.conversation.application.ResponseRunService
import com.nexusflow.backend.feature.conversation.application.ResponseRunSnapshot
import com.nexusflow.backend.feature.conversation.domain.ConversationDetail
import com.nexusflow.backend.feature.conversation.domain.ConversationMessage
import com.nexusflow.backend.feature.responserun.domain.ResponseRun
import com.nexusflow.backend.feature.responserun.domain.ResponseRunFailureCategory
import com.nexusflow.backend.feature.responserun.domain.ResponseRunStage
import com.nexusflow.backend.feature.responserun.domain.ResponseRunStatus
import com.nexusflow.backend.feature.task.api.toConversationCurrentTaskResponse
import com.nexusflow.backend.feature.task.application.InvalidTaskOperationException
import com.nexusflow.backend.feature.task.application.InvalidTaskRequestException
import com.nexusflow.backend.feature.task.application.MissingTaskScopeException
import com.nexusflow.backend.feature.task.application.TaskConflictException
import com.nexusflow.backend.feature.task.application.TaskDependencyUnavailableException
import com.nexusflow.backend.feature.task.application.TaskNotFoundException
import com.nexusflow.backend.feature.conversation.domain.MessageRole
import com.nexusflow.contracts.appbackend.conversation.ConversationDetailResponse
import com.nexusflow.contracts.appbackend.conversation.ConversationMessageResponse
import com.nexusflow.contracts.appbackend.conversation.ConversationResponse
import com.nexusflow.contracts.appbackend.conversation.CreateConversationRequest
import com.nexusflow.contracts.appbackend.conversation.CreateConversationResponse
import com.nexusflow.contracts.appbackend.conversation.ResponseRunActivityKindResponse
import com.nexusflow.contracts.appbackend.conversation.ResponseRunActivityResponse
import com.nexusflow.contracts.appbackend.conversation.ResponseRunEventEnvelope
import com.nexusflow.contracts.appbackend.conversation.ResponseRunEventPayload as ResponseRunEventPayloadResponse
import com.nexusflow.contracts.appbackend.conversation.ResponseRunFailureCategoryResponse
import com.nexusflow.contracts.appbackend.conversation.ResponseRunResponse
import com.nexusflow.contracts.appbackend.conversation.ResponseRunSnapshotResponse
import com.nexusflow.contracts.appbackend.conversation.ResponseRunStageResponse
import com.nexusflow.contracts.appbackend.conversation.ResponseRunStatusResponse
import com.nexusflow.contracts.appbackend.conversation.SendConversationMessageRequest
import com.nexusflow.contracts.appbackend.conversation.SendConversationMessageResponse
import com.nexusflow.contracts.appbackend.task.MessageRole as MessageRoleResponse
import com.nexusflow.observability.LogLevel
import com.nexusflow.observability.StructuredLogger
import com.nexusflow.observability.logFields
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import java.time.Instant
import kotlinx.coroutines.flow.collect
import kotlinx.datetime.Instant as ContractInstant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun Route.conversationRoutes(
    conversationService: ConversationService,
    actorResolver: ActorResolver,
    responseRunService: ResponseRunService? = null,
    logger: StructuredLogger? = null,
) {
    route("/v1/conversations") {
        post {
            call.respondConversation {
                val request = call.receive<CreateConversationRequest>()
                call.respondSuccess(
                    conversationService.createConversation(
                        actor = actorResolver.resolve(call),
                        clientRequestId = request.clientRequestId,
                        message = request.message,
                        timeZoneId = request.timeZoneId,
                    ).toCreateResponse(),
                )
            }
        }

        get("/{conversationId}") {
            call.respondConversation {
                call.respondSuccess(
                    conversationService.getConversation(
                        actor = actorResolver.resolve(call),
                        conversationId = call.conversationIdParameter(),
                    ).toDetailResponse(),
                )
            }
        }

        post("/{conversationId}/messages") {
            call.respondConversation {
                val request = call.receive<SendConversationMessageRequest>()
                call.respondSuccess(
                    conversationService.sendMessage(
                        actor = actorResolver.resolve(call),
                        conversationId = call.conversationIdParameter(),
                        clientMessageId = request.clientMessageId,
                        text = request.text,
                        timeZoneId = request.timeZoneId,
                    ).toSendResponse(),
                )
            }
        }

        if (responseRunService != null) {
            get("/{conversationId}/response-runs/{responseRunId}") {
                call.respondConversation {
                    call.respondSuccess(
                        responseRunService.snapshot(
                            actor = actorResolver.resolve(call),
                            conversationId = call.conversationIdParameter(),
                            responseRunId = call.responseRunIdParameter(),
                        ).toResponse(),
                    )
                }
            }

            post("/{conversationId}/response-runs/{responseRunId}/cancel") {
                call.respondConversation {
                    call.respondSuccess(
                        responseRunService.cancel(
                            actor = actorResolver.resolve(call),
                            conversationId = call.conversationIdParameter(),
                            responseRunId = call.responseRunIdParameter(),
                        ).run.toResponse(),
                    )
                }
            }

            post("/{conversationId}/response-runs/{responseRunId}/retry") {
                call.respondConversation {
                    call.respondSuccess(
                        responseRunService.retry(
                            actor = actorResolver.resolve(call),
                            conversationId = call.conversationIdParameter(),
                            responseRunId = call.responseRunIdParameter(),
                        ).run.toResponse(),
                    )
                }
            }

            sse("/{conversationId}/response-runs/{responseRunId}/events") {
                var connected = false
                var lastSentSeq: Long? = null
                try {
                    val conversationId = call.conversationIdParameter()
                    val responseRunId = call.responseRunIdParameter()
                    val lastEventId = call.request.headers[LAST_EVENT_ID_HEADER]
                    val snapshot = responseRunService.snapshot(
                        actor = actorResolver.resolve(call),
                        conversationId = conversationId,
                        responseRunId = responseRunId,
                    )
                    val afterSeq = call.lastResponseRunEventId(snapshot)
                    connected = true
                    logger.logSse(
                        event = "response_run_sse_connected",
                        conversationId = conversationId,
                        responseRunId = responseRunId,
                        attempt = snapshot.streamAttempt,
                        lastSeq = snapshot.lastSeq,
                        afterSeq = afterSeq,
                        lastEventId = lastEventId,
                    )
                    val snapshotEvent = snapshot.toSnapshotEvent()
                    sendResponseRunEvent(
                        envelope = snapshotEvent,
                        outboundKind = "snapshot",
                        conversationId = conversationId,
                        responseRunId = responseRunId,
                        logger = logger,
                    )
                    lastSentSeq = snapshotEvent.seq
                    responseRunService.events(responseRunId, afterSeq).collect { event ->
                        val envelope = event.toResponse()
                        sendResponseRunEvent(
                            envelope = envelope,
                            outboundKind = "live",
                            conversationId = conversationId,
                            responseRunId = responseRunId,
                            logger = logger,
                        )
                        lastSentSeq = envelope.seq
                    }
                } catch (_: UnauthenticatedException) {
                    call.respondError(HttpStatusCode.Unauthorized, "Authentication is required")
                } catch (_: MissingTaskScopeException) {
                    call.respondError(HttpStatusCode.Forbidden, "Required task scope is missing")
                } catch (_: TaskNotFoundException) {
                    call.respondError(HttpStatusCode.NotFound, "Conversation was not found")
                } catch (_: InvalidTaskRequestException) {
                    call.respondError(HttpStatusCode.UnprocessableEntity, "Invalid request")
                } catch (_: InvalidTaskOperationException) {
                    call.respondError(HttpStatusCode.UnprocessableEntity, "Conversation operation is not allowed")
                } finally {
                    if (connected) {
                        logger.logSse(
                            event = "response_run_sse_disconnected",
                            conversationId = call.parameters["conversationId"],
                            responseRunId = call.parameters["responseRunId"],
                            lastSentSeq = lastSentSeq,
                        )
                    }
                }
            }
        }
    }
}

private suspend fun io.ktor.server.sse.ServerSSESession.sendResponseRunEvent(
    envelope: ResponseRunEventEnvelope,
    outboundKind: String,
    conversationId: String,
    responseRunId: String,
    logger: StructuredLogger?,
) {
    try {
        send(envelope.toServerSentEvent())
        logger.logSse(
            event = if (outboundKind == "snapshot") "response_run_sse_snapshot_sent" else "response_run_sse_event_sent",
            conversationId = conversationId,
            responseRunId = responseRunId,
            attempt = envelope.attempt,
            seq = envelope.seq,
            payloadType = envelope.payload.logValue(),
        )
    } catch (cause: Throwable) {
        logger.logSse(
            event = "response_run_sse_send_failed",
            conversationId = conversationId,
            responseRunId = responseRunId,
            attempt = envelope.attempt,
            seq = envelope.seq,
            payloadType = envelope.payload.logValue(),
            outboundKind = outboundKind,
            cause = cause,
        )
        throw cause
    }
}

private fun StructuredLogger?.logSse(
    event: String,
    conversationId: String?,
    responseRunId: String?,
    attempt: Int? = null,
    seq: Long? = null,
    lastSeq: Long? = null,
    afterSeq: Long? = null,
    lastSentSeq: Long? = null,
    lastEventId: String? = null,
    payloadType: String? = null,
    outboundKind: String? = null,
    cause: Throwable? = null,
) {
    this?.log(
        level = if (event == "response_run_sse_send_failed") LogLevel.WARN else LogLevel.INFO,
        component = RESPONSE_RUN_SSE_LOG_COMPONENT,
        event = event,
        fields =
            logFields {
                "conversation_id" value conversationId
                "response_run_id" value responseRunId
                "attempt" value attempt
                "seq" value seq
                "last_seq" value lastSeq
                "after_seq" value afterSeq
                "last_sent_seq" value lastSentSeq
                "last_event_id" value lastEventId
                "payload_type" value payloadType
                "outbound_kind" value outboundKind
            },
        cause = cause,
    )
}

private suspend fun ApplicationCall.respondConversation(block: suspend () -> Unit) {
    try {
        block()
    } catch (_: UnauthenticatedException) {
        respondError(HttpStatusCode.Unauthorized, "Authentication is required")
    } catch (_: MissingTaskScopeException) {
        respondError(HttpStatusCode.Forbidden, "Required task scope is missing")
    } catch (_: TaskNotFoundException) {
        respondError(HttpStatusCode.NotFound, "Conversation was not found")
    } catch (_: TaskConflictException) {
        respondError(HttpStatusCode.Conflict, "Conversation request conflicts with existing state")
    } catch (_: InvalidTaskRequestException) {
        respondError(HttpStatusCode.UnprocessableEntity, "Invalid request")
    } catch (_: InvalidTaskOperationException) {
        respondError(HttpStatusCode.UnprocessableEntity, "Conversation operation is not allowed")
    } catch (error: TaskDependencyUnavailableException) {
        respondError(HttpStatusCode.ServiceUnavailable, error.message ?: "Conversation dependency is temporarily unavailable")
    }
}

private fun ApplicationCall.conversationIdParameter(): String =
    parameters["conversationId"] ?: throw InvalidTaskRequestException("conversationId is required")

private fun ApplicationCall.responseRunIdParameter(): String =
    parameters["responseRunId"] ?: throw InvalidTaskRequestException("responseRunId is required")

private fun ApplicationCall.lastResponseRunEventId(snapshot: ResponseRunSnapshot): Long? {
    val raw = request.headers[LAST_EVENT_ID_HEADER] ?: return null
    val parsed = raw.parseResponseRunEventId() ?: return null
    return parsed
        .takeIf { it.runId == snapshot.run.id.value.toString() && it.attempt == snapshot.streamAttempt }
        ?.seq
}

private const val LAST_EVENT_ID_HEADER = "Last-Event-ID"
private const val RESPONSE_RUN_SSE_LOG_COMPONENT = "response_run_sse"

private data class ParsedResponseRunEventId(
    val runId: String,
    val attempt: Int,
    val seq: Long,
)

private fun String.parseResponseRunEventId(): ParsedResponseRunEventId? {
    val parts = split(':')
    if (parts.size != 3) return null
    val attempt = parts[1].toIntOrNull() ?: return null
    val seq = parts[2].toLongOrNull() ?: return null
    if (attempt < 0 || seq < 0) return null
    return ParsedResponseRunEventId(parts[0], attempt, seq)
}

private fun ConversationMutationResult.toCreateResponse(): CreateConversationResponse =
    CreateConversationResponse(
        conversation = detail.toResponse(),
        currentTask = currentTask?.toConversationCurrentTaskResponse(planningOutcome),
    )

private fun ConversationMutationResult.toDetailResponse(): ConversationDetailResponse =
    ConversationDetailResponse(
        conversation = detail.toResponse(),
        currentTask = currentTask?.toConversationCurrentTaskResponse(planningOutcome),
    )

private fun ConversationMutationResult.toSendResponse(): SendConversationMessageResponse =
    SendConversationMessageResponse(
        conversation = detail.toResponse(),
        currentTask = currentTask?.toConversationCurrentTaskResponse(planningOutcome),
    )

private fun ConversationDetail.toResponse(): ConversationResponse =
    ConversationResponse(
        id = conversation.id.value.toString(),
        messages = messages.map { it.toResponse() },
        responseRuns = responseRuns.map { it.toResponse() },
        createdAt = conversation.createdAt.toContractInstant(),
        updatedAt = conversation.updatedAt.toContractInstant(),
    )

private fun ConversationMessage.toResponse(): ConversationMessageResponse =
    ConversationMessageResponse(
        id = id.value.toString(),
        role = role.toResponse(),
        content = content,
        clientMessageId = clientMessageId,
        aiRequestId = aiRequestId,
        turnIndex = turnIndex,
        understoodAt = understoodAt?.toContractInstant(),
        createdAt = createdAt.toContractInstant(),
    )

private fun ResponseRun.toResponse(): ResponseRunResponse =
    ResponseRunResponse(
        id = id.value.toString(),
        userMessageId = userMessageId.value.toString(),
        turnIndex = turnIndex,
        status = status.toResponse(),
        stage = stage.toResponse(),
        attempt = attempt,
        retryable = status.isRetryable(),
        assistantMessageId = assistantMessageId?.value?.toString(),
        failureCategory = failureCategory?.toResponse(),
        createdAt = createdAt.toContractInstant(),
        updatedAt = updatedAt.toContractInstant(),
        completedAt = completedAt?.toContractInstant(),
    )

private fun ResponseRunSnapshot.toResponse(): ResponseRunSnapshotResponse =
    ResponseRunSnapshotResponse(
        run = run.toResponse(),
        conversation = detail.toResponse(),
        currentTask = currentTask?.toConversationCurrentTaskResponse(),
        streamAttempt = streamAttempt,
        lastSeq = lastSeq,
        partialText = partialText,
        activities = activities.map { it.toResponse() },
        realtimeSnapshotAvailable = realtimeSnapshotAvailable,
    )

private fun ResponseRunActivity.toResponse(): ResponseRunActivityResponse =
    ResponseRunActivityResponse(
        id = id,
        kind = kind.toResponse(),
        message = message,
        startedAt = startedAt.toContractInstant(),
        completedAt = completedAt?.toContractInstant(),
        failed = failed,
    )

private fun ResponseRunEvent.toResponse(): ResponseRunEventEnvelope =
    ResponseRunEventEnvelope(
        runId = runId.value.toString(),
        attempt = attempt,
        seq = seq,
        occurredAt = occurredAt.toContractInstant(),
        payload = payload.toResponse(),
    )

private fun ResponseRunSnapshot.toSnapshotEvent(): ResponseRunEventEnvelope =
    ResponseRunEventEnvelope(
        runId = run.id.value.toString(),
        attempt = streamAttempt,
        seq = lastSeq,
        occurredAt = run.updatedAt.toContractInstant(),
        payload = ResponseRunEventPayloadResponse.Snapshot(toResponse()),
    )

private fun ResponseRunEventEnvelope.toServerSentEvent(): ServerSentEvent =
    ServerSentEvent(
        data = SseJson.encodeToString(this),
        event = "response-run",
        id = "$runId:$attempt:$seq",
    )

private fun ResponseRunStatus.toResponse(): ResponseRunStatusResponse =
    when (this) {
        ResponseRunStatus.Queued -> ResponseRunStatusResponse.Queued
        ResponseRunStatus.Processing -> ResponseRunStatusResponse.Processing
        ResponseRunStatus.Streaming -> ResponseRunStatusResponse.Streaming
        ResponseRunStatus.Completed -> ResponseRunStatusResponse.Completed
        ResponseRunStatus.FailedRetryable -> ResponseRunStatusResponse.FailedRetryable
        ResponseRunStatus.Failed -> ResponseRunStatusResponse.Failed
        ResponseRunStatus.TimedOut -> ResponseRunStatusResponse.TimedOut
        ResponseRunStatus.Cancelled -> ResponseRunStatusResponse.Cancelled
    }

private fun ResponseRunStatus.isRetryable(): Boolean =
    when (this) {
        ResponseRunStatus.FailedRetryable,
        ResponseRunStatus.Failed,
        ResponseRunStatus.TimedOut,
        -> true
        ResponseRunStatus.Queued,
        ResponseRunStatus.Processing,
        ResponseRunStatus.Streaming,
        ResponseRunStatus.Completed,
        ResponseRunStatus.Cancelled,
        -> false
    }

private fun ResponseRunStage.toResponse(): ResponseRunStageResponse =
    when (this) {
        ResponseRunStage.Turn -> ResponseRunStageResponse.Turn
        ResponseRunStage.Planning -> ResponseRunStageResponse.Planning
    }

private fun ResponseRunFailureCategory.toResponse(): ResponseRunFailureCategoryResponse =
    when (this) {
        ResponseRunFailureCategory.ProviderTemporary -> ResponseRunFailureCategoryResponse.ProviderTemporary
        ResponseRunFailureCategory.AiInvalidResult -> ResponseRunFailureCategoryResponse.AiInvalidResult
        ResponseRunFailureCategory.WorkerLost -> ResponseRunFailureCategoryResponse.WorkerLost
        ResponseRunFailureCategory.RunTimeout -> ResponseRunFailureCategoryResponse.RunTimeout
        ResponseRunFailureCategory.InternalInvariant -> ResponseRunFailureCategoryResponse.InternalInvariant
    }

private fun ResponseRunActivityKind.toResponse(): ResponseRunActivityKindResponse =
    when (this) {
        ResponseRunActivityKind.Thinking -> ResponseRunActivityKindResponse.Thinking
        ResponseRunActivityKind.Weather -> ResponseRunActivityKindResponse.Weather
        ResponseRunActivityKind.PlaceSearch -> ResponseRunActivityKindResponse.PlaceSearch
        ResponseRunActivityKind.Route -> ResponseRunActivityKindResponse.Route
        ResponseRunActivityKind.Movie -> ResponseRunActivityKindResponse.Movie
        ResponseRunActivityKind.Sports -> ResponseRunActivityKindResponse.Sports
        ResponseRunActivityKind.Music -> ResponseRunActivityKindResponse.Music
        ResponseRunActivityKind.Web -> ResponseRunActivityKindResponse.Web
        ResponseRunActivityKind.OtherResearch -> ResponseRunActivityKindResponse.OtherResearch
    }

private fun ResponseRunEventPayload.toResponse(): ResponseRunEventPayloadResponse =
    when (this) {
        ResponseRunEventPayload.Thinking -> ResponseRunEventPayloadResponse.Thinking
        is ResponseRunEventPayload.ToolStarted -> ResponseRunEventPayloadResponse.ToolStarted(activityId, kind.toResponse())
        is ResponseRunEventPayload.ToolCompleted -> ResponseRunEventPayloadResponse.ToolCompleted(activityId, kind.toResponse())
        is ResponseRunEventPayload.ToolFailed -> ResponseRunEventPayloadResponse.ToolFailed(activityId, kind.toResponse())
        ResponseRunEventPayload.StreamingStarted -> ResponseRunEventPayloadResponse.StreamingStarted
        is ResponseRunEventPayload.Delta -> ResponseRunEventPayloadResponse.Delta(text)
        is ResponseRunEventPayload.Completed -> ResponseRunEventPayloadResponse.Completed(assistantMessageId?.value?.toString())
        is ResponseRunEventPayload.Failed -> ResponseRunEventPayloadResponse.Failed(retryable)
        ResponseRunEventPayload.Cancelled -> ResponseRunEventPayloadResponse.Cancelled
        ResponseRunEventPayload.TimedOut -> ResponseRunEventPayloadResponse.TimedOut
    }

private fun ResponseRunEventPayloadResponse.logValue(): String =
    when (this) {
        ResponseRunEventPayloadResponse.Thinking -> "thinking"
        is ResponseRunEventPayloadResponse.ToolStarted -> "tool_started"
        is ResponseRunEventPayloadResponse.ToolCompleted -> "tool_completed"
        is ResponseRunEventPayloadResponse.ToolFailed -> "tool_failed"
        ResponseRunEventPayloadResponse.StreamingStarted -> "streaming_started"
        is ResponseRunEventPayloadResponse.Delta -> "delta"
        is ResponseRunEventPayloadResponse.Completed -> "completed"
        is ResponseRunEventPayloadResponse.Failed -> "failed"
        ResponseRunEventPayloadResponse.Cancelled -> "cancelled"
        ResponseRunEventPayloadResponse.TimedOut -> "timed_out"
        is ResponseRunEventPayloadResponse.Snapshot -> "snapshot"
    }

private fun MessageRole.toResponse(): MessageRoleResponse =
    when (this) {
        MessageRole.User -> MessageRoleResponse.User
        MessageRole.Assistant -> MessageRoleResponse.Assistant
    }

private fun Instant.toContractInstant(): ContractInstant =
    ContractInstant.fromEpochSeconds(epochSecond, nano.toLong())

private val SseJson = Json {
    explicitNulls = false
    encodeDefaults = false
}
