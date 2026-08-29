package com.nexusflow.app.core.network

import com.nexusflow.app.core.error.AppException
import com.nexusflow.app.core.observability.AppLogger
import com.nexusflow.app.core.observability.LogFields
import com.nexusflow.app.core.observability.LogLevel
import com.nexusflow.app.core.observability.LogTag
import com.nexusflow.contracts.api.KResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class NetworkCallTest {
    @Test
    fun `unwraps the sole supported success code`() =
        runBlocking {
            assertEquals("ready", executor().execute(ENDPOINT) { KResponse(code = 200, data = "ready") }.getOrThrow())
        }

    @Test
    fun `rejects non-200 envelopes and records safe diagnostics`() =
        runBlocking {
            val logger = RecordingApiLogger()

            val result =
                ApiCallExecutor(logger).execute<String>(ENDPOINT) {
                    KResponse(code = 422, message = "Invalid credential")
                }

            assertIs<AppException.Rejected>(result.exceptionOrNull())
            assertEquals(
                mapOf(
                    "api_path" to "/v1/auth/google/exchange",
                    "code" to "422",
                    "message" to "Invalid credential",
                ),
                logger.entries.single().fields,
            )
        }

    @Test
    fun `maps normalized HTTP failures`() =
        runBlocking {
            val result = executor().execute<String>(ENDPOINT) { throw HttpFailureException(HttpFailure.RateLimited) }

            assertIs<AppException.RateLimited>(result.exceptionOrNull())
            Unit
        }

    @Test
    fun `maps IO failures and preserves cancellation`() =
        runBlocking {
            assertIs<AppException.Unavailable>(executor().execute<String>(ENDPOINT) { throw IOException("offline") }.exceptionOrNull())
            assertFailsWith<CancellationException> {
                executor().execute<String>(ENDPOINT) { throw CancellationException("cancelled") }
            }
            Unit
        }

    @Test
    fun `accepts an empty successful envelope only through the unit helper`() =
        runBlocking {
            assertEquals(Unit, executor().executeUnit(ENDPOINT) { KResponse<Unit>(code = 200) }.getOrThrow())
            assertIs<AppException.InvalidResponse>(executor().execute<Unit>(ENDPOINT) { KResponse(code = 200) }.exceptionOrNull())
            Unit
        }

    @Test
    fun `protected first party requests inject bearer token`() =
        runBlocking {
            val session = RecordingFirstPartyApiSession(currentToken = "access-token")
            val seenAuthorization = mutableListOf<String?>()
            val client =
                testClient(
                    session = session,
                    engine =
                        MockEngine { request ->
                            seenAuthorization += request.headers[HttpHeaders.Authorization]
                            jsonResponse("""{"code":200}""")
                        },
                )

            assertEquals("""{"code":200}""", client.get("$API_BASE_URL/v1/tasks").bodyAsText())
            assertEquals(listOf<String?>("Bearer access-token"), seenAuthorization)
            client.close()
        }

    @Test
    fun `first protected 401 refreshes once and replays once`() =
        runBlocking {
            val session =
                RecordingFirstPartyApiSession(
                    currentToken = "old-token",
                    refreshResult = FirstPartySessionRefresh.TokenAvailable("new-token"),
                )
            val seenAuthorization = mutableListOf<String?>()
            val client =
                testClient(
                    session = session,
                    engine =
                        MockEngine { request ->
                            seenAuthorization += request.headers[HttpHeaders.Authorization]
                            if (seenAuthorization.size == 1) {
                                jsonResponse("""{"code":401,"message":"Unauthorized"}""", HttpStatusCode.Unauthorized)
                            } else {
                                jsonResponse("""{"code":200}""")
                            }
                        },
                )

            assertEquals("""{"code":200}""", client.get("$API_BASE_URL/v1/tasks").bodyAsText())
            assertEquals(listOf<String?>("Bearer old-token", "Bearer new-token"), seenAuthorization)
            assertEquals(listOf("old-token"), session.refreshRequests)
            assertEquals(emptyList(), session.clearRequests)
            client.close()
        }

    @Test
    fun `auth endpoints bypass bearer injection and refresh recursion`() =
        runBlocking {
            val session =
                RecordingFirstPartyApiSession(
                    currentToken = "access-token",
                    refreshResult = FirstPartySessionRefresh.TokenAvailable("new-token"),
                )
            val seenAuthorization = mutableListOf<String?>()
            val client =
                testClient(
                    session = session,
                    engine =
                        MockEngine { request ->
                            seenAuthorization += request.headers[HttpHeaders.Authorization]
                            jsonResponse("""{"code":401,"message":"Unauthorized"}""", HttpStatusCode.Unauthorized)
                        },
                )

            assertFailsWith<HttpFailureException> {
                client.get("$API_BASE_URL/v1/auth/refresh").bodyAsText()
            }
            assertEquals(listOf<String?>(null), seenAuthorization)
            assertEquals(emptyList<String>(), session.refreshRequests)
            client.close()
        }

    @Test
    fun `refresh unavailable returns original 401 without clearing session`() =
        runBlocking {
            val session =
                RecordingFirstPartyApiSession(
                    currentToken = "old-token",
                    refreshResult = FirstPartySessionRefresh.Unavailable,
                )
            val client =
                testClient(
                    session = session,
                    engine = MockEngine { jsonResponse("""{"code":503}""", HttpStatusCode.Unauthorized) },
                )

            assertFailsWith<HttpFailureException> {
                client.get("$API_BASE_URL/v1/tasks").bodyAsText()
            }
            assertEquals(listOf("old-token"), session.refreshRequests)
            assertEquals(emptyList<String>(), session.clearRequests)
            client.close()
        }

    @Test
    fun `replayed 401 clears only the replayed matching token`() =
        runBlocking {
            val session =
                RecordingFirstPartyApiSession(
                    currentToken = "old-token",
                    refreshResult = FirstPartySessionRefresh.TokenAvailable("new-token"),
                )
            val client =
                testClient(
                    session = session,
                    engine = MockEngine { jsonResponse("""{"code":401}""", HttpStatusCode.Unauthorized) },
                )

            assertFailsWith<HttpFailureException> {
                client.get("$API_BASE_URL/v1/tasks").bodyAsText()
            }
            assertEquals(listOf("old-token"), session.refreshRequests)
            assertEquals(listOf("new-token"), session.clearRequests)
            client.close()
        }

    @Test
    fun `stale token 401 reuses newer active token without destructive clear`() =
        runBlocking {
            val session =
                RecordingFirstPartyApiSession(
                    currentToken = "old-token",
                    refreshResult = FirstPartySessionRefresh.TokenAvailable("newer-token"),
                )
            val seenAuthorization = mutableListOf<String?>()
            val client =
                testClient(
                    session = session,
                    engine =
                        MockEngine { request ->
                            seenAuthorization += request.headers[HttpHeaders.Authorization]
                            if (seenAuthorization.size == 1) {
                                jsonResponse("""{"code":401}""", HttpStatusCode.Unauthorized)
                            } else {
                                jsonResponse("""{"code":200}""")
                            }
                        },
                )

            assertEquals("""{"code":200}""", client.get("$API_BASE_URL/v1/tasks").bodyAsText())
            assertEquals(listOf<String?>("Bearer old-token", "Bearer newer-token"), seenAuthorization)
            assertEquals(listOf("old-token"), session.refreshRequests)
            assertEquals(emptyList<String>(), session.clearRequests)
            client.close()
        }

    @Test
    fun `transport cancellation propagates through first party interceptors`() =
        runBlocking {
            val client =
                testClient(
                    session = RecordingFirstPartyApiSession(currentToken = "access-token"),
                    engine = MockEngine { throw CancellationException("cancelled") },
                )

            assertFailsWith<CancellationException> {
                client.get("$API_BASE_URL/v1/tasks").bodyAsText()
            }
            client.close()
        }
}

private const val ENDPOINT = "v1/auth/google/exchange"
private const val API_BASE_URL = "https://api.example"

private fun executor(): ApiCallExecutor = ApiCallExecutor(RecordingApiLogger())

private fun testClient(
    session: FirstPartyApiSession?,
    engine: MockEngine,
): HttpClient =
    HttpClient(engine) {
        configureAppHttpClient()
    }.also { client ->
        client.installFirstPartyHttpInterceptors(API_BASE_URL) { session }
    }

private fun MockRequestHandleScope.jsonResponse(
    content: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(content, status, headersOf(HttpHeaders.ContentType, "application/json"))

private class RecordingFirstPartyApiSession(
    private val currentToken: String?,
    private val refreshResult: FirstPartySessionRefresh = FirstPartySessionRefresh.Unavailable,
) : FirstPartyApiSession {
    val refreshRequests = mutableListOf<String>()
    val clearRequests = mutableListOf<String>()

    override suspend fun currentAccessToken(): String? = currentToken

    override suspend fun refreshAccessTokenIfCurrent(accessToken: String): FirstPartySessionRefresh {
        refreshRequests += accessToken
        return refreshResult
    }

    override suspend fun clearSessionIfCurrent(accessToken: String): Boolean {
        clearRequests += accessToken
        return true
    }
}

private class RecordingApiLogger : AppLogger {
    val entries = mutableListOf<Entry>()

    override fun log(
        level: LogLevel,
        tag: LogTag,
        event: String,
        fields: LogFields,
        cause: Throwable?,
    ) {
        entries += Entry(level, tag.value, event, fields.values)
    }

    data class Entry(
        val level: LogLevel,
        val tag: String,
        val event: String,
        val fields: Map<String, String>,
    )
}
