package com.nexusflow.backend.feature.conversation.application

import com.nexusflow.backend.feature.research.application.ReadToolActivityKind
import com.nexusflow.backend.feature.research.application.ReadToolCall
import com.nexusflow.backend.feature.research.application.ReadToolDefinition
import com.nexusflow.backend.feature.research.application.ReadToolExecutionObserver
import com.nexusflow.backend.feature.research.application.ReadToolOutcome
import com.nexusflow.backend.feature.responserun.domain.ResponseRun
import java.util.concurrent.ConcurrentHashMap

class ResponseRunReadToolActivityObserver(
    private val realtimeHub: ResponseRunRealtimeHub,
    private val run: ResponseRun,
) : ReadToolExecutionObserver {
    private val activityIdsByCall = ConcurrentHashMap<ReadToolCall, String>()

    override suspend fun onStarted(
        call: ReadToolCall,
        definition: ReadToolDefinition,
    ) {
        val event = realtimeHub.toolStarted(run, definition.activityKind.toResponseRunActivityKind())
            ?: return
        val activityId = (event.payload as? ResponseRunEventPayload.ToolStarted)?.activityId ?: return
        activityIdsByCall[call] = activityId
    }

    override suspend fun onFinished(
        call: ReadToolCall,
        definition: ReadToolDefinition,
        outcome: ReadToolOutcome,
    ) {
        val activityId = activityIdsByCall[call] ?: return
        val kind = definition.activityKind.toResponseRunActivityKind()
        when (outcome) {
            is ReadToolOutcome.InvalidArguments,
            is ReadToolOutcome.Unavailable,
            -> realtimeHub.toolFailed(run, activityId, kind)
            ReadToolOutcome.Empty,
            is ReadToolOutcome.MissingInput,
            is ReadToolOutcome.Success,
            -> realtimeHub.toolCompleted(run, activityId, kind)
        }
    }
}

private fun ReadToolActivityKind.toResponseRunActivityKind(): ResponseRunActivityKind =
    when (this) {
        ReadToolActivityKind.Weather -> ResponseRunActivityKind.Weather
        ReadToolActivityKind.PlaceSearch -> ResponseRunActivityKind.PlaceSearch
        ReadToolActivityKind.Route -> ResponseRunActivityKind.Route
        ReadToolActivityKind.Movie -> ResponseRunActivityKind.Movie
        ReadToolActivityKind.Sports -> ResponseRunActivityKind.Sports
        ReadToolActivityKind.Music -> ResponseRunActivityKind.Music
        ReadToolActivityKind.Web -> ResponseRunActivityKind.Web
        ReadToolActivityKind.OtherResearch -> ResponseRunActivityKind.OtherResearch
    }
