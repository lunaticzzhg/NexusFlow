package com.nexusflow.app.core.network.realtime

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nexusflow.app.App
import com.nexusflow.app.core.network.FirstPartyApiSession
import com.nexusflow.app.core.network.FirstPartySessionRefresh
import com.nexusflow.app.core.network.configureAppHttpClient
import com.nexusflow.app.core.network.installFirstPartyHttpInterceptors
import com.nexusflow.app.core.observability.AppLogger
import com.nexusflow.app.core.observability.AppTraceManager
import com.nexusflow.app.feature.task.data.MockTaskRepository
import com.nexusflow.app.feature.task.domain.ConversationDetail
import com.nexusflow.app.feature.task.domain.ConversationId
import com.nexusflow.app.feature.task.domain.ResponseRun
import com.nexusflow.app.feature.task.domain.ResponseRunId
import com.nexusflow.app.feature.task.domain.ResponseRunSnapshot
import com.nexusflow.app.feature.task.domain.ResponseRunStage
import com.nexusflow.app.feature.task.domain.ResponseRunStatus
import com.nexusflow.app.feature.task.presentation.detail.ResponseRunStreamController
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Instant
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SseDeviceDiagnosticTest {
    @Test
    fun controllerReceivesDeltaOnDevice() =
        runBlocking {
            val arguments = InstrumentationRegistry.getArguments()
            val token = checkNotNull(arguments.getString("sse_token"))
            val conversationId = ConversationId(checkNotNull(arguments.getString("sse_conversation_id")))
            val runId = ResponseRunId(checkNotNull(arguments.getString("sse_run_id")))
            val run =
                ResponseRun(
                    id = runId,
                    userMessageId = "diagnostic-user",
                    turnIndex = 1,
                    status = ResponseRunStatus.Processing,
                    stage = ResponseRunStage.Turn,
                    attempt = 0,
                    retryable = false,
                    assistantMessageId = null,
                    failureCategory = null,
                    createdAt = Instant.parse("2026-09-24T00:00:00Z"),
                    updatedAt = Instant.parse("2026-09-24T00:00:00Z"),
                    completedAt = null,
                )
            val snapshot =
                ResponseRunSnapshot(
                    run = run,
                    conversation = ConversationDetail(conversationId, emptyList(), listOf(run), null),
                    streamAttempt = 0,
                    lastSeq = 0,
                    partialText = "",
                    activities = emptyList(),
                    realtimeSnapshotAvailable = true,
                )
            val repository =
                object : com.nexusflow.app.feature.task.domain.TaskRepository by MockTaskRepository() {
                    override suspend fun loadResponseRunSnapshot(
                        conversationId: ConversationId,
                        responseRunId: ResponseRunId,
                    ): Result<ResponseRunSnapshot> = Result.success(snapshot)
                }
            val baseUrl = "http://127.0.0.1:8080"
            val client = HttpClient(OkHttp) { configureAppHttpClient() }
            val koin = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as App).koinApplication.koin
            client.installFirstPartyHttpInterceptors(
                apiBaseUrl = baseUrl,
                logger = koin.get<AppLogger>(),
                traceManager = koin.get<AppTraceManager>(),
            ) { null }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            try {
                val controller =
                    ResponseRunStreamController(
                        repository = repository,
                        sseSessionFactory = RealtimeSseSessionFactory(client, baseUrl) { DeviceTestSession(token) },
                        scope = scope,
                        logger = koin.get<AppLogger>(),
                    )
                controller.start(conversationId, run)
                val received = withTimeoutOrNull(10_000) { controller.state.first { it.partialText.isNotEmpty() } } != null
                Log.i("SseDiagnostic", "controller_delta=$received")
                assertTrue(received)
            } finally {
                scope.cancel()
                client.close()
            }
        }

    @Test
    fun readSseOnDevice() =
        runBlocking {
            val token = checkNotNull(InstrumentationRegistry.getArguments().getString("sse_token"))
            val path = checkNotNull(InstrumentationRegistry.getArguments().getString("sse_path"))
            val lastEventId = InstrumentationRegistry.getArguments().getString("sse_last_id")
            val koin = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as App).koinApplication.koin
            val sessionProvider = { DeviceTestSession(token) }
            val baseUrl = "http://127.0.0.1:8080"
            val request = RealtimeSseRequest(path, lastEventId)
            val results =
                listOf(true, false).map { withInterceptor ->
                    val client = HttpClient(OkHttp) { configureAppHttpClient() }
                    if (withInterceptor) {
                        client.installFirstPartyHttpInterceptors(
                            apiBaseUrl = baseUrl,
                            logger = koin.get<AppLogger>(),
                            traceManager = koin.get<AppTraceManager>(),
                        ) { null }
                    }
                    val session = RealtimeSseSessionFactory(client, baseUrl, sessionProvider = sessionProvider).open(request)
                    try {
                        val received =
                            withTimeoutOrNull(10_000) {
                                session.events.first {
                                    it is RealtimeSseSessionEvent.RawEvent &&
                                        it.event.data?.contains("\"type\":\"delta\"") == true
                                }
                            } != null
                        Log.i("SseDiagnostic", "with_interceptor=$withInterceptor delta_event=$received")
                        received
                    } finally {
                        session.stop()
                        client.close()
                    }
                }
            assertTrue(results.all { it })
        }
}

private class DeviceTestSession(private val token: String) : FirstPartyApiSession {
    override suspend fun currentAccessToken(): String = token

    override suspend fun refreshAccessTokenIfCurrent(accessToken: String): FirstPartySessionRefresh = FirstPartySessionRefresh.Unavailable

    override suspend fun clearSessionIfCurrent(accessToken: String): Boolean = false
}
