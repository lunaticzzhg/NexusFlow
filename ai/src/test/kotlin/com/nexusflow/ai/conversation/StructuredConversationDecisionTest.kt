package com.nexusflow.ai.conversation

import com.nexusflow.ai.provider.StructuredModelProvider
import com.nexusflow.ai.provider.StructuredModelRequest
import com.nexusflow.ai.provider.StructuredModelResult
import com.nexusflow.ai.provider.StructuredModelResultMetadata
import com.nexusflow.contracts.backendai.common.InvalidCapabilityResultException
import com.nexusflow.contracts.backendai.common.ModelContextBlockPayload
import com.nexusflow.contracts.backendai.common.ModelContextTrustPayload
import com.nexusflow.contracts.backendai.common.StructuredModelCapability
import com.nexusflow.contracts.backendai.common.StructuredModelUsage
import com.nexusflow.contracts.backendai.conversation.ConversationDecisionRequest
import com.nexusflow.contracts.backendai.conversation.ConversationMessagePayload
import com.nexusflow.contracts.backendai.conversation.ConversationMessageRole
import com.nexusflow.contracts.backendai.conversation.InformationNeedMode
import com.nexusflow.contracts.backendai.conversation.ReadOnlyToolDefinitionPayload
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StructuredConversationDecisionTest {
    @Test
    fun `greeting returns model only information need`() =
        runBlocking {
            val provider = ScriptedConversationProvider(
                decisionPayload(
                    needPayload(id = "need-greeting", question = "回应问候", mode = "model_only"),
                ),
            )

            val result = StructuredConversationDecision(provider).decide(request(currentMessage = "你好"))

            val need = result.informationNeeds.single()
            assertEquals("need-1", need.id)
            assertEquals(InformationNeedMode.MODEL_ONLY, need.mode)
            assertEquals(emptyList(), need.toolCalls)
            assertEquals(CONVERSATION_DECISION_PROMPT_VERSION, result.metadata.promptVersion)
            val modelRequest = provider.requests.single()
            assertEquals(StructuredModelCapability.ConversationDecision, modelRequest.metadata.capability)
            assertEquals(CONVERSATION_DECISION_SCHEMA_NAME, modelRequest.outputSchema.name)
        }

    @Test
    fun `current weather returns tool required weather call`() =
        runBlocking {
            val provider = ScriptedConversationProvider(
                decisionPayload(
                    needPayload(
                        id = "need-weather",
                        question = "确认今天上海天气",
                        mode = "tool_required",
                        toolCalls = listOf(
                            toolCallPayload(
                                "weather.forecast",
                                buildJsonObject {
                                    put("latitude", 31.2304)
                                    put("longitude", 121.4737)
                                },
                            ),
                        ),
                    ),
                ),
            )

            val result = StructuredConversationDecision(provider).decide(
                request(currentMessage = "今天上海天气", availableTools = defaultTools()),
            )

            val toolCall = result.informationNeeds.single().toolCalls.single()
            assertEquals(InformationNeedMode.TOOL_REQUIRED, result.informationNeeds.single().mode)
            assertEquals("weather.forecast", toolCall.toolKey)
            assertEquals(121.4737, toolCall.arguments.getValue("longitude").jsonPrimitive.double)
        }

    @Test
    fun `missing suitable tool keeps required need with capability hint`() =
        runBlocking {
            val provider = ScriptedConversationProvider(
                decisionPayload(
                    needPayload(
                        id = "need-sports",
                        question = "确认英超赛程",
                        mode = "tool_required",
                        requestedCapabilityHint = "sports fixtures",
                    ),
                ),
            )

            val result = StructuredConversationDecision(provider).decide(
                request(currentMessage = "最近有哪些英超比赛", availableTools = listOf(defaultTools().first())),
            )

            val need = result.informationNeeds.single()
            assertEquals(InformationNeedMode.TOOL_REQUIRED, need.mode)
            assertEquals(emptyList(), need.toolCalls)
            assertEquals(null, need.requestedCapabilityHint)
            assertFalse(provider.requests.single().systemPrompt.contains("Repair only"))
        }

    @Test
    fun `mixed question returns required and model only needs`() =
        runBlocking {
            val provider = ScriptedConversationProvider(
                decisionPayload(
                    needPayload(
                        id = "need-weather",
                        question = "确认今晚是否下雨",
                        mode = "tool_required",
                        toolCalls = listOf(toolCallPayload("weather.forecast")),
                    ),
                    needPayload(
                        id = "need-activities",
                        question = "给出下雨时适合的活动",
                        mode = "model_only",
                    ),
                ),
            )

            val result = StructuredConversationDecision(provider).decide(
                request(currentMessage = "今晚下雨吗？下雨做什么？", availableTools = defaultTools()),
            )

            assertEquals(listOf("need-1", "need-2"), result.informationNeeds.map { it.id })
            assertEquals(listOf(InformationNeedMode.TOOL_REQUIRED, InformationNeedMode.MODEL_ONLY), result.informationNeeds.map { it.mode })
        }

    @Test
    fun `tool enhanced recommendation may omit tool calls`() =
        runBlocking {
            val provider = ScriptedConversationProvider(
                decisionPayload(
                    needPayload(
                        id = "need-recommendation",
                        question = "给今晚室内活动建议",
                        mode = "tool_enhanced",
                    ),
                ),
            )

            val result = StructuredConversationDecision(provider).decide(request(currentMessage = "今晚有什么室内活动推荐？"))

            val need = result.informationNeeds.single()
            assertEquals(InformationNeedMode.TOOL_ENHANCED, need.mode)
            assertEquals(emptyList(), need.toolCalls)
        }

    @Test
    fun `unknown tool key is rejected and retried`() =
        runBlocking {
            val provider = ScriptedConversationProvider(
                decisionPayload(needPayload(toolCalls = listOf(toolCallPayload("invented.tool")))),
                decisionPayload(needPayload(toolCalls = listOf(toolCallPayload("weather.forecast")))),
            )

            val result = StructuredConversationDecision(provider).decide(
                request(currentMessage = "今天上海天气", availableTools = defaultTools()),
            )

            assertEquals("weather.forecast", result.informationNeeds.single().toolCalls.single().toolKey)
            assertEquals(2, provider.requests.size)
            assertTrue(provider.requests.last().systemPrompt.contains("Repair only"))
        }

    @Test
    fun `total tool calls over budget is rejected`() =
        runBlocking {
            val provider = ScriptedConversationProvider(
                decisionPayload(
                    needPayload(
                        toolCalls = listOf(
                            toolCallPayload("weather.forecast", buildJsonObject { put("city", "A") }),
                            toolCallPayload("weather.forecast", buildJsonObject { put("city", "B") }),
                        ),
                    ),
                ),
                decisionPayload(
                    needPayload(
                        toolCalls = listOf(
                            toolCallPayload("weather.forecast", buildJsonObject { put("city", "A") }),
                            toolCallPayload("weather.forecast", buildJsonObject { put("city", "B") }),
                        ),
                    ),
                ),
            )

            val error = assertFailsWith<InvalidCapabilityResultException> {
                StructuredConversationDecision(provider).decide(
                    request(currentMessage = "天气", availableTools = defaultTools(), maxReadToolCalls = 1),
                )
            }

            assertEquals("tool_call_budget_exceeded", error.failureStage)
        }

    @Test
    fun `same tool key different args and shared exact calls are legal`() =
        runBlocking {
            val provider = ScriptedConversationProvider(
                decisionPayload(
                    needPayload(
                        toolCalls = listOf(
                            toolCallPayload("weather.forecast", buildJsonObject { put("city", "深圳") }),
                            toolCallPayload("weather.forecast", buildJsonObject { put("city", "上海") }),
                        ),
                    ),
                ),
            )

            val result = StructuredConversationDecision(provider).decide(
                request(currentMessage = "深圳和上海天气", availableTools = defaultTools()),
            )

            assertEquals(2, result.informationNeeds.single().toolCalls.size)

            val duplicateProvider = ScriptedConversationProvider(
                decisionPayload(
                    needPayload(
                        id = "need-rain",
                        question = "确认深圳是否下雨",
                        toolCalls = listOf(toolCallPayload("weather.forecast", buildJsonObject { put("city", "深圳") })),
                    ),
                    needPayload(
                        id = "need-temperature",
                        question = "确认深圳气温",
                        toolCalls = listOf(toolCallPayload("weather.forecast", buildJsonObject { put("city", "深圳") })),
                    ),
                ),
            )

            val duplicateResult = StructuredConversationDecision(duplicateProvider).decide(
                request(currentMessage = "天气", availableTools = defaultTools(), maxReadToolCalls = 1),
            )

            assertEquals(listOf("need-1", "need-2"), duplicateResult.informationNeeds.map { it.id })
            assertEquals(
                listOf("深圳", "深圳"),
                duplicateResult.informationNeeds.map {
                    it.toolCalls.single().arguments.getValue("city").jsonPrimitive.content
                },
            )
        }

    @Test
    fun `request payload omits opaque ids and includes budget`() =
        runBlocking {
            val provider = ScriptedConversationProvider(decisionPayload(needPayload(mode = "model_only")))

            StructuredConversationDecision(provider).decide(
                request(
                    currentMessage = "你好",
                    recentMessages = listOf(ConversationMessagePayload(ConversationMessageRole.User, "上一轮聊到上海")),
                    optionalContext = listOf(
                        ModelContextBlockPayload(
                            key = "profile.preference.location",
                            trust = ModelContextTrustPayload.UserProfile,
                            content = buildJsonObject { put("city", "上海") },
                        ),
                    ),
                    availableTools = defaultTools(),
                    maxReadToolCalls = 3,
                ),
            )

            val renderedPayload = provider.requests.single().userPayload.toString()
            assertFalse(renderedPayload.contains("conversation-decision-test"))
            assertFalse(renderedPayload.contains("conversation-1"))
            assertFalse(renderedPayload.contains("task-1"))
            assertEquals(
                JsonPrimitive(3),
                provider.requests.single().userPayload.getValue("coreContext").jsonObject.getValue("maxReadToolCalls"),
            )
            assertEquals(
                "上一轮聊到上海",
                provider.requests.single().userPayload.getValue("request")
                    .jsonObject
                    .getValue("recentMessages")
                    .jsonArray
                    .single()
                    .jsonObject
                    .getValue("content")
                    .jsonPrimitive
                    .content,
            )
        }

    private fun request(
        currentMessage: String,
        recentMessages: List<ConversationMessagePayload> = emptyList(),
        optionalContext: List<ModelContextBlockPayload> = emptyList(),
        availableTools: List<ReadOnlyToolDefinitionPayload> = emptyList(),
        maxReadToolCalls: Int = 4,
    ): ConversationDecisionRequest =
        ConversationDecisionRequest(
            aiRequestId = "conversation-decision-test",
            conversationId = "conversation-1",
            taskId = "task-1",
            taskRevision = 7,
            currentMessage = currentMessage,
            recentMessages = recentMessages,
            referenceTime = Instant.parse("2026-09-07T04:00:00Z"),
            timeZoneId = "Asia/Shanghai",
            optionalContext = optionalContext,
            availableReadTools = availableTools,
            maxReadToolCalls = maxReadToolCalls,
        )

    private fun defaultTools(): List<ReadOnlyToolDefinitionPayload> =
        listOf(
            ReadOnlyToolDefinitionPayload(
                toolKey = "weather.forecast",
                description = "Read a weather forecast for a bounded location and date window.",
                argumentHint = "latitude, longitude, dateFrom, dateTo",
            ),
            ReadOnlyToolDefinitionPayload(
                toolKey = "movie.discovery",
                description = "Discover current or recent movies using a bounded location and date window.",
                argumentHint = "location, dateWindow",
            ),
            ReadOnlyToolDefinitionPayload(
                toolKey = "web.search",
                description = "Search the web for current public information.",
                argumentHint = "query, locale, dateWindow",
            ),
        )
}

private fun decisionPayload(vararg needs: InformationNeedPayload): String =
    ConversationTestJson.encodeToString(ConversationDecisionPayload(needs.toList()))

private fun needPayload(
    id: String = "need-1",
    question: String = "确认当前事实",
    mode: String = "tool_required",
    toolCalls: List<ReadOnlyToolCallPayload> = emptyList(),
    requestedCapabilityHint: String? = null,
): InformationNeedPayload =
    InformationNeedPayload(
        question = question,
        mode = mode,
        toolCalls = toolCalls,
    )

private fun toolCallPayload(
    toolKey: String,
    arguments: JsonObject = buildJsonObject {},
): ReadOnlyToolCallPayload =
    ReadOnlyToolCallPayload(toolKey = toolKey, arguments = arguments)

private class ScriptedConversationProvider(
    private vararg val outputs: String,
) : StructuredModelProvider {
    val requests = mutableListOf<StructuredModelRequest>()

    override suspend fun generate(request: StructuredModelRequest): StructuredModelResult {
        requests += request
        val outputIndex = requests.lastIndex.coerceAtMost(outputs.lastIndex)
        return StructuredModelResult(
            outputText = outputs[outputIndex],
            metadata = StructuredModelResultMetadata(
                provider = "test-provider",
                model = "test-model",
                providerRequestId = "provider-request-${requests.size}",
                attemptCount = request.metadata.attemptNumber,
                usage = StructuredModelUsage(inputTokens = 6, outputTokens = 9, totalTokens = 15),
                requestDiagnostics = request.metadata.diagnostics,
            ),
        )
    }
}

private val ConversationTestJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
    encodeDefaults = true
}
