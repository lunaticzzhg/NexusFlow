package com.nexusflow.ai.understanding

import com.nexusflow.ai.provider.StructuredModelProvider
import com.nexusflow.ai.provider.StructuredModelRequest
import com.nexusflow.ai.provider.StructuredModelResult
import com.nexusflow.ai.provider.StructuredModelResultMetadata
import com.nexusflow.observability.LogFields
import com.nexusflow.observability.LogLevel
import com.nexusflow.observability.StructuredLogger
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class StructuredUserMessageUnderstandingLoggingTest {
    @Test
    fun `logs retry from understanding repair decision`() =
        runBlocking {
            val logger = RecordingLogger()
            val provider = ScriptedProvider("""{"wrong":"shape"}""", validUnderstandingPayload())

            StructuredUserMessageUnderstanding(provider, logger = logger).understand(context())

            val retry = logger.entries.single()
            assertEquals(LogLevel.WARN, retry.level)
            assertEquals("ai_request_retry", retry.event)
            assertEquals("understanding", retry.fields["operation"])
            assertEquals("2", retry.fields["next_attempt"])
            assertEquals("invalid_structured_output", retry.fields["failure_category"])
        }

    private fun context(): UnderstandingContext =
        UnderstandingContext(
            aiRequestId = "understand-1",
            taskId = "task-1",
            taskRevision = 1,
            intent = "",
            requirements = emptyList(),
            currentMessage = "Find a movie",
            referenceTime = Instant.parse("2026-08-29T00:00:00Z"),
            timeZoneId = "Asia/Shanghai",
        )
}

private fun validUnderstandingPayload(): String =
    UnderstandingJson.encodeToString(
        StructuredUnderstandingPayload(
            userIntent = "plan_request",
            requirementChanges = emptyList(),
            clarification =
                StructuredClarificationPayload(
                    needed = false,
                    missingInformation = emptyList(),
                    reasonCategory = "none",
                ),
            contextSelection = StructuredContextSelectionPayload(selectedKeys = emptyList()),
        ),
    )

private class ScriptedProvider(
    private vararg val outputs: String,
) : StructuredModelProvider {
    private var calls = 0

    override suspend fun generate(request: StructuredModelRequest): StructuredModelResult {
        val output = outputs[calls.coerceAtMost(outputs.lastIndex)]
        calls += 1
        return StructuredModelResult(
            outputText = output,
            metadata =
                StructuredModelResultMetadata(
                    provider = "test",
                    model = "test",
                    providerRequestId = "provider-request",
                    attemptCount = request.metadata.attemptNumber,
                    requestDiagnostics = request.metadata.diagnostics,
                ),
        )
    }
}

private class RecordingLogger : StructuredLogger {
    val entries = mutableListOf<Entry>()

    override fun log(
        level: LogLevel,
        component: String,
        event: String,
        fields: LogFields,
        cause: Throwable?,
    ) {
        entries += Entry(level, event, fields.values)
    }
}

private data class Entry(
    val level: LogLevel,
    val event: String,
    val fields: Map<String, String>,
)

private val UnderstandingJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
    encodeDefaults = false
}
