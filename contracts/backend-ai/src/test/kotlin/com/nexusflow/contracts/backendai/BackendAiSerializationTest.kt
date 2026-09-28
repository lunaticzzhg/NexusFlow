package com.nexusflow.contracts.backendai

import com.nexusflow.contracts.backendai.answer.AnswerModelMetadata
import com.nexusflow.contracts.backendai.answer.AnswerEvidencePayload
import com.nexusflow.contracts.backendai.answer.AnswerEvidenceFactKindPayload
import com.nexusflow.contracts.backendai.answer.AnswerEvidenceFactPayload
import com.nexusflow.contracts.backendai.answer.AnswerEvidenceFactValuePayload
import com.nexusflow.contracts.backendai.answer.AnswerInformationNeedPayload
import com.nexusflow.contracts.backendai.answer.AnswerNeedCoveragePayload
import com.nexusflow.contracts.backendai.answer.AnswerNeedCoverageStatus
import com.nexusflow.contracts.backendai.answer.ComposeConversationAnswerRequest
import com.nexusflow.contracts.backendai.answer.ComposeConversationAnswerResult
import com.nexusflow.contracts.backendai.answer.ResearchIssuePayload
import com.nexusflow.contracts.backendai.answer.ResearchIssueType
import com.nexusflow.contracts.backendai.common.ModelContextBlockPayload
import com.nexusflow.contracts.backendai.common.ModelContextTrustPayload
import com.nexusflow.contracts.backendai.common.StructuredModelCapability
import com.nexusflow.contracts.backendai.common.StructuredModelRequestDiagnostics
import com.nexusflow.contracts.backendai.conversation.ConversationDecisionMetadata
import com.nexusflow.contracts.backendai.conversation.ConversationDecisionRequest
import com.nexusflow.contracts.backendai.conversation.ConversationDecisionResult
import com.nexusflow.contracts.backendai.conversation.ConversationMessagePayload
import com.nexusflow.contracts.backendai.conversation.ConversationMessageRole
import com.nexusflow.contracts.backendai.conversation.ConversationTurnResult
import com.nexusflow.contracts.backendai.conversation.ConversationTurnRequest
import com.nexusflow.contracts.backendai.conversation.ConversationTurnMetadata
import com.nexusflow.contracts.backendai.conversation.InformationNeedMode
import com.nexusflow.contracts.backendai.conversation.InformationNeedProposal
import com.nexusflow.contracts.backendai.conversation.ReadOnlyToolCallProposal
import com.nexusflow.contracts.backendai.conversation.ReadOnlyToolDefinitionPayload
import com.nexusflow.contracts.backendai.planning.CandidateOpportunity
import com.nexusflow.contracts.backendai.planning.CandidateSourceRef
import com.nexusflow.contracts.backendai.planning.CreatePlansRequest
import com.nexusflow.contracts.backendai.planning.CreatePlansResult
import com.nexusflow.contracts.backendai.planning.ExplainPlansRequest
import com.nexusflow.contracts.backendai.planning.ExplainPlansResult
import com.nexusflow.contracts.backendai.planning.PlanDirection
import com.nexusflow.contracts.backendai.planning.PlanExplanationFact
import com.nexusflow.contracts.backendai.planning.PlanForExplanation
import com.nexusflow.contracts.backendai.planning.PlanNarrative
import com.nexusflow.contracts.backendai.planning.PlanNarrativePoint
import com.nexusflow.contracts.backendai.planning.PlanProposal
import com.nexusflow.contracts.backendai.planning.PlanningRequirement
import com.nexusflow.contracts.backendai.planning.PlanningRequirementStrength
import com.nexusflow.contracts.backendai.planning.PlanningResearchRequest
import com.nexusflow.contracts.backendai.planning.PlanningResearchResult
import com.nexusflow.contracts.backendai.understanding.ActivePlanningContextPayload
import com.nexusflow.contracts.backendai.understanding.ClarificationProposal
import com.nexusflow.contracts.backendai.understanding.ClarificationReasonCategory
import com.nexusflow.contracts.backendai.understanding.ConstraintDeltaOperation
import com.nexusflow.contracts.backendai.understanding.ConstraintDeltaProposal
import com.nexusflow.contracts.backendai.understanding.ContextSelectionProposal
import com.nexusflow.contracts.backendai.understanding.CurrentRequirement
import com.nexusflow.contracts.backendai.understanding.RequirementKind
import com.nexusflow.contracts.backendai.understanding.RequirementStrength
import com.nexusflow.contracts.backendai.understanding.RequirementValue
import com.nexusflow.contracts.backendai.understanding.TurnIntent
import com.nexusflow.contracts.backendai.understanding.UnderstandMessageRequest
import com.nexusflow.contracts.backendai.understanding.UnderstandMessageResult
import com.nexusflow.contracts.backendai.understanding.UnderstandingMetadata
import kotlinx.datetime.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class BackendAiSerializationTest {
    private val json = Json { encodeDefaults = false }

    @Test
    fun `turn intent serializes conversation and planning only`() {
        assertEquals("conversation", json.encodeToString(TurnIntent.Conversation).trim('"'))
        assertEquals("planning", json.encodeToString(TurnIntent.Planning).trim('"'))
        assertEquals(TurnIntent.Conversation, json.decodeFromString<TurnIntent>("\"conversation\""))
        assertEquals(TurnIntent.Planning, json.decodeFromString<TurnIntent>("\"planning\""))
    }

    @Test
    fun `conversation understanding request serializes without top level task identity`() {
        val request = UnderstandMessageRequest(
            aiRequestId = "understand-1",
            currentMessage = "今天天气如何？",
            referenceTime = Now,
            timeZoneId = "Asia/Shanghai",
            activePlanning = null,
        )

        val element = json.parseToJsonElement(json.encodeToString(request)).jsonObject

        assertEquals(JsonNull, element.getValue("activePlanning"))
        assertFalse(element.containsKey("taskId"))
        assertFalse(element.containsKey("taskRevision"))
        assertFalse(element.containsKey("intent"))
        assertFalse(element.containsKey("requirements"))
        assertEquals(request, json.decodeFromString<UnderstandMessageRequest>(json.encodeToString(request)))
    }

    @Test
    fun `planning understanding request serializes active planning context`() {
        val request = UnderstandMessageRequest(
            aiRequestId = "understand-2",
            currentMessage = "Budget 300",
            referenceTime = Now,
            timeZoneId = "Asia/Shanghai",
            activePlanning = ActivePlanningContextPayload(
                taskId = "task-1",
                taskRevision = 3,
                goal = "Find dinner",
                requirements = listOf(
                    CurrentRequirement(
                        kind = RequirementKind.BudgetLimit,
                        value = RequirementValue.BudgetLimit(300, "CNY"),
                        strength = RequirementStrength.Must,
                    ),
                ),
            ),
        )

        val element = json.parseToJsonElement(json.encodeToString(request)).jsonObject
        val activePlanning = element.getValue("activePlanning").jsonObject
        val requirement = activePlanning.getValue("requirements").jsonArray.first().jsonObject

        assertFalse(element.containsKey("taskId"))
        assertFalse(element.containsKey("taskRevision"))
        assertFalse(element.containsKey("intent"))
        assertFalse(element.containsKey("requirements"))
        assertEquals("task-1", activePlanning.getValue("taskId").jsonPrimitive.content)
        assertEquals(JsonPrimitive(3), activePlanning.getValue("taskRevision"))
        assertEquals("Find dinner", activePlanning.getValue("goal").jsonPrimitive.content)
        assertEquals("budget_limit", requirement.getValue("kind").jsonPrimitive.content)
        assertEquals(request, json.decodeFromString<UnderstandMessageRequest>(json.encodeToString(request)))
    }

    @Test
    fun `understanding planning result serializes turn proposals`() {
        val result = UnderstandMessageResult(
            turnIntent = TurnIntent.Planning,
            planningGoalPatch = "Find dinner",
            constraintDeltas = listOf(
                ConstraintDeltaProposal(
                    operation = ConstraintDeltaOperation.Upsert,
                    kind = RequirementKind.BudgetLimit,
                    value = RequirementValue.BudgetLimit(300, "CNY"),
                    strength = RequirementStrength.Must,
                    evidenceText = "Budget 300",
                ),
            ),
            clarification = ClarificationProposal(false, emptyList(), ClarificationReasonCategory.None, null),
            contextSelection = ContextSelectionProposal(),
            metadata = UnderstandingMetadata("test-provider", "test-model", "understand-user-message-v2", null, 1),
        )

        val resultElement = json.parseToJsonElement(json.encodeToString(result)).jsonObject
        val delta = resultElement.getValue("constraintDeltas").jsonArray.first().jsonObject

        assertEquals("planning", resultElement.getValue("turnIntent").jsonPrimitive.content)
        assertEquals("Find dinner", resultElement.getValue("planningGoalPatch").jsonPrimitive.content)
        assertEquals("upsert", delta.getValue("operation").jsonPrimitive.content)
        assertEquals("budget_limit", delta.getValue("kind").jsonPrimitive.content)
        assertEquals(result, json.decodeFromString<UnderstandMessageResult>(json.encodeToString(result)))
    }

    @Test
    fun `constraint delta upsert serializes value strength and evidence`() {
        val delta = ConstraintDeltaProposal(
            operation = ConstraintDeltaOperation.Upsert,
            kind = RequirementKind.ActivityDomain,
            value = RequirementValue.ActivityDomain("movie"),
            strength = RequirementStrength.Prefer,
            evidenceText = "看电影",
        )

        val element = json.parseToJsonElement(json.encodeToString(delta)).jsonObject

        assertEquals("upsert", element.getValue("operation").jsonPrimitive.content)
        assertEquals("activity_domain", element.getValue("kind").jsonPrimitive.content)
        assertEquals("activity_domain", element.getValue("value").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("prefer", element.getValue("strength").jsonPrimitive.content)
        assertEquals("看电影", element.getValue("evidenceText").jsonPrimitive.content)
        assertEquals(delta, json.decodeFromString<ConstraintDeltaProposal>(json.encodeToString(delta)))
    }

    @Test
    fun `constraint delta remove serializes value and strength as null`() {
        val delta = ConstraintDeltaProposal(
            operation = ConstraintDeltaOperation.Remove,
            kind = RequirementKind.BudgetLimit,
            evidenceText = "预算不限制了",
        )

        val element = json.parseToJsonElement(json.encodeToString(delta)).jsonObject

        assertEquals("remove", element.getValue("operation").jsonPrimitive.content)
        assertEquals("budget_limit", element.getValue("kind").jsonPrimitive.content)
        assertEquals(JsonNull, element.getValue("value"))
        assertEquals(JsonNull, element.getValue("strength"))
        assertEquals("预算不限制了", element.getValue("evidenceText").jsonPrimitive.content)
        assertEquals(delta, json.decodeFromString<ConstraintDeltaProposal>(json.encodeToString(delta)))
    }

    @Test
    fun `understanding result conversation allows context selection without planning changes`() {
        val result = UnderstandMessageResult(
            turnIntent = TurnIntent.Conversation,
            planningGoalPatch = null,
            constraintDeltas = emptyList(),
            clarification = ClarificationProposal(false, emptyList(), ClarificationReasonCategory.None, null),
            contextSelection = ContextSelectionProposal(selectedKeys = listOf("profile.preference.location")),
            metadata = UnderstandingMetadata("test-provider", "test-model", "understand-user-message-v2", null, 1),
        )

        val element = json.parseToJsonElement(json.encodeToString(result)).jsonObject

        assertEquals("conversation", element.getValue("turnIntent").jsonPrimitive.content)
        assertEquals(JsonNull, element.getValue("planningGoalPatch"))
        assertEquals(JsonArray(emptyList()), element.getValue("constraintDeltas"))
        assertEquals(
            "profile.preference.location",
            element.getValue("contextSelection").jsonObject.getValue("selectedKeys").jsonArray.first().jsonPrimitive.content,
        )
        assertEquals(result, json.decodeFromString<UnderstandMessageResult>(json.encodeToString(result)))
    }

    @Test
    fun `understanding result existing planning may omit goal patch and deltas`() {
        val result = UnderstandMessageResult(
            turnIntent = TurnIntent.Planning,
            planningGoalPatch = null,
            constraintDeltas = emptyList(),
            clarification = ClarificationProposal(false, emptyList(), ClarificationReasonCategory.None, null),
            contextSelection = ContextSelectionProposal(),
            metadata = UnderstandingMetadata("test-provider", "test-model", "understand-user-message-v2", null, 1),
        )

        val element = json.parseToJsonElement(json.encodeToString(result)).jsonObject

        assertEquals("planning", element.getValue("turnIntent").jsonPrimitive.content)
        assertEquals(JsonNull, element.getValue("planningGoalPatch"))
        assertEquals(JsonArray(emptyList()), element.getValue("constraintDeltas"))
        assertEquals(result, json.decodeFromString<UnderstandMessageResult>(json.encodeToString(result)))
    }

    @Test
    fun `conversation decision serializes information needs and backend tool budget`() {
        val request = conversationDecisionRequest()
        val result = ConversationDecisionResult(
            informationNeeds = listOf(
                InformationNeedProposal(
                    id = "need-greeting",
                    question = "回应问候",
                    mode = InformationNeedMode.MODEL_ONLY,
                ),
                InformationNeedProposal(
                    id = "need-weather",
                    question = "确认天气",
                    mode = InformationNeedMode.TOOL_REQUIRED,
                    toolCalls = listOf(
                        ReadOnlyToolCallProposal(
                            toolKey = "weather.forecast",
                            arguments = buildJsonObject {
                                put("latitude", 31.2304)
                                put("longitude", 121.4737)
                            },
                        ),
                    ),
                ),
            ),
            metadata = ConversationDecisionMetadata("test-provider", "test-model", "conversation-decision-v1", null, 1),
        )

        val requestElement = json.parseToJsonElement(json.encodeToString(request)).jsonObject
        val resultElement = json.parseToJsonElement(json.encodeToString(result)).jsonObject

        assertEquals("weather.forecast", requestElement.getValue("availableReadTools").jsonArray.first().jsonObject.getValue("toolKey").jsonPrimitive.content)
        assertEquals(JsonPrimitive(3), requestElement.getValue("maxReadToolCalls"))
        val needs = resultElement.getValue("informationNeeds").jsonArray
        assertEquals("model_only", needs.first().jsonObject.getValue("mode").jsonPrimitive.content)
        assertEquals("tool_required", needs[1].jsonObject.getValue("mode").jsonPrimitive.content)
        assertEquals("weather.forecast", needs[1].jsonObject.getValue("toolCalls").jsonArray.first().jsonObject.getValue("toolKey").jsonPrimitive.content)
        assertEquals(request, json.decodeFromString<ConversationDecisionRequest>(json.encodeToString(request)))
        assertEquals(result, json.decodeFromString<ConversationDecisionResult>(json.encodeToString(result)))
        assertEquals("conversation_decision", json.encodeToString(StructuredModelCapability.ConversationDecision).trim('"'))
    }

    @Test
    fun `conversation turn request and results serialize one pass outcome contract`() {
        val request = ConversationTurnRequest(
            aiRequestId = "turn-1",
            conversationId = "conversation-1",
            taskId = "task-1",
            taskRevision = 7,
            currentMessage = "预算300，今晚看电影",
            recentMessages = listOf(ConversationMessagePayload(ConversationMessageRole.Assistant, "之前的回复")),
            referenceTime = Now,
            timeZoneId = "Asia/Shanghai",
            availableReadTools = listOf(
                ReadOnlyToolDefinitionPayload(
                    toolKey = "movie.showtimes",
                    description = "Read movie showtimes.",
                    argumentHint = "movieTitle, location, date",
                ),
            ),
            maxReadToolCalls = 2,
            activePlanning = ActivePlanningContextPayload(
                taskId = "task-1",
                taskRevision = 7,
                goal = "看电影",
                requirements = listOf(CurrentRequirement(RequirementKind.BudgetLimit, RequirementValue.BudgetLimit(300, "CNY"), RequirementStrength.Must)),
            ),
        )
        val metadata = ConversationTurnMetadata("test-provider", "test-model", "conversation-turn-v1", "provider-turn-1", 1)
        val answer = ConversationTurnResult.Answer("可以，先选影院。", metadata)
        val research = ConversationTurnResult.Research(
            informationNeeds = listOf(
                InformationNeedProposal(
                    id = "need-showtimes",
                    question = "确认今晚电影场次",
                    mode = InformationNeedMode.TOOL_REQUIRED,
                    toolCalls = listOf(ReadOnlyToolCallProposal("movie.showtimes", buildJsonObject { put("location", "Shanghai") })),
                ),
            ),
            metadata = metadata,
        )
        val planning = ConversationTurnResult.Planning(
            planningGoalPatch = "今晚看电影",
            constraintDeltas = listOf(
                ConstraintDeltaProposal(
                    operation = ConstraintDeltaOperation.Upsert,
                    kind = RequirementKind.BudgetLimit,
                    value = RequirementValue.BudgetLimit(300, "CNY"),
                    strength = RequirementStrength.Must,
                    evidenceText = "预算300",
                ),
            ),
            clarification = ClarificationProposal(false, emptyList(), ClarificationReasonCategory.None, null),
            contextSelection = ContextSelectionProposal(),
            metadata = metadata,
        )

        val requestElement = json.parseToJsonElement(json.encodeToString(request)).jsonObject
        assertEquals("turn-1", requestElement.getValue("aiRequestId").jsonPrimitive.content)
        assertEquals("conversation-1", requestElement.getValue("conversationId").jsonPrimitive.content)
        assertEquals("task-1", requestElement.getValue("taskId").jsonPrimitive.content)
        assertEquals(JsonPrimitive(7), requestElement.getValue("taskRevision"))
        assertEquals("预算300，今晚看电影", requestElement.getValue("currentMessage").jsonPrimitive.content)
        assertEquals("assistant", requestElement.getValue("recentMessages").jsonArray.single().jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("2026-08-29T00:00:00Z", requestElement.getValue("referenceTime").jsonPrimitive.content)
        assertEquals("Asia/Shanghai", requestElement.getValue("timeZoneId").jsonPrimitive.content)
        assertEquals("movie.showtimes", requestElement.getValue("availableReadTools").jsonArray.first().jsonObject.getValue("toolKey").jsonPrimitive.content)
        assertEquals(JsonPrimitive(2), requestElement.getValue("maxReadToolCalls"))
        assertEquals("看电影", requestElement.getValue("activePlanning").jsonObject.getValue("goal").jsonPrimitive.content)
        assertEquals("conversation_turn", json.encodeToString(StructuredModelCapability.ConversationTurn).trim('"'))
        assertEquals(request, json.decodeFromString<ConversationTurnRequest>(json.encodeToString(request)))

        val answerElement = json.parseToJsonElement(json.encodeToString<ConversationTurnResult>(answer)).jsonObject
        val researchElement = json.parseToJsonElement(json.encodeToString<ConversationTurnResult>(research)).jsonObject
        val planningElement = json.parseToJsonElement(json.encodeToString<ConversationTurnResult>(planning)).jsonObject

        assertEquals("answer", answerElement.getValue("type").jsonPrimitive.content)
        assertEquals("research", researchElement.getValue("type").jsonPrimitive.content)
        assertEquals("planning", planningElement.getValue("type").jsonPrimitive.content)
        assertEquals("可以，先选影院。", answerElement.getValue("answer").jsonPrimitive.content)
        assertEquals("need-showtimes", researchElement.getValue("informationNeeds").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("今晚看电影", planningElement.getValue("planningGoalPatch").jsonPrimitive.content)
        assertEquals(answer, json.decodeFromString<ConversationTurnResult>(json.encodeToString<ConversationTurnResult>(answer)))
        assertEquals(research, json.decodeFromString<ConversationTurnResult>(json.encodeToString<ConversationTurnResult>(research)))
        assertEquals(planning, json.decodeFromString<ConversationTurnResult>(json.encodeToString<ConversationTurnResult>(planning)))
    }

    @Test
    fun `planning request only exposes backend supplied opportunities and proposal refs`() {
        val request = CreatePlansRequest(
            planningRequestId = "plan-task-1-3",
            taskId = "task-1",
            taskRevision = 3,
            intent = "Find dinner",
            requirements = listOf(PlanningRequirement("requirement-1", "BudgetLimit", "300 CNY", PlanningRequirementStrength.Must)),
            opportunities = listOf(opportunity()),
            referenceTime = Now,
            timeZoneId = "Asia/Shanghai",
            diagnostics = StructuredModelRequestDiagnostics(includedContextBlockCount = 0),
        )
        val result = CreatePlansResult(drafts = listOf(PlanProposal(PlanDirection.BestMatch, listOf("opportunity-1"))))

        val requestElement = json.parseToJsonElement(json.encodeToString(request)).jsonObject
        val resultElement = json.parseToJsonElement(json.encodeToString(result)).jsonObject

        assertFalse(requestElement.toString().contains("providerRequestId"))
        assertEquals(
            "opportunity-1",
            resultElement.getValue("drafts").jsonArray.first().jsonObject.getValue("opportunityRefs").jsonArray.first().jsonPrimitive.content,
        )
        assertEquals(request, json.decodeFromString<CreatePlansRequest>(json.encodeToString(request)))
    }

    @Test
    fun `planning research request serializes resolved context and offered read tools`() {
        val request = PlanningResearchRequest(
            planningResearchRequestId = "research-task-1-3",
            taskId = "task-1",
            taskRevision = 3,
            goal = "Plan a movie night",
            requirements = listOf(PlanningRequirement("requirement-1", "ActivityDomain", "movie", PlanningRequirementStrength.Must)),
            optionalContext = listOf(
                ModelContextBlockPayload(
                    key = "profile.preference.location",
                    trust = ModelContextTrustPayload.UserProfile,
                    content = buildJsonObject { put("city", "Shanghai") },
                ),
            ),
            availableReadTools = listOf(
                ReadOnlyToolDefinitionPayload(
                    toolKey = "movie.discovery",
                    description = "Discover current movie candidates.",
                    argumentHint = "location, dateWindow",
                ),
                ReadOnlyToolDefinitionPayload(
                    toolKey = "movie.showtimes",
                    description = "Read showtimes for bounded movie candidates.",
                    argumentHint = "movieTitle, location, date",
                ),
            ),
            referenceTime = Now,
            timeZoneId = "Asia/Shanghai",
            diagnostics = StructuredModelRequestDiagnostics(includedContextBlockCount = 1),
        )

        val element = json.parseToJsonElement(json.encodeToString(request)).jsonObject
        val tool = element.getValue("availableReadTools").jsonArray.first().jsonObject

        assertEquals("research-task-1-3", element.getValue("planningResearchRequestId").jsonPrimitive.content)
        assertEquals("task-1", element.getValue("taskId").jsonPrimitive.content)
        assertEquals(JsonPrimitive(3), element.getValue("taskRevision"))
        assertEquals("Plan a movie night", element.getValue("goal").jsonPrimitive.content)
        assertEquals("ActivityDomain", element.getValue("requirements").jsonArray.first().jsonObject.getValue("kind").jsonPrimitive.content)
        assertEquals("profile.preference.location", element.getValue("optionalContext").jsonArray.first().jsonObject.getValue("key").jsonPrimitive.content)
        assertEquals("movie.discovery", tool.getValue("toolKey").jsonPrimitive.content)
        assertFalse(element.toString().contains("credential"))
        assertFalse(element.toString().contains("rawPrompt"))
        assertFalse(element.toString().contains("fullTaskMessageHistory"))
        assertEquals(request, json.decodeFromString<PlanningResearchRequest>(json.encodeToString(request)))
    }

    @Test
    fun `planning research result serializes bounded read only proposals`() {
        val result = PlanningResearchResult(
            toolCalls = listOf(
                ReadOnlyToolCallProposal(
                    toolKey = "movie.discovery",
                    arguments = buildJsonObject {
                        put("location", "Shanghai")
                        put("dateWindow", "tonight")
                    },
                ),
                ReadOnlyToolCallProposal(
                    toolKey = "movie.showtimes",
                    arguments = buildJsonObject {
                        put("location", "Shanghai")
                        put("date", "2026-09-07")
                    },
                ),
            ),
        )

        val element = json.parseToJsonElement(json.encodeToString(result)).jsonObject
        val calls = element.getValue("toolCalls").jsonArray

        assertEquals("movie.discovery", calls.first().jsonObject.getValue("toolKey").jsonPrimitive.content)
        assertEquals("Shanghai", calls.first().jsonObject.getValue("arguments").jsonObject.getValue("location").jsonPrimitive.content)
        assertEquals(result, json.decodeFromString<PlanningResearchResult>(json.encodeToString(result)))
        assertEquals("planning_research", json.encodeToString(StructuredModelCapability.PlanningResearch).trim('"'))
    }

    @Test
    fun `plan explanation is grounded in validated facts`() {
        val request = ExplainPlansRequest(
            planningRequestId = "plan-task-1-3",
            plans = listOf(
                PlanForExplanation(
                    planId = "plan-1",
                    direction = PlanDirection.BestMatch,
                    opportunityRefs = listOf("opportunity-1"),
                    facts = listOf(PlanExplanationFact("fact-1", "Backend supplied fact")),
                ),
            ),
            referenceTime = Now,
            timeZoneId = "Asia/Shanghai",
        )
        val result = ExplainPlansResult(
            narratives = listOf(
                PlanNarrative(
                    planId = "plan-1",
                    title = "Dinner",
                    summary = "Grounded summary.",
                    reasons = listOf(PlanNarrativePoint("Uses supplied fact.", listOf("fact-1"))),
                    tradeoffs = emptyList(),
                ),
            ),
        )

        assertEquals(request, json.decodeFromString<ExplainPlansRequest>(json.encodeToString(request)))
        assertEquals(result, json.decodeFromString<ExplainPlansResult>(json.encodeToString(result)))
    }

    @Test
    fun `conversation answer request supports needs issues evidence and coverage`() {
        val request = ComposeConversationAnswerRequest(
            aiRequestId = "answer-1",
            conversationId = "conversation-1",
            taskId = null,
            taskRevision = null,
            question = "哪吒2讲什么？",
            recentMessages = listOf(ConversationMessagePayload(ConversationMessageRole.User, "上次聊电影")),
            referenceTime = Now,
            timeZoneId = "Asia/Shanghai",
            informationNeeds = listOf(
                AnswerInformationNeedPayload(
                    id = "need-movie",
                    question = "说明哪吒2剧情",
                    mode = InformationNeedMode.TOOL_REQUIRED,
                    evidenceSourceIds = listOf("tmdb"),
                    issues = emptyList(),
                ),
                AnswerInformationNeedPayload(
                    id = "need-showtime",
                    question = "确认今晚场次",
                    mode = InformationNeedMode.TOOL_REQUIRED,
                    evidenceSourceIds = emptyList(),
                    issues = listOf(ResearchIssuePayload(ResearchIssueType.EMPTY_RESULT)),
                ),
            ),
            evidence = listOf(
                AnswerEvidencePayload(
                    sourceId = "tmdb",
                    sourceUrl = "https://example.test/movie/1",
                    sourceKey = "movie.details",
                    facts = listOf(
                        AnswerEvidenceFactPayload(
                            kind = AnswerEvidenceFactKindPayload.TITLE,
                            value = AnswerEvidenceFactValuePayload.Text("哪吒2"),
                        ),
                        AnswerEvidenceFactPayload(
                            kind = AnswerEvidenceFactKindPayload.SUMMARY,
                            value = AnswerEvidenceFactValuePayload.Text("Animated fantasy sequel."),
                        ),
                    ),
                ),
                AnswerEvidencePayload(
                    sourceId = "cinema-page",
                    sourceKey = "movie.showtimes",
                    facts = listOf(
                        AnswerEvidenceFactPayload(
                            kind = AnswerEvidenceFactKindPayload.SUMMARY,
                            value = AnswerEvidenceFactValuePayload.Text("Tonight 20:00 at Futian Cinema."),
                        ),
                    ),
                ),
            ),
        )
        val result = ComposeConversationAnswerResult(
            answer = "哪吒2是一部动画奇幻续集。",
            coverage = listOf(
                AnswerNeedCoveragePayload(
                    needId = "need-movie",
                    status = AnswerNeedCoverageStatus.ANSWERED,
                    usedEvidenceSourceIds = listOf("tmdb"),
                ),
                AnswerNeedCoveragePayload(
                    needId = "need-showtime",
                    status = AnswerNeedCoverageStatus.UNRESOLVED,
                    usedEvidenceSourceIds = emptyList(),
                ),
            ),
            metadata = AnswerModelMetadata("test-provider", "test-model", "conversation-answer-v1", null, 1),
        )

        val requestElement = json.parseToJsonElement(json.encodeToString(request)).jsonObject
        val resultElement = json.parseToJsonElement(json.encodeToString(result)).jsonObject

        assertEquals("conversation-1", requestElement.getValue("conversationId").jsonPrimitive.content)
        assertEquals(JsonNull, requestElement.getValue("taskId"))
        assertEquals(JsonNull, requestElement.getValue("taskRevision"))
        assertEquals("need-movie", requestElement.getValue("informationNeeds").jsonArray.first().jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("empty_result", requestElement.getValue("informationNeeds").jsonArray[1].jsonObject.getValue("issues").jsonArray.first().jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("movie.details", requestElement.getValue("evidence").jsonArray.first().jsonObject.getValue("sourceKey").jsonPrimitive.content)
        assertEquals("title", requestElement.getValue("evidence").jsonArray.first().jsonObject.getValue("facts").jsonArray.first().jsonObject.getValue("kind").jsonPrimitive.content)
        assertFalse(requestElement.toString().contains("category"))
        assertFalse(requestElement.toString().contains("rawJson"))
        assertFalse(requestElement.toString().contains("rawHtml"))
        assertEquals("tmdb", resultElement.getValue("coverage").jsonArray.first().jsonObject.getValue("usedEvidenceSourceIds").jsonArray.first().jsonPrimitive.content)
        assertEquals("unresolved", resultElement.getValue("coverage").jsonArray[1].jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals(request, json.decodeFromString<ComposeConversationAnswerRequest>(json.encodeToString(request)))
        assertEquals(result, json.decodeFromString<ComposeConversationAnswerResult>(json.encodeToString(result)))
        assertEquals("conversation_answer", json.encodeToString(StructuredModelCapability.ConversationAnswer).trim('"'))
    }

    private fun conversationDecisionRequest(): ConversationDecisionRequest =
        ConversationDecisionRequest(
            aiRequestId = "conversation-decision-1",
            conversationId = "conversation-1",
            taskId = null,
            taskRevision = null,
            currentMessage = "你好",
            recentMessages = listOf(ConversationMessagePayload(ConversationMessageRole.User, "上次聊到天气")),
            referenceTime = Now,
            timeZoneId = "Asia/Shanghai",
            maxReadToolCalls = 3,
            availableReadTools = listOf(
                ReadOnlyToolDefinitionPayload(
                    toolKey = "weather.forecast",
                    description = "Read a weather forecast for a bounded location and date window.",
                    argumentHint = "latitude, longitude, dateFrom, dateTo",
                ),
            ),
        )

    private fun opportunity(): CandidateOpportunity =
        CandidateOpportunity(
            id = "opportunity-1",
            domain = "dining",
            title = "Dinner slot",
            summary = "Reserved table",
            location = "Futian",
            activityMode = "out_of_home",
            startsAt = Now,
            endsAt = Instant.parse("2026-08-29T04:00:00Z"),
            estimatedCostWholeUnits = 300,
            currencyCode = "CNY",
            commuteMinutes = 20,
            sources = listOf(
                CandidateSourceRef(
                    label = "Controlled Feed",
                    uri = "controlled://feed",
                    sourceUpdatedAt = Now,
                    sourceId = "controlled-feed",
                    authority = "StructuredPrimary",
                    factKeys = listOf("Title", "StartTime", "Location"),
                ),
            ),
            validUntil = Instant.parse("2026-08-30T00:00:00Z"),
        )
}

private val Now = Instant.parse("2026-08-29T00:00:00Z")
