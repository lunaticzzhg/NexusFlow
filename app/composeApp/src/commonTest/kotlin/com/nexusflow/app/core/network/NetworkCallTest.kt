package com.nexusflow.app.core.network

import com.nexusflow.app.core.error.AppException
import com.nexusflow.app.core.observability.AppLogger
import com.nexusflow.app.core.observability.LogFields
import com.nexusflow.app.core.observability.LogLevel
import com.nexusflow.app.core.observability.LogTag
import com.nexusflow.contracts.api.KResponse
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
}

private const val ENDPOINT = "v1/auth/google/exchange"

private fun executor(): ApiCallExecutor = ApiCallExecutor(RecordingApiLogger())

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
