package com.nexusflow.ai.understanding.openai

import com.nexusflow.ai.understanding.ConfirmedConstraint
import com.nexusflow.ai.understanding.ConstraintKind
import com.nexusflow.ai.understanding.ConstraintStrength
import com.nexusflow.ai.understanding.ConstraintValue
import com.nexusflow.ai.understanding.InvalidStructuredOutputException
import com.nexusflow.ai.understanding.ProviderRefusedException
import com.nexusflow.ai.understanding.ProviderTimeoutException
import com.nexusflow.ai.understanding.ProviderUnavailableException
import com.nexusflow.ai.understanding.UNDERSTAND_USER_MESSAGE_PROMPT_VERSION
import com.nexusflow.ai.understanding.UnderstandingContext
import com.nexusflow.ai.understanding.UserIntent
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OpenAiUserMessageUnderstandingTest {
    @Test
    fun `converts OpenAI structured response into typed proposal`() =
        runBlocking {
            val requests = mutableListOf<String>()
            val client = openAiClient(
                MockEngine { request ->
                    requests += request.bodyText()
                    jsonResponse(openAiResponse(payload(locationConstraint())))
                },
            )

            val outcome = adapter(client).understand(context())

            assertEquals(UserIntent.PlanRequest, outcome.userIntent)
            assertEquals("openai", outcome.metadata.provider)
            assertEquals("gpt-test", outcome.metadata.model)
            assertEquals(UNDERSTAND_USER_MESSAGE_PROMPT_VERSION, outcome.metadata.promptVersion)
            assertEquals("resp_1", outcome.metadata.providerRequestId)
            assertEquals(1, outcome.metadata.attemptCount)
            val constraint = outcome.extractedConstraints.single()
            assertEquals(ConstraintKind.Location, constraint.kind)
            assertEquals(ConstraintStrength.Hard, constraint.strength)
            assertIs<ConstraintValue.Location>(constraint.value)
            assertEquals("Futian", constraint.evidenceText)
            val format = JsonFormat.parseToJsonElement(requests.single())
                .jsonObject["text"]!!
                .jsonObject["format"]!!
                .jsonObject
            assertEquals("json_schema", format["type"]!!.jsonPrimitive.content)
            assertEquals(true, format["strict"]!!.jsonPrimitive.boolean)
            assertTrue(requests.single().contains(UNDERSTAND_USER_MESSAGE_PROMPT_VERSION))
            assertTrue(!requests.single().contains("tenantId"))
            client.close()
        }

    @Test
    fun `retries invalid structured output once then returns the second valid attempt`() =
        runBlocking {
            var attempts = 0
            val client = openAiClient(
                MockEngine {
                    attempts += 1
                    if (attempts == 1) {
                        jsonResponse(openAiResponse(payload(locationConstraint(evidenceText = "not in message"))))
                    } else {
                        jsonResponse(openAiResponse(payload(locationConstraint())))
                    }
                },
            )

            val outcome = adapter(client).understand(context())

            assertEquals(2, attempts)
            assertEquals(2, outcome.metadata.attemptCount)
            client.close()
        }

    @Test
    fun `stops after the second invalid structured output`() =
        runBlocking {
            var attempts = 0
            val client = openAiClient(
                MockEngine {
                    attempts += 1
                    jsonResponse(openAiResponse(payload(locationConstraint(evidenceText = "not in message"))))
                },
            )

            assertFailsWith<InvalidStructuredOutputException> {
                adapter(client).understand(context())
            }
            assertEquals(2, attempts)
            client.close()
        }

    @Test
    fun `rejects unknown constraint kind`() =
        runBlocking {
            val client = invalidPayloadClient(payload(locationConstraint().copy(kind = "unknown_kind")))

            assertFailsWith<InvalidStructuredOutputException> {
                adapter(client).understand(context())
            }
            client.close()
        }

    @Test
    fun `rejects budget constraint with text value`() =
        runBlocking {
            val client = invalidPayloadClient(
                payload(
                    budgetConstraint(
                        evidenceText = "Futian",
                        textValue = "Futian",
                    ),
                ),
            )

            assertFailsWith<InvalidStructuredOutputException> {
                adapter(client).understand(context())
            }
            client.close()
        }

    @Test
    fun `rejects text constraint with amount value`() =
        runBlocking {
            val client = invalidPayloadClient(payload(locationConstraint().copy(amountWholeUnits = 300)))

            assertFailsWith<InvalidStructuredOutputException> {
                adapter(client).understand(context())
            }
            client.close()
        }

    @Test
    fun `rejects commute constraint with currency code`() =
        runBlocking {
            val client = invalidPayloadClient(
                payload(
                    commuteConstraint(
                        evidenceText = "Futian",
                        currencyCode = "CNY",
                    ),
                ),
            )

            assertFailsWith<InvalidStructuredOutputException> {
                adapter(client).understand(context())
            }
            client.close()
        }

    @Test
    fun `rejects time window constraint with text value`() =
        runBlocking {
            val client = invalidPayloadClient(
                payload(
                    timeWindowConstraint(
                        evidenceText = "after 3pm",
                        textValue = "after 3pm",
                    ),
                ),
            )

            assertFailsWith<InvalidStructuredOutputException> {
                adapter(client).understand(context())
            }
            client.close()
        }

    @Test
    fun `rejects time window with reversed bounds`() =
        runBlocking {
            val client = invalidPayloadClient(
                payload(
                    timeWindowConstraint(evidenceText = "after 3pm").copy(
                        startAt = "2026-08-28T16:00:00Z",
                        endAt = "2026-08-28T15:00:00Z",
                    ),
                ),
            )

            assertFailsWith<InvalidStructuredOutputException> {
                adapter(client).understand(context())
            }
            client.close()
        }

    @Test
    fun `rejects time window with blank time zone`() =
        runBlocking {
            val client = invalidPayloadClient(
                payload(
                    timeWindowConstraint(evidenceText = "after 3pm").copy(timeZoneId = " "),
                ),
            )

            assertFailsWith<InvalidStructuredOutputException> {
                adapter(client).understand(context())
            }
            client.close()
        }

    @Test
    fun `rejects nonpositive budget amount`() =
        runBlocking {
            val client = invalidPayloadClient(
                payload(
                    budgetConstraint(evidenceText = "Futian").copy(amountWholeUnits = 0),
                ),
            )

            assertFailsWith<InvalidStructuredOutputException> {
                adapter(client).understand(context())
            }
            client.close()
        }

    @Test
    fun `rejects nonpositive commute minutes`() =
        runBlocking {
            val client = invalidPayloadClient(
                payload(
                    commuteConstraint(evidenceText = "Futian").copy(maxMinutes = 0),
                ),
            )

            assertFailsWith<InvalidStructuredOutputException> {
                adapter(client).understand(context())
            }
            client.close()
        }

    @Test
    fun `rejects text constraint with blank text value`() =
        runBlocking {
            val client = invalidPayloadClient(payload(locationConstraint().copy(textValue = " ")))

            assertFailsWith<InvalidStructuredOutputException> {
                adapter(client).understand(context())
            }
            client.close()
        }

    @Test
    fun `rejects blank evidence`() =
        runBlocking {
            val client = invalidPayloadClient(payload(locationConstraint(evidenceText = " ")))

            assertFailsWith<InvalidStructuredOutputException> {
                adapter(client).understand(context())
            }
            client.close()
        }

    @Test
    fun `rejects missing information without clarification`() =
        runBlocking {
            val client = invalidPayloadClient(
                payload(
                    missingInformation = listOf("preferred time"),
                    clarificationNeeded = false,
                ),
            )

            assertFailsWith<InvalidStructuredOutputException> {
                adapter(client).understand(context())
            }
            client.close()
        }

    @Test
    fun `rejects clarification with blank assistant message`() =
        runBlocking {
            val client = invalidPayloadClient(
                payload(
                    missingInformation = listOf("preferred time"),
                    clarificationNeeded = true,
                    assistantMessageDraft = " ",
                ),
            )

            assertFailsWith<InvalidStructuredOutputException> {
                adapter(client).understand(context())
            }
            client.close()
        }

    @Test
    fun `does not retry provider unavailable failures`() =
        runBlocking {
            var attempts = 0
            val client = openAiClient(
                MockEngine {
                    attempts += 1
                    jsonResponse("""{"error":{"message":"temporarily unavailable"}}""", HttpStatusCode.ServiceUnavailable)
                },
            )

            assertFailsWith<ProviderUnavailableException> {
                adapter(client).understand(context())
            }
            assertEquals(1, attempts)
            client.close()
        }

    @Test
    fun `does not retry provider timeout failures`() =
        runBlocking {
            var attempts = 0
            val client = openAiClient(
                MockEngine {
                    attempts += 1
                    jsonResponse("""{"error":{"message":"timeout"}}""", HttpStatusCode.RequestTimeout)
                },
            )

            assertFailsWith<ProviderTimeoutException> {
                adapter(client).understand(context())
            }
            assertEquals(1, attempts)
            client.close()
        }

    @Test
    fun `does not retry provider refusals`() =
        runBlocking {
            var attempts = 0
            val client = openAiClient(
                MockEngine {
                    attempts += 1
                    jsonResponse(openAiRefusalResponse())
                },
            )

            assertFailsWith<ProviderRefusedException> {
                adapter(client).understand(context())
            }
            assertEquals(1, attempts)
            client.close()
        }

    @Test
    fun `propagates cancellation`() =
        runBlocking {
            val client = openAiClient(MockEngine { throw CancellationException("cancelled") })

            assertFailsWith<CancellationException> {
                adapter(client).understand(context())
            }
            client.close()
        }
}

private fun adapter(client: HttpClient): OpenAiUserMessageUnderstanding =
    OpenAiUserMessageUnderstanding(
        client = client,
        apiKey = "test-key",
        model = "gpt-test",
        baseUrl = "https://api.test/v1",
    )

private fun openAiClient(engine: MockEngine): HttpClient =
    HttpClient(engine) {
        install(ContentNegotiation) {
            json(JsonFormat)
        }
    }

private fun invalidPayloadClient(payload: OpenAiUnderstandingPayload): HttpClient =
    openAiClient(
        MockEngine {
            jsonResponse(openAiResponse(payload))
        },
    )

private fun context(): UnderstandingContext =
    UnderstandingContext(
        aiRequestId = "ai-request-1",
        taskId = "task-1",
        taskVersion = 3,
        currentGoal = "Plan a coffee meetup",
        confirmedConstraints = listOf(
            ConfirmedConstraint(
                kind = ConstraintKind.BudgetLimit,
                value = ConstraintValue.BudgetLimit(wholeUnits = 200, currencyCode = "CNY"),
                strength = ConstraintStrength.Soft,
            ),
        ),
        currentMessage = "Please keep it around Futian after 3pm.",
        referenceTime = kotlinx.datetime.Instant.parse("2026-08-28T10:45:00Z"),
        timeZoneId = "Asia/Shanghai",
    )

private fun payload(
    vararg constraints: OpenAiConstraintPayload,
    missingInformation: List<String> = emptyList(),
    clarificationNeeded: Boolean = false,
    assistantMessageDraft: String? = null,
): OpenAiUnderstandingPayload =
    OpenAiUnderstandingPayload(
        userIntent = "plan_request",
        extractedConstraints = constraints.toList(),
        missingInformation = missingInformation,
        clarificationNeeded = clarificationNeeded,
        assistantMessageDraft = assistantMessageDraft,
    )

private fun locationConstraint(evidenceText: String = "Futian"): OpenAiConstraintPayload =
    OpenAiConstraintPayload(
        kind = "location",
        strength = "hard",
        evidenceText = evidenceText,
        textValue = "Futian",
        amountWholeUnits = null,
        currencyCode = null,
        maxMinutes = null,
        startAt = null,
        endAt = null,
        timeZoneId = null,
    )

private fun budgetConstraint(
    evidenceText: String,
    textValue: String? = null,
): OpenAiConstraintPayload =
    OpenAiConstraintPayload(
        kind = "budget_limit",
        strength = "hard",
        evidenceText = evidenceText,
        textValue = textValue,
        amountWholeUnits = 300,
        currencyCode = "CNY",
        maxMinutes = null,
        startAt = null,
        endAt = null,
        timeZoneId = null,
    )

private fun commuteConstraint(
    evidenceText: String,
    currencyCode: String? = null,
): OpenAiConstraintPayload =
    OpenAiConstraintPayload(
        kind = "commute_limit",
        strength = "hard",
        evidenceText = evidenceText,
        textValue = null,
        amountWholeUnits = null,
        currencyCode = currencyCode,
        maxMinutes = 30,
        startAt = null,
        endAt = null,
        timeZoneId = null,
    )

private fun timeWindowConstraint(
    evidenceText: String,
    textValue: String? = null,
): OpenAiConstraintPayload =
    OpenAiConstraintPayload(
        kind = "time_window",
        strength = "hard",
        evidenceText = evidenceText,
        textValue = textValue,
        amountWholeUnits = null,
        currencyCode = null,
        maxMinutes = null,
        startAt = "2026-08-28T15:00:00Z",
        endAt = "2026-08-28T16:00:00Z",
        timeZoneId = "Asia/Shanghai",
    )

private fun openAiResponse(payload: OpenAiUnderstandingPayload): String {
    val payloadText = JsonFormat.encodeToString(payload)
    return """
        {
          "id": "resp_1",
          "status": "completed",
          "output_text": ${JsonFormat.encodeToString(payloadText)}
        }
    """.trimIndent()
}

private fun openAiRefusalResponse(): String =
    """
        {
          "id": "resp_1",
          "status": "completed",
          "output": [
            {
              "type": "message",
              "content": [
                {
                  "type": "refusal",
                  "refusal": "I cannot comply."
                }
              ]
            }
          ]
        }
    """.trimIndent()

private fun MockRequestHandleScope.jsonResponse(
    content: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(content, status, headersOf(HttpHeaders.ContentType, "application/json"))

private fun HttpRequestData.bodyText(): String =
    when (val content = body) {
        is OutgoingContent.ByteArrayContent -> content.bytes().decodeToString()
        else -> error("Unexpected request body type: ${content::class.simpleName}")
    }

private val JsonFormat = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}
