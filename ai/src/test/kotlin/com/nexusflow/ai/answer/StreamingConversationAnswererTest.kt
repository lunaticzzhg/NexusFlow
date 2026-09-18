package com.nexusflow.ai.answer

import com.nexusflow.ai.provider.ProviderUnavailableException
import com.nexusflow.ai.provider.StreamingTextModelProvider
import com.nexusflow.ai.provider.TextModelRequest
import com.nexusflow.ai.provider.TextModelResult
import com.nexusflow.ai.provider.TextModelResultMetadata
import com.nexusflow.contracts.backendai.answer.AnswerEvidenceFactKindPayload
import com.nexusflow.contracts.backendai.answer.AnswerEvidenceFactPayload
import com.nexusflow.contracts.backendai.answer.AnswerEvidenceFactValuePayload
import com.nexusflow.contracts.backendai.answer.AnswerEvidencePayload
import com.nexusflow.contracts.backendai.answer.AnswerInformationNeedPayload
import com.nexusflow.contracts.backendai.answer.AnswerNeedCoverageStatus
import com.nexusflow.contracts.backendai.answer.ComposeConversationAnswerRequest
import com.nexusflow.contracts.backendai.common.CapabilityUnavailableException
import com.nexusflow.contracts.backendai.conversation.InformationNeedMode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StreamingConversationAnswererTest {
    @Test
    fun `emits provider deltas and final answer is assembled from the same stream`() =
        runBlocking {
            val provider = ScriptedStreamingTextProvider("深", "圳", "可以安排室内活动。")
            val deltas = mutableListOf<String>()

            val result = StreamingConversationAnswerer(provider).answer(request()) { delta ->
                deltas += delta
            }

            assertEquals(listOf("深", "圳", "可以安排室内活动。"), deltas)
            assertEquals("深圳可以安排室内活动。", result.answer)
            assertEquals(result.answer, deltas.joinToString(separator = ""))
            assertEquals(AnswerNeedCoverageStatus.ANSWERED, result.coverage.single { it.needId == "model" }.status)
            assertEquals(AnswerNeedCoverageStatus.UNRESOLVED, result.coverage.single { it.needId == "required" }.status)
            assertEquals(CONVERSATION_ANSWER_PROMPT_VERSION, result.metadata.promptVersion)
            assertEquals(1, provider.requests.size)
        }

    @Test
    fun `stream provider error surfaces as capability unavailable after emitted deltas`() =
        runBlocking {
            val provider = ScriptedStreamingTextProvider("partial", failure = ProviderUnavailableException())
            val deltas = mutableListOf<String>()

            assertFailsWith<CapabilityUnavailableException> {
                StreamingConversationAnswerer(provider).answer(request()) { delta ->
                    deltas += delta
                }
            }

            assertEquals(listOf("partial"), deltas)
        }

    @Test
    fun `cancellation propagates to provider stream`() =
        runBlocking {
            val started = CompletableDeferred<Boolean>()
            val cancelled = CompletableDeferred<Boolean>()
            val provider = object : StreamingTextModelProvider {
                override suspend fun stream(
                    request: TextModelRequest,
                    onDelta: suspend (String) -> Unit,
                ): TextModelResult =
                    suspendCancellableCoroutine { continuation ->
                        started.complete(true)
                        continuation.invokeOnCancellation { cancelled.complete(true) }
                    }
            }

            val job = launch {
                StreamingConversationAnswerer(provider).answer(request()) { }
            }

            started.await()
            job.cancelAndJoin()

            assertTrue(cancelled.await())
        }

    private fun request(): ComposeConversationAnswerRequest =
        ComposeConversationAnswerRequest(
            aiRequestId = "answer-request-1",
            conversationId = "conversation-1",
            taskId = "task-1",
            taskRevision = 2,
            question = "深圳周末做什么？",
            recentMessages = emptyList(),
            referenceTime = Instant.parse("2026-09-07T04:00:00Z"),
            timeZoneId = "Asia/Shanghai",
            informationNeeds = listOf(
                AnswerInformationNeedPayload(
                    id = "model",
                    question = "给出活动建议",
                    mode = InformationNeedMode.MODEL_ONLY,
                ),
                AnswerInformationNeedPayload(
                    id = "required",
                    question = "确认实时天气",
                    mode = InformationNeedMode.TOOL_REQUIRED,
                ),
                AnswerInformationNeedPayload(
                    id = "evidence",
                    question = "确认电影信息",
                    mode = InformationNeedMode.TOOL_REQUIRED,
                    evidenceSourceIds = listOf("movie-1"),
                ),
            ),
            evidence = listOf(
                AnswerEvidencePayload(
                    sourceId = "movie-1",
                    sourceKey = "movie.showtimes",
                    facts = listOf(
                        AnswerEvidenceFactPayload(
                            kind = AnswerEvidenceFactKindPayload.TITLE,
                            value = AnswerEvidenceFactValuePayload.Text("Test Movie"),
                        ),
                    ),
                ),
            ),
        )
}

private class ScriptedStreamingTextProvider(
    private vararg val deltas: String,
    private val failure: Throwable? = null,
) : StreamingTextModelProvider {
    val requests = mutableListOf<TextModelRequest>()

    override suspend fun stream(
        request: TextModelRequest,
        onDelta: suspend (String) -> Unit,
    ): TextModelResult {
        requests += request
        deltas.forEach { onDelta(it) }
        failure?.let { throw it }
        return TextModelResult(
            outputText = deltas.joinToString(separator = ""),
            metadata = TextModelResultMetadata(
                provider = "test",
                model = "streaming",
                providerRequestId = "provider-request-1",
                attemptCount = request.metadata.attemptNumber,
            ),
        )
    }
}
