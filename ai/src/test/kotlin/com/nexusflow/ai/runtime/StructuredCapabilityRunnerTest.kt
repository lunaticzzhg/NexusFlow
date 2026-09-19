package com.nexusflow.ai.runtime

import com.nexusflow.ai.provider.InvalidStructuredOutputException
import com.nexusflow.ai.provider.ProviderRateLimitedException
import com.nexusflow.ai.provider.StructuredModelProvider
import com.nexusflow.ai.provider.StructuredModelRequest
import com.nexusflow.ai.provider.StructuredModelRequestMetadata
import com.nexusflow.ai.provider.StructuredModelResult
import com.nexusflow.ai.provider.StructuredModelResultMetadata
import com.nexusflow.ai.provider.StructuredOutputSchema
import com.nexusflow.contracts.backendai.common.CapabilityRateLimitedException
import com.nexusflow.contracts.backendai.common.InvalidCapabilityResultException
import com.nexusflow.contracts.backendai.common.StructuredModelCapability
import com.nexusflow.observability.LogFields
import com.nexusflow.observability.LogLevel
import com.nexusflow.observability.StructuredLogger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StructuredCapabilityRunnerTest {
    @Test
    fun `retries repairable output and logs safe retry fields`() =
        runBlocking {
            val logger = RecordingLogger()
            val provider = RecordingProvider()
            val runner = StructuredCapabilityRunner(provider, logger)
            var decodeCalls = 0

            val result = runner.execute(
                operation = operation(providerInvalidOutputFailureStage = "provider_invalid"),
                request = ::request,
                decode = { modelResult ->
                    decodeCalls += 1
                    if (decodeCalls == 1) {
                        throw StructuredCapabilityInvalidOutputException(
                            message = "invalid shape",
                            failureStage = "json_decode",
                        )
                    }
                    "ok-${modelResult.metadata.attemptCount}"
                },
            )

            assertEquals("ok-2", result)
            assertEquals(listOf(1, 2), provider.requests.map { it.metadata.attemptNumber })
            val retry = logger.entries.single()
            assertEquals(LogLevel.WARN, retry.level)
            assertEquals("ai_request_retry", retry.event)
            assertEquals("test_operation", retry.fields["operation"])
            assertEquals("2", retry.fields["next_attempt"])
            assertEquals("invalid_test_output", retry.fields["failure_category"])
            assertEquals("json_decode", retry.fields["failure_stage"])
        }

    @Test
    fun `maps provider invalid structured output with configured failure stage`() =
        runBlocking {
            val runner = StructuredCapabilityRunner(
                provider = FailingProvider(InvalidStructuredOutputException("provider response was invalid")),
            )

            val error = assertFailsWith<InvalidCapabilityResultException> {
                runner.execute(
                    operation = operation(providerInvalidOutputFailureStage = "provider_invalid"),
                    request = ::request,
                    decode = { "unused" },
                )
            }

            assertEquals("provider_invalid", error.failureStage)
        }

    @Test
    fun `maps provider dependency failures without repair retry`() =
        runBlocking {
            val provider = FailingProvider(ProviderRateLimitedException())
            val runner = StructuredCapabilityRunner(provider)

            assertFailsWith<CapabilityRateLimitedException> {
                runner.execute(
                    operation = operation(providerInvalidOutputFailureStage = "provider_invalid"),
                    request = ::request,
                    decode = { "unused" },
                )
            }
            assertEquals(1, provider.requests.size)
        }
}

private fun operation(providerInvalidOutputFailureStage: String?): StructuredCapabilityOperation =
    StructuredCapabilityOperation(
        name = "test_operation",
        invalidFailureCategory = "invalid_test_output",
        providerInvalidOutputFailureStage = providerInvalidOutputFailureStage,
    )

private fun request(attempt: Int): StructuredModelRequest =
    StructuredModelRequest(
        systemPrompt = "prompt",
        userPayload = buildJsonObject {},
        outputSchema = StructuredOutputSchema(name = "schema", schema = buildJsonObject {}),
        metadata = StructuredModelRequestMetadata(
            requestId = "request-1",
            promptVersion = "test.v1",
            capability = StructuredModelCapability.UnderstandMessage,
            attemptNumber = attempt,
        ),
    )

private open class RecordingProvider : StructuredModelProvider {
    val requests = mutableListOf<StructuredModelRequest>()

    override suspend fun generate(request: StructuredModelRequest): StructuredModelResult {
        requests += request
        return StructuredModelResult(
            outputText = "{}",
            metadata = StructuredModelResultMetadata(
                provider = "test",
                model = "test",
                providerRequestId = "provider-request",
                attemptCount = request.metadata.attemptNumber,
                requestDiagnostics = request.metadata.diagnostics,
            ),
        )
    }
}

private class FailingProvider(
    private val failure: RuntimeException,
) : RecordingProvider() {
    override suspend fun generate(request: StructuredModelRequest): StructuredModelResult {
        requests += request
        throw failure
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
