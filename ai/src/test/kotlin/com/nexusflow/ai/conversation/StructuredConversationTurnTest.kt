package com.nexusflow.ai.conversation

import com.nexusflow.ai.provider.InvalidStructuredOutputException
import com.nexusflow.ai.provider.ProviderRequestException
import com.nexusflow.ai.provider.ProviderTimeoutException
import com.nexusflow.ai.provider.StreamingTurnModelProvider
import com.nexusflow.ai.provider.StructuredModelFinishCategory
import com.nexusflow.ai.provider.TurnModelRequest
import com.nexusflow.ai.provider.TurnModelResult
import com.nexusflow.ai.provider.TurnModelResultMetadata
import com.nexusflow.contracts.backendai.common.CapabilityTimeoutException
import com.nexusflow.contracts.backendai.common.CapabilityProviderRequestException
import com.nexusflow.contracts.backendai.common.InvalidCapabilityResultException
import com.nexusflow.contracts.backendai.common.ModelContextBlockPayload
import com.nexusflow.contracts.backendai.common.ModelContextTrustPayload
import com.nexusflow.contracts.backendai.common.StructuredModelCapability
import com.nexusflow.contracts.backendai.common.StructuredModelRequestDiagnostics
import com.nexusflow.contracts.backendai.conversation.ConversationTurnRequest
import com.nexusflow.contracts.backendai.conversation.ConversationTurnResult
import com.nexusflow.contracts.backendai.conversation.InformationNeedMode
import com.nexusflow.contracts.backendai.conversation.ReadOnlyToolDefinitionPayload
import com.nexusflow.contracts.backendai.understanding.ClarificationReasonCategory
import com.nexusflow.contracts.backendai.understanding.ConstraintDeltaOperation
import com.nexusflow.contracts.backendai.understanding.RequirementKind
import com.nexusflow.contracts.backendai.understanding.RequirementStrength
import com.nexusflow.contracts.backendai.understanding.RequirementValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

class StructuredConversationTurnTest {
    @Test
    fun `direct text result streams deltas and returns matching final answer`() = runBlocking {
        val provider = RecordingTurnProvider { _, onDelta ->
            onDelta("Hello")
            onDelta(", Shanghai")
            TurnModelResult.Text(" Hello, Shanghai ", metadata())
        }
        val turn = StructuredConversationTurn(provider)
        val deltas = mutableListOf<String>()

        val result = turn.execute(request()) { deltas += it }

        val answer = assertIs<ConversationTurnResult.Answer>(result)
        assertEquals("Hello, Shanghai", answer.answer)
        assertEquals(answer.answer, deltas.joinToString(separator = ""))
        assertEquals("test-provider", answer.metadata.provider)
        assertEquals("conversation-turn-v1", answer.metadata.promptVersion)
        assertEquals(1, provider.requests.size)
        assertEquals(StructuredModelCapability.ConversationTurn, provider.requests.single().metadata.capability)
    }

    @Test
    fun `request payload exposes bounded context without opaque conversation id`() = runBlocking {
        val provider = RecordingTurnProvider { _, _ ->
            TurnModelResult.Text("Done", metadata())
        }
        val turn = StructuredConversationTurn(provider)

        turn.execute(request()) {}

        val modelRequest = provider.requests.single()
        val payload = modelRequest.userPayload
        val requestPayload = payload.getValue("request").jsonObject
        val coreContext = payload.getValue("coreContext").jsonObject
        assertEquals("2026-09-19T00:00:00Z", requestPayload.getValue("referenceTime").jsonPrimitive.content)
        assertEquals("Asia/Shanghai", requestPayload.getValue("timeZoneId").jsonPrimitive.content)
        assertEquals(
            "weather.forecast",
            coreContext.getValue("availableReadTools")
                .jsonArray
                .single()
                .jsonObject
                .getValue("toolKey")
                .jsonPrimitive
                .content,
        )
        assertEquals("1", coreContext.getValue("maxReadToolCalls").jsonPrimitive.content)
        assertEquals(2, modelRequest.tools.size)
        assertEquals(listOf("research", "planning"), modelRequest.tools.map { it.name })
        assertEquals("turn-test-1", modelRequest.metadata.requestId)
        assertEquals(StructuredModelCapability.ConversationTurn, modelRequest.metadata.capability)
        assertEquals(1, modelRequest.metadata.diagnostics.includedContextBlockCount)
        assertEquals(1, modelRequest.metadata.diagnostics.selectedContextKeyCount)
        assertEquals(1, modelRequest.metadata.diagnostics.resolvedContextBlockCount)
        assertFalse(payload.toString().contains("conversation-1"))
    }

    @Test
    fun `research tool result accepts offered tool calls inside budget`() = runBlocking {
        val provider = RecordingTurnProvider { _, _ ->
            TurnModelResult.ToolCall(
                name = "research",
                argumentsJson = researchPayload(
                    researchNeed("need-weather", "Check weather"),
                    researchNeed(
                        id = "need-background",
                        question = "Explain weather terms",
                        mode = "model_only",
                        toolKey = null,
                    ),
                ),
                metadata = metadata(),
            )
        }
        val turn = StructuredConversationTurn(provider)

        val result = assertIs<ConversationTurnResult.Research>(turn.execute(request()) {})

        val need = result.informationNeeds.first()
        assertEquals("need-weather", need.id)
        assertEquals(InformationNeedMode.TOOL_REQUIRED, need.mode)
        assertEquals("weather.forecast", need.toolCalls.single().toolKey)
        assertEquals(InformationNeedMode.MODEL_ONLY, result.informationNeeds[1].mode)
    }

    @Test
    fun `research tool result rejects unknown tools over budget duplicate needs and invalid modes`() = runBlocking {
        val invalidPayloads = listOf(
            researchPayload(researchNeed("need-weather", "Check weather", toolKey = "invented.tool")),
            researchPayload(
                researchNeed("need-a", "Check A", arguments = """{"city":"A"}"""),
                researchNeed("need-b", "Check B", arguments = """{"city":"B"}"""),
            ),
            researchPayload(
                researchNeed("same", "Check A", mode = "model_only", toolKey = null),
                researchNeed("same", "Check B", mode = "model_only", toolKey = null),
            ),
            researchPayload(
                researchNeed("need-weather", "Check weather", mode = "model_only"),
            ),
            researchPayload(
                researchNeed(
                    id = "need-weather",
                    question = "Check weather",
                    mode = "tool_required",
                    toolKey = null,
                ),
            ),
        )

        invalidPayloads.forEach { payload ->
            val turn = StructuredConversationTurn(
                RecordingTurnProvider { _, _ ->
                    TurnModelResult.ToolCall(name = "research", argumentsJson = payload, metadata = metadata())
                },
            )

            assertFailsWith<InvalidCapabilityResultException> {
                turn.execute(request(maxReadToolCalls = 1)) {}
            }
        }
    }

    @Test
    fun `planning tool result validates understanding semantics`() = runBlocking {
        val provider = RecordingTurnProvider { _, _ ->
            TurnModelResult.ToolCall(
                name = "planning",
                argumentsJson = planningPayload(),
                metadata = metadata(),
            )
        }
        val turn = StructuredConversationTurn(provider)

        val result = assertIs<ConversationTurnResult.Planning>(
            turn.execute(request(currentMessage = "预算300，今晚看电影")) {},
        )

        assertEquals("Plan within budget", result.planningGoalPatch)
        assertEquals(1, result.constraintDeltas.size)
        val delta = result.constraintDeltas.single()
        assertEquals(ConstraintDeltaOperation.Upsert, delta.operation)
        assertEquals(RequirementKind.BudgetLimit, delta.kind)
        assertEquals(RequirementStrength.Must, delta.strength)
        assertEquals(RequirementValue.BudgetLimit(300, "CNY"), delta.value)
        assertEquals("预算300", delta.evidenceText)
    }

    @Test
    fun `planning tool result supports clarification without goal patch`() = runBlocking {
        val provider = RecordingTurnProvider { _, _ ->
            TurnModelResult.ToolCall(
                name = "planning",
                argumentsJson = planningPayload(
                    goalPatch = null,
                    constraintDeltas = "[]",
                    clarification = """"clarification":{"needed":true,"missingInformation":["date"],"reasonCategory":"missing_required_information","questionDraft":"哪一天出发？"}""",
                ),
                metadata = metadata(),
            )
        }
        val turn = StructuredConversationTurn(provider)

        val result = assertIs<ConversationTurnResult.Planning>(
            turn.execute(request(currentMessage = "帮我计划旅行")) {},
        )

        assertEquals(null, result.planningGoalPatch)
        assertEquals(true, result.clarification.needed)
        assertEquals(ClarificationReasonCategory.MissingRequiredInformation, result.clarification.reasonCategory)
        assertEquals("哪一天出发？", result.clarification.questionDraft)
    }

    @Test
    fun `planning tool rejects invalid intent evidence value clarification and context selection`() = runBlocking {
        val invalidPayloads = listOf(
            planningPayload(turnIntent = "conversation"),
            planningPayload(evidenceText = "not in message"),
            planningPayload(value = """{"type":"location","textValue":"上海"}"""),
            planningPayload(value = "null"),
            planningPayload(strength = "null"),
            planningPayload(
                goalPatch = "\"Plan within budget\"",
                clarification = """"clarification":{"needed":true,"missingInformation":["date"],"reasonCategory":"missing_required_information","questionDraft":"哪一天？"}""",
            ),
            planningPayload(contextSelection = """"contextSelection":{"selectedKeys":["profile.preference.location"]}"""),
        )

        invalidPayloads.forEach { payload ->
            val turn = StructuredConversationTurn(
                RecordingTurnProvider { _, _ ->
                    TurnModelResult.ToolCall(name = "planning", argumentsJson = payload, metadata = metadata())
                },
            )

            assertFailsWith<InvalidCapabilityResultException> {
                turn.execute(request(currentMessage = "预算300，今晚看电影")) {}
            }
        }
    }

    @Test
    fun `provider dependency structured output and cancellation map to capability outcomes`() = runBlocking {
        assertFailsWith<CapabilityProviderRequestException> {
            StructuredConversationTurn(ThrowingTurnProvider(ProviderRequestException())).execute(request()) {}
        }
        assertFailsWith<CapabilityTimeoutException> {
            StructuredConversationTurn(ThrowingTurnProvider(ProviderTimeoutException())).execute(request()) {}
        }
        assertFailsWith<InvalidCapabilityResultException> {
            StructuredConversationTurn(
                ThrowingTurnProvider(InvalidStructuredOutputException("bad turn")),
            ).execute(request()) {}
        }
        assertFailsWith<CancellationException> {
            StructuredConversationTurn(ThrowingTurnProvider(CancellationException("cancelled"))).execute(request()) {}
        }
        Unit
    }

    @Test
    fun `unknown operation is rejected`() = runBlocking {
        val turn = StructuredConversationTurn(
            RecordingTurnProvider { _, _ ->
                TurnModelResult.ToolCall(name = "write_calendar", argumentsJson = "{}", metadata = metadata())
            },
        )

        assertFailsWith<InvalidCapabilityResultException> {
            turn.execute(request()) {}
        }
        Unit
    }

    private fun request(
        currentMessage: String = "What is the weather?",
        maxReadToolCalls: Int = 1,
    ): ConversationTurnRequest =
        ConversationTurnRequest(
            aiRequestId = "turn-test-1",
            conversationId = "conversation-1",
            taskId = null,
            taskRevision = null,
            currentMessage = currentMessage,
            recentMessages = emptyList(),
            referenceTime = Instant.parse("2026-09-19T00:00:00Z"),
            timeZoneId = "Asia/Shanghai",
            optionalContext = listOf(
                ModelContextBlockPayload(
                    key = "profile.preference.location",
                    trust = ModelContextTrustPayload.UserProfile,
                    content = buildJsonObject { put("city", "Shanghai") },
                ),
            ),
            availableReadTools = listOf(ReadOnlyToolDefinitionPayload("weather.forecast", "Read weather.", "city")),
            maxReadToolCalls = maxReadToolCalls,
            diagnostics = StructuredModelRequestDiagnostics(),
        )

    private fun researchPayload(vararg needs: String): String =
        """{"informationNeeds":[${needs.joinToString(separator = ",")}]}"""

    private fun researchNeed(
        id: String,
        question: String,
        mode: String = "tool_required",
        toolKey: String? = "weather.forecast",
        arguments: String = """{"city":"Shanghai"}""",
    ): String {
        val toolCalls = toolKey?.let { """[{"toolKey":"$it","arguments":$arguments}]""" } ?: "[]"
        return """
            {
              "id":"$id",
              "question":"$question",
              "mode":"$mode",
              "toolCalls":$toolCalls
            }
        """.trimIndent()
    }

    private fun planningPayload(
        turnIntent: String = "planning",
        goalPatch: String? = "\"Plan within budget\"",
        evidenceText: String = "预算300",
        value: String = """{"type":"budget_limit","amountWholeUnits":300,"currencyCode":"CNY"}""",
        strength: String = "\"must\"",
        constraintDeltas: String =
            """
                [{
                  "operation":"upsert",
                  "kind":"budget_limit",
                  "value":$value,
                  "strength":$strength,
                  "evidenceText":"$evidenceText"
                }]
            """.trimIndent(),
        clarification: String =
            """"clarification":{"needed":false,"missingInformation":[],"reasonCategory":"none","questionDraft":null}""",
        contextSelection: String = """"contextSelection":{"selectedKeys":[]}""",
    ): String =
        """
            {
              "turnIntent":"$turnIntent",
              "planningGoalPatch":$goalPatch,
              "constraintDeltas":$constraintDeltas,
              $clarification,
              $contextSelection
            }
        """.trimIndent()

    private class RecordingTurnProvider(
        private val handler: suspend (TurnModelRequest, suspend (String) -> Unit) -> TurnModelResult,
    ) : StreamingTurnModelProvider {
        val requests = mutableListOf<TurnModelRequest>()

        override suspend fun streamTurn(
            request: TurnModelRequest,
            onTextDelta: suspend (String) -> Unit,
        ): TurnModelResult {
            requests += request
            return handler(request, onTextDelta)
        }
    }

    private class ThrowingTurnProvider(
        private val failure: RuntimeException,
    ) : StreamingTurnModelProvider {
        override suspend fun streamTurn(
            request: TurnModelRequest,
            onTextDelta: suspend (String) -> Unit,
        ): TurnModelResult {
            throw failure
        }
    }

    private fun metadata(): TurnModelResultMetadata =
        TurnModelResultMetadata(
            provider = "test-provider",
            model = "test-model",
            providerRequestId = "provider-turn-1",
            attemptCount = 1,
            finishCategory = StructuredModelFinishCategory.Complete,
        )
}
