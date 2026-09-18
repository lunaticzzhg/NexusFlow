package com.nexusflow.ai.answer

import com.nexusflow.ai.provider.StructuredModelProvider
import com.nexusflow.ai.provider.StructuredModelRequest
import com.nexusflow.ai.provider.StructuredModelResult
import com.nexusflow.ai.provider.StructuredModelResultMetadata
import com.nexusflow.contracts.backendai.answer.AnswerEvidenceFactKindPayload
import com.nexusflow.contracts.backendai.answer.AnswerEvidenceFactPayload
import com.nexusflow.contracts.backendai.answer.AnswerEvidenceFactValuePayload
import com.nexusflow.contracts.backendai.answer.AnswerEvidencePayload
import com.nexusflow.contracts.backendai.answer.AnswerInformationNeedPayload
import com.nexusflow.contracts.backendai.answer.AnswerNeedCoverageStatus
import com.nexusflow.contracts.backendai.answer.ComposeConversationAnswerRequest
import com.nexusflow.contracts.backendai.answer.ResearchIssuePayload
import com.nexusflow.contracts.backendai.answer.ResearchIssueType
import com.nexusflow.contracts.backendai.common.InvalidCapabilityResultException
import com.nexusflow.contracts.backendai.common.StructuredModelCapability
import com.nexusflow.contracts.backendai.common.StructuredModelUsage
import com.nexusflow.contracts.backendai.conversation.InformationNeedMode
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StructuredConversationAnswererTest {
    @Test
    fun `model only need can be answered with empty evidence`() =
        runBlocking {
            val provider = ScriptedAnswerProvider(
                """{"answer":"黑洞是引力极强的天体。","coverage":[{"needId":"need-model","status":"answered","usedEvidenceSourceIds":[]}]}""",
            )

            val result = StructuredConversationAnswerer(provider).answer(
                request(
                    needs = listOf(need("need-model", InformationNeedMode.MODEL_ONLY)),
                    evidence = emptyList(),
                ),
            )

            assertEquals("黑洞是引力极强的天体。", result.answer)
            assertEquals(AnswerNeedCoverageStatus.ANSWERED, result.coverage.single().status)
            assertEquals(CONVERSATION_ANSWER_PROMPT_VERSION, result.metadata.promptVersion)
            val modelRequest = provider.requests.single()
            assertEquals(StructuredModelCapability.ConversationAnswer, modelRequest.metadata.capability)
            assertEquals(CONVERSATION_ANSWER_SCHEMA_NAME, modelRequest.outputSchema.name)
            assertEquals("什么是黑洞？", modelRequest.userPayload["request"]!!.jsonObject["question"]!!.jsonPrimitive.content)
            assertFalse(modelRequest.userPayload.toString().contains("task-1"))
            assertFalse(modelRequest.userPayload.toString().contains("answer-request-1"))
        }

    @Test
    fun `tool required need must use matching evidence when answered`() =
        runBlocking {
            val provider = ScriptedAnswerProvider(
                """{"answer":"深圳今天多云。","coverage":[{"needId":"need-weather","status":"answered","usedEvidenceSourceIds":["weather-1"]}]}""",
            )

            val result = StructuredConversationAnswerer(provider).answer(
                request(
                    needs = listOf(need("need-weather", InformationNeedMode.TOOL_REQUIRED, evidenceSourceIds = listOf("weather-1"))),
                    evidence = listOf(evidence("weather-1", "weather.forecast")),
                ),
            )

            assertEquals(listOf("weather-1"), result.coverage.single().usedEvidenceSourceIds)
            val modelEvidence = provider.requests.single().userPayload["coreContext"]!!
                .jsonObject["evidence"]!!
                .jsonArray
                .single()
                .jsonObject
            assertEquals("weather.forecast", modelEvidence["sourceKey"]!!.jsonPrimitive.content)
        }

    @Test
    fun `tool required need without evidence must be unresolved`() =
        runBlocking {
            val provider = ScriptedAnswerProvider(
                """{"answer":"我不能确认实时天气。","coverage":[{"needId":"need-weather","status":"answered","usedEvidenceSourceIds":[]}]}""",
                """{"answer":"我不能确认实时天气。","coverage":[{"needId":"need-weather","status":"unresolved","usedEvidenceSourceIds":[]}]}""",
            )

            val result = StructuredConversationAnswerer(provider).answer(
                request(
                    needs = listOf(
                        need(
                            "need-weather",
                            InformationNeedMode.TOOL_REQUIRED,
                            issues = listOf(ResearchIssuePayload(ResearchIssueType.SOURCE_UNAVAILABLE)),
                        ),
                    ),
                    evidence = emptyList(),
                ),
            )

            assertEquals(AnswerNeedCoverageStatus.UNRESOLVED, result.coverage.single().status)
            assertEquals(2, provider.requests.size)
            assertTrue(provider.requests.last().systemPrompt.contains("Repair only"))
        }

    @Test
    fun `tool enhanced need can be answered without evidence when source unavailable`() =
        runBlocking {
            val provider = ScriptedAnswerProvider(
                """{"answer":"可以先选室内活动。","coverage":[{"needId":"need-activity","status":"answered","usedEvidenceSourceIds":[]}]}""",
            )

            val result = StructuredConversationAnswerer(provider).answer(
                request(
                    needs = listOf(
                        need(
                            "need-activity",
                            InformationNeedMode.TOOL_ENHANCED,
                            issues = listOf(ResearchIssuePayload(ResearchIssueType.SOURCE_UNAVAILABLE)),
                        ),
                    ),
                    evidence = emptyList(),
                ),
            )

            assertEquals(AnswerNeedCoverageStatus.ANSWERED, result.coverage.single().status)
        }

    @Test
    fun `mixed needs can include answered and unresolved coverage`() =
        runBlocking {
            val provider = ScriptedAnswerProvider(
                """{"answer":"天气无法确认；可以安排室内活动。","coverage":[{"needId":"need-weather","status":"unresolved","usedEvidenceSourceIds":[]},{"needId":"need-activity","status":"answered","usedEvidenceSourceIds":[]}]}""",
            )

            val result = StructuredConversationAnswerer(provider).answer(
                request(
                    needs = listOf(
                        need("need-weather", InformationNeedMode.TOOL_REQUIRED, issues = listOf(ResearchIssuePayload(ResearchIssueType.SOURCE_UNAVAILABLE))),
                        need("need-activity", InformationNeedMode.MODEL_ONLY),
                    ),
                    evidence = emptyList(),
                ),
            )

            assertEquals(listOf(AnswerNeedCoverageStatus.UNRESOLVED, AnswerNeedCoverageStatus.ANSWERED), result.coverage.map { it.status })
        }

    @Test
    fun `unknown evidence source missing coverage and duplicate coverage are rejected`() =
        runBlocking {
            val unknownProvider = ScriptedAnswerProvider(
                """{"answer":"Unsupported.","coverage":[{"needId":"need-weather","status":"answered","usedEvidenceSourceIds":["invented"]}]}""",
                """{"answer":"Unsupported.","coverage":[{"needId":"need-weather","status":"answered","usedEvidenceSourceIds":["invented"]}]}""",
            )
            assertFailsWith<InvalidCapabilityResultException> {
                StructuredConversationAnswerer(unknownProvider).answer(
                    request(
                        needs = listOf(need("need-weather", InformationNeedMode.TOOL_REQUIRED, evidenceSourceIds = listOf("weather-1"))),
                        evidence = listOf(evidence("weather-1", "weather.forecast")),
                    ),
                )
            }

            val missingProvider = ScriptedAnswerProvider(
                """{"answer":"Partial.","coverage":[{"needId":"need-a","status":"answered","usedEvidenceSourceIds":[]}]}""",
                """{"answer":"Partial.","coverage":[{"needId":"need-a","status":"answered","usedEvidenceSourceIds":[]}]}""",
            )
            assertFailsWith<InvalidCapabilityResultException> {
                StructuredConversationAnswerer(missingProvider).answer(
                    request(
                        needs = listOf(
                            need("need-a", InformationNeedMode.MODEL_ONLY),
                            need("need-b", InformationNeedMode.MODEL_ONLY),
                        ),
                        evidence = emptyList(),
                    ),
                )
            }

            val duplicateProvider = ScriptedAnswerProvider(
                """{"answer":"Dup.","coverage":[{"needId":"need-a","status":"answered","usedEvidenceSourceIds":[]},{"needId":"need-a","status":"answered","usedEvidenceSourceIds":[]}]}""",
                """{"answer":"Dup.","coverage":[{"needId":"need-a","status":"answered","usedEvidenceSourceIds":[]},{"needId":"need-a","status":"answered","usedEvidenceSourceIds":[]}]}""",
            )
            assertFailsWith<InvalidCapabilityResultException> {
                StructuredConversationAnswerer(duplicateProvider).answer(
                    request(needs = listOf(need("need-a", InformationNeedMode.MODEL_ONLY)), evidence = emptyList()),
                )
            }
            Unit
        }

    private fun request(
        needs: List<AnswerInformationNeedPayload>,
        evidence: List<AnswerEvidencePayload>,
    ): ComposeConversationAnswerRequest =
        ComposeConversationAnswerRequest(
            aiRequestId = "answer-request-1",
            conversationId = "conversation-1",
            taskId = "task-1",
            taskRevision = 2,
            question = "什么是黑洞？",
            recentMessages = emptyList(),
            referenceTime = Instant.parse("2026-09-07T04:00:00Z"),
            timeZoneId = "Asia/Shanghai",
            informationNeeds = needs,
            evidence = evidence,
        )

    private fun need(
        id: String,
        mode: InformationNeedMode,
        evidenceSourceIds: List<String> = emptyList(),
        issues: List<ResearchIssuePayload> = emptyList(),
    ): AnswerInformationNeedPayload =
        AnswerInformationNeedPayload(
            id = id,
            question = id,
            mode = mode,
            evidenceSourceIds = evidenceSourceIds,
            issues = issues,
        )

    private fun evidence(
        sourceId: String,
        sourceKey: String,
    ): AnswerEvidencePayload =
        AnswerEvidencePayload(
            sourceId = sourceId,
            sourceUrl = "https://example.com/source",
            sourceKey = sourceKey,
            facts = listOf(
                AnswerEvidenceFactPayload(
                    kind = AnswerEvidenceFactKindPayload.SUMMARY,
                    value = AnswerEvidenceFactValuePayload.Text("Source content."),
                ),
            ),
        )
}

private class ScriptedAnswerProvider(
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
