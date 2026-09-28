package com.nexusflow.app.core.network.realtime

import com.nexusflow.app.core.network.FirstPartyApiSession
import com.nexusflow.app.core.network.FirstPartySessionRefresh
import com.nexusflow.app.core.network.configureAppHttpClient
import com.nexusflow.app.core.network.installFirstPartyHttpInterceptors
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals

class RealtimeSseOkHttpTest {
    @Test
    fun `local backend SSE reaches the shared OkHttp client`() =
        runBlocking {
            val token = System.getenv("NEXUSFLOW_SSE_DIAG_TOKEN") ?: return@runBlocking
            val baseUrl = "http://127.0.0.1:8080"
            val client = HttpClient(OkHttp) { configureAppHttpClient() }
            client.installFirstPartyHttpInterceptors(baseUrl, logger = null) { TestSession }
            try {
                val session =
                    RealtimeSseSessionFactory(client, baseUrl) { DiagnosticSession(token) }
                        .open(
                            RealtimeSseRequest(
                                "v1/conversations/c2c67930-f551-4de8-bb33-c1d2c8ce1dcf/" +
                                    "response-runs/db983788-86f4-49d7-b39e-368294037314/events",
                            ),
                        )
                withTimeout(5_000) {
                    session.events.first { it is RealtimeSseSessionEvent.RawEvent }
                }
            } finally {
                client.close()
            }
        }

    @Test
    fun `shared OkHttp client receives a complete SSE event`() =
        runBlocking {
            ServerSocket(0).use { server ->
                val serverThread =
                    thread {
                        server.accept().use { socket ->
                            val input = socket.getInputStream().bufferedReader()
                            while (input.readLine()?.isNotEmpty() == true) Unit
                            socket.getOutputStream().apply {
                                write(
                                    (
                                        "HTTP/1.1 200 OK\r\n" +
                                            "Content-Type: text/event-stream\r\n" +
                                            "Cache-Control: no-cache\r\n\r\n" +
                                            "event: response-run\n" +
                                            "data: {\"kind\":\"snapshot\"}\n" +
                                            "id: run:1:1\n\n"
                                    ).toByteArray(),
                                )
                                flush()
                            }
                        }
                    }
                val baseUrl = "http://127.0.0.1:${server.localPort}"
                val client = HttpClient(OkHttp) { configureAppHttpClient() }
                client.installFirstPartyHttpInterceptors(baseUrl, logger = null) { TestSession }
                try {
                    val session =
                        RealtimeSseSessionFactory(client, baseUrl) { TestSession }
                            .open(RealtimeSseRequest("v1/conversations/c/response-runs/r/events"))
                    val event =
                        withTimeout(5_000) {
                            session.events.first { it is RealtimeSseSessionEvent.RawEvent }
                        } as RealtimeSseSessionEvent.RawEvent
                    assertEquals("run:1:1", event.event.id)
                    assertEquals("response-run", event.event.type)
                } finally {
                    client.close()
                    serverThread.join(5_000)
                }
            }
        }
}

private class DiagnosticSession(private val token: String) : FirstPartyApiSession {
    override suspend fun currentAccessToken(): String = token

    override suspend fun refreshAccessTokenIfCurrent(accessToken: String): FirstPartySessionRefresh = FirstPartySessionRefresh.Unavailable

    override suspend fun clearSessionIfCurrent(accessToken: String): Boolean = false
}

private object TestSession : FirstPartyApiSession {
    override suspend fun currentAccessToken(): String = "t"

    override suspend fun refreshAccessTokenIfCurrent(accessToken: String): FirstPartySessionRefresh = FirstPartySessionRefresh.Unavailable

    override suspend fun clearSessionIfCurrent(accessToken: String): Boolean = false
}
