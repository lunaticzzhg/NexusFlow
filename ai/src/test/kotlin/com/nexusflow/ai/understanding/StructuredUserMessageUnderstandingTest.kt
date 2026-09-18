package com.nexusflow.ai.understanding

import com.nexusflow.ai.provider.StructuredModelProvider
import com.nexusflow.ai.provider.StructuredModelRequest
import com.nexusflow.ai.provider.StructuredModelResult
import com.nexusflow.ai.provider.StructuredModelResultMetadata
import com.nexusflow.contracts.backendai.common.InvalidCapabilityResultException
import com.nexusflow.contracts.backendai.common.SelectableContextDefinitionPayload
import com.nexusflow.contracts.backendai.understanding.ActivePlanningContextPayload
import com.nexusflow.contracts.backendai.understanding.ConstraintDeltaOperation
import com.nexusflow.contracts.backendai.understanding.CurrentRequirement
import com.nexusflow.contracts.backendai.understanding.RequirementKind
import com.nexusflow.contracts.backendai.understanding.RequirementStrength
import com.nexusflow.contracts.backendai.understanding.RequirementValue
import com.nexusflow.contracts.backendai.understanding.TurnIntent
import com.nexusflow.contracts.backendai.understanding.UNDERSTAND_USER_MESSAGE_PROMPT_VERSION
import com.nexusflow.contracts.backendai.understanding.UnderstandMessageRequest
import com.nexusflow.contracts.backendai.understanding.UnderstandMessageResult
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StructuredUserMessageUnderstandingTest {
    @Test
    fun `classifies greetings thanks explanations weather and movie lookup as conversation`() =
        runBlocking {
            val cases = listOf(
                "你好",
                "谢谢",
                "Kotlin 协程是什么",
                "今天天气如何",
                "最近有什么电影",
            )

            cases.forEach { message ->
                val provider = RecordingProvider(conversationPayload())

                val result = StructuredUserMessageUnderstanding(provider).understand(
                    context(currentMessage = message),
                )

                assertEquals(TurnIntent.Conversation, result.turnIntent, message)
                assertNull(result.planningGoalPatch, message)
                assertEquals(emptyList(), result.constraintDeltas, message)
                assertFalse(result.clarification.needed, message)
            }
        }

    @Test
    fun `maps first planning turn to goal patch and upsert deltas`() =
        runBlocking {
            val provider = RecordingProvider(
                planningPayload(
                    planningGoalPatch = "周六晚上和女朋友看电影",
                    deltas = listOf(
                        upsertTimeWindow("周六晚上"),
                        upsertBudget("预算300", 300),
                        upsertActivityDomain("看电影", "movie"),
                    ),
                ),
            )

            val result = StructuredUserMessageUnderstanding(provider).understand(
                context(currentMessage = "周六晚上和女朋友看电影，预算300"),
            )

            assertEquals(TurnIntent.Planning, result.turnIntent)
            assertEquals("周六晚上和女朋友看电影", result.planningGoalPatch)
            assertEquals(3, result.constraintDeltas.size)
            assertDelta(result, RequirementKind.TimeWindow, ConstraintDeltaOperation.Upsert, "周六晚上")
            val budget = assertDelta(result, RequirementKind.BudgetLimit, ConstraintDeltaOperation.Upsert, "预算300")
            assertEquals(RequirementStrength.Must, budget.strength)
            assertEquals(300, assertIs<RequirementValue.BudgetLimit>(budget.value).wholeUnits)
            val domain = assertDelta(result, RequirementKind.ActivityDomain, ConstraintDeltaOperation.Upsert, "看电影")
            assertEquals("movie", assertIs<RequirementValue.ActivityDomain>(domain.value).value)
        }

    @Test
    fun `maps active planning budget update as planning upsert delta`() =
        runBlocking {
            val provider = RecordingProvider(
                planningPayload(deltas = listOf(upsertBudget("预算改800", 800))),
            )

            val result = StructuredUserMessageUnderstanding(provider).understand(
                context(
                    currentMessage = "预算改800",
                    activePlanning = activePlanning(),
                ),
            )

            assertEquals(TurnIntent.Planning, result.turnIntent)
            assertNull(result.planningGoalPatch)
            val budget = assertDelta(result, RequirementKind.BudgetLimit, ConstraintDeltaOperation.Upsert, "预算改800")
            assertEquals(800, assertIs<RequirementValue.BudgetLimit>(budget.value).wholeUnits)
        }

    @Test
    fun `maps active planning budget removal as planning remove delta`() =
        runBlocking {
            val provider = RecordingProvider(
                planningPayload(deltas = listOf(removeBudget("预算不限制了"))),
            )

            val result = StructuredUserMessageUnderstanding(provider).understand(
                context(
                    currentMessage = "预算不限制了",
                    activePlanning = activePlanning(),
                ),
            )

            assertEquals(TurnIntent.Planning, result.turnIntent)
            val budget = assertDelta(result, RequirementKind.BudgetLimit, ConstraintDeltaOperation.Remove, "预算不限制了")
            assertNull(budget.value)
            assertNull(budget.strength)
        }

    @Test
    fun `allows conversation to select context without planning mutation`() =
        runBlocking {
            val provider = RecordingProvider(conversationPayload(selectedKeys = listOf("profile.location")))

            val result = StructuredUserMessageUnderstanding(provider).understand(
                context(
                    currentMessage = "今天天气如何",
                    availableContextDefinitions = listOf("profile.location"),
                ),
            )

            assertEquals(TurnIntent.Conversation, result.turnIntent)
            assertEquals(listOf("profile.location"), result.contextSelection.selectedKeys)
            assertNull(result.planningGoalPatch)
            assertEquals(emptyList(), result.constraintDeltas)
        }

    @Test
    fun `rejects conversation with planning deltas`() =
        runBlocking {
            val provider = RecordingProvider(
                conversationPayload(deltas = listOf(upsertBudget("预算300", 300))),
            )

            val error = assertFailsWith<InvalidCapabilityResultException> {
                StructuredUserMessageUnderstanding(provider).understand(
                    context(currentMessage = "预算300"),
                )
            }

            assertEquals("invalid_intent_combination", error.failureStage)
        }

    @Test
    fun `rejects upsert without value and strength`() =
        runBlocking {
            val provider = RecordingProvider(
                planningPayload(
                    deltas = listOf(
                        StructuredConstraintDeltaPayload(
                            operation = "upsert",
                            kind = "budget_limit",
                            evidenceText = "预算300",
                        ),
                    ),
                ),
            )

            val error = assertFailsWith<InvalidCapabilityResultException> {
                StructuredUserMessageUnderstanding(provider).understand(
                    context(currentMessage = "预算300"),
                )
            }

            assertEquals("invalid_value_field", error.failureStage)
        }

    @Test
    fun `rejects remove with value or strength`() =
        runBlocking {
            val provider = RecordingProvider(
                planningPayload(
                    deltas = listOf(
                        upsertBudget("预算不限制了", 300).copy(operation = "remove"),
                    ),
                ),
            )

            val error = assertFailsWith<InvalidCapabilityResultException> {
                StructuredUserMessageUnderstanding(provider).understand(
                    context(currentMessage = "预算不限制了"),
                )
            }

            assertEquals("invalid_value_field", error.failureStage)
        }

    @Test
    fun `publishes v2 prompt version and R1 conversation first schema`() =
        runBlocking {
            val provider = RecordingProvider(conversationPayload())

            StructuredUserMessageUnderstanding(provider).understand(context(currentMessage = "最近有什么电影"))

            val request = assertNotNull(provider.requests.single())
            assertEquals("understand-user-message-v2", UNDERSTAND_USER_MESSAGE_PROMPT_VERSION)
            assertEquals("understand-user-message-v2", request.metadata.promptVersion)
            assertTrue(request.systemPrompt.contains("Prompt version: understand-user-message-v2"))
            assertTrue(request.systemPrompt.contains("conversation"))
            assertTrue(request.systemPrompt.contains("planning"))
            assertFalse(request.systemPrompt.contains("queryKind"))
            assertFalse(request.userPayload.toString().contains("task-test"))
            assertFalse(request.userPayload.toString().contains("taskRevision"))

            val schema = request.outputSchema.schema
            val required = schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(
                listOf("turnIntent", "planningGoalPatch", "constraintDeltas", "clarification", "contextSelection"),
                required,
            )
            val properties = schema.getValue("properties").jsonObject
            val intentValues = properties.getValue("turnIntent").jsonObject.getValue("enum").jsonArray
                .map { it.jsonPrimitive.content }
            assertEquals(listOf("conversation", "planning"), intentValues)
            val clarification = properties.getValue("clarification").jsonObject
            assertEquals(false, clarification.getValue("additionalProperties").jsonPrimitive.boolean)
        }
}

private fun assertDelta(
    result: UnderstandMessageResult,
    kind: RequirementKind,
    operation: ConstraintDeltaOperation,
    evidenceText: String,
) = result.constraintDeltas.single { it.kind == kind && it.operation == operation }
    .also { delta -> assertEquals(evidenceText, delta.evidenceText) }

private fun context(
    currentMessage: String,
    activePlanning: ActivePlanningContextPayload? = null,
    availableContextDefinitions: List<String> = emptyList(),
): UnderstandMessageRequest =
    UnderstandMessageRequest(
        aiRequestId = "understand-test",
        currentMessage = currentMessage,
        referenceTime = Instant.parse("2026-09-06T11:00:00Z"),
        timeZoneId = "Asia/Shanghai",
        activePlanning = activePlanning,
        availableContextDefinitions = availableContextDefinitions.map { key ->
            SelectableContextDefinitionPayload(
                key = key,
                description = "$key description",
                selectionHint = "$key hint",
            )
        },
    )

private fun activePlanning(): ActivePlanningContextPayload =
    ActivePlanningContextPayload(
        taskId = "task-test",
        taskRevision = 7,
        goal = "周六晚上和女朋友看电影",
        requirements = listOf(
            CurrentRequirement(
                kind = RequirementKind.BudgetLimit,
                value = RequirementValue.BudgetLimit(wholeUnits = 300, currencyCode = "CNY"),
                strength = RequirementStrength.Must,
            ),
        ),
    )

private fun conversationPayload(
    selectedKeys: List<String> = emptyList(),
    deltas: List<StructuredConstraintDeltaPayload> = emptyList(),
): String =
    TestJson.encodeToString(
        StructuredUnderstandingPayload(
            turnIntent = "conversation",
            planningGoalPatch = null,
            constraintDeltas = deltas,
            clarification = noClarification(),
            contextSelection = StructuredContextSelectionPayload(selectedKeys = selectedKeys),
        ),
    )

private fun planningPayload(
    planningGoalPatch: String? = null,
    deltas: List<StructuredConstraintDeltaPayload> = emptyList(),
): String =
    TestJson.encodeToString(
        StructuredUnderstandingPayload(
            turnIntent = "planning",
            planningGoalPatch = planningGoalPatch,
            constraintDeltas = deltas,
            clarification = noClarification(),
            contextSelection = StructuredContextSelectionPayload(selectedKeys = emptyList()),
        ),
    )

private fun upsertTimeWindow(evidenceText: String): StructuredConstraintDeltaPayload =
    StructuredConstraintDeltaPayload(
        operation = "upsert",
        kind = "time_window",
        value = StructuredRequirementValuePayload(
            type = "time_window",
            startAt = "2026-09-12T10:00:00Z",
            endAt = "2026-09-12T15:59:00Z",
            timeZoneId = "Asia/Shanghai",
        ),
        strength = "must",
        evidenceText = evidenceText,
    )

private fun upsertBudget(
    evidenceText: String,
    amount: Long,
): StructuredConstraintDeltaPayload =
    StructuredConstraintDeltaPayload(
        operation = "upsert",
        kind = "budget_limit",
        value = StructuredRequirementValuePayload(
            type = "budget_limit",
            amountWholeUnits = amount,
            currencyCode = "CNY",
        ),
        strength = "must",
        evidenceText = evidenceText,
    )

private fun removeBudget(evidenceText: String): StructuredConstraintDeltaPayload =
    StructuredConstraintDeltaPayload(
        operation = "remove",
        kind = "budget_limit",
        value = null,
        strength = null,
        evidenceText = evidenceText,
    )

private fun upsertActivityDomain(
    evidenceText: String,
    value: String,
): StructuredConstraintDeltaPayload =
    StructuredConstraintDeltaPayload(
        operation = "upsert",
        kind = "activity_domain",
        value = StructuredRequirementValuePayload(
            type = "activity_domain",
            textValue = value,
        ),
        strength = "must",
        evidenceText = evidenceText,
    )

private fun noClarification(): StructuredClarificationPayload =
    StructuredClarificationPayload(
        needed = false,
        missingInformation = emptyList(),
        reasonCategory = "none",
    )

private class RecordingProvider(
    private val output: String,
) : StructuredModelProvider {
    val requests = mutableListOf<StructuredModelRequest>()

    override suspend fun generate(request: StructuredModelRequest): StructuredModelResult {
        requests += request
        return StructuredModelResult(
            outputText = output,
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

private val TestJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
    encodeDefaults = true
}
