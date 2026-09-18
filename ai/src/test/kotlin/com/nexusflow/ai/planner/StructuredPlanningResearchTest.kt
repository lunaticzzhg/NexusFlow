package com.nexusflow.ai.planner

import com.nexusflow.ai.provider.StructuredModelProvider
import com.nexusflow.ai.provider.StructuredModelRequest
import com.nexusflow.ai.provider.StructuredModelResult
import com.nexusflow.ai.provider.StructuredModelResultMetadata
import com.nexusflow.contracts.backendai.common.InvalidCapabilityResultException
import com.nexusflow.contracts.backendai.common.ModelContextBlockPayload
import com.nexusflow.contracts.backendai.common.ModelContextTrustPayload
import com.nexusflow.contracts.backendai.common.StructuredModelCapability
import com.nexusflow.contracts.backendai.common.StructuredModelUsage
import com.nexusflow.contracts.backendai.conversation.ReadOnlyToolCallProposal
import com.nexusflow.contracts.backendai.conversation.ReadOnlyToolDefinitionPayload
import com.nexusflow.contracts.backendai.planning.PlanningRequirement
import com.nexusflow.contracts.backendai.planning.PlanningRequirementStrength
import com.nexusflow.contracts.backendai.planning.PlanningResearchRequest
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StructuredPlanningResearchTest {
    @Test
    fun `movie planning selects offered discovery and showtimes tools`() =
        runBlocking {
            val provider = ScriptedPlanningResearchProvider(
                researchPayload(
                    toolCall("movie.discovery", buildJsonObject { put("dateWindow", "tonight") }),
                    toolCall("movie.showtimes", buildJsonObject { put("location", "Shanghai") }),
                ),
            )

            val result = StructuredPlanningResearch(provider).research(
                request(
                    goal = "Recommend a movie plan for tonight",
                    requirements = listOf(
                        PlanningRequirement("requirement-1", "ActivityDomain", "movie", PlanningRequirementStrength.Must),
                    ),
                    availableTools = movieTools(),
                ),
            )

            assertEquals(listOf("movie.discovery", "movie.showtimes"), result.toolCalls.map { it.toolKey })
            assertEquals(PLANNING_RESEARCH_PROMPT_VERSION, result.metadata.promptVersion)
            val modelRequest = provider.requests.single()
            assertEquals(StructuredModelCapability.PlanningResearch, modelRequest.metadata.capability)
            assertEquals(PLANNING_RESEARCH_SCHEMA_NAME, modelRequest.outputSchema.name)
        }

    @Test
    fun `hiking planning selects offered outdoor weather and route tools`() =
        runBlocking {
            val provider = ScriptedPlanningResearchProvider(
                researchPayload(
                    toolCall("outdoor.trails", buildJsonObject { put("location", "Shenzhen") }),
                    toolCall("weather.forecast", buildJsonObject { put("dateWindow", "weekend") }),
                    toolCall("route.estimate", buildJsonObject { put("maxMinutes", 90) }),
                ),
            )

            val result = StructuredPlanningResearch(provider).research(
                request(
                    goal = "Plan a weekend hike that is not too far",
                    requirements = listOf(
                        PlanningRequirement("requirement-1", "ActivityDomain", "hiking", PlanningRequirementStrength.Must),
                        PlanningRequirement("requirement-2", "CommutePreference", "prefer shorter travel", PlanningRequirementStrength.Prefer),
                    ),
                    availableTools = hikingTools(),
                ),
            )

            assertEquals(listOf("outdoor.trails", "weather.forecast", "route.estimate"), result.toolCalls.map { it.toolKey })
        }

    @Test
    fun `place lookup planning selects offered places search tool`() =
        runBlocking {
            val provider = ScriptedPlanningResearchProvider(
                researchPayload(
                    toolCall("places.search", buildJsonObject { put("text", "Dragon Back trailhead") }),
                ),
            )

            val result = StructuredPlanningResearch(provider).research(
                request(
                    goal = "Find where Dragon Back trailhead is before planning the route",
                    requirements = listOf(
                        PlanningRequirement("requirement-1", "Location", "Dragon Back trailhead", PlanningRequirementStrength.Must),
                    ),
                    availableTools = placeTools(),
                ),
            )

            assertEquals(listOf("places.search"), result.toolCalls.map { it.toolKey })
            assertTrue(provider.requests.single().systemPrompt.contains("places.search"))
        }

    @Test
    fun `music planning selects offered metadata and event tools`() =
        runBlocking {
            val provider = ScriptedPlanningResearchProvider(
                researchPayload(
                    toolCall(
                        "music.metadata",
                        buildJsonObject {
                            put("query", "Radiohead")
                            put("type", "release")
                        },
                    ),
                    toolCall(
                        "music.events",
                        buildJsonObject {
                            put("artist", "Radiohead")
                            put("city", "London")
                        },
                    ),
                ),
            )

            val result = StructuredPlanningResearch(provider).research(
                request(
                    goal = "Plan a night around Radiohead albums and upcoming concerts",
                    requirements = listOf(
                        PlanningRequirement("requirement-1", "ActivityDomain", "music", PlanningRequirementStrength.Must),
                    ),
                    availableTools = musicTools(),
                ),
            )

            assertEquals(listOf("music.metadata", "music.events"), result.toolCalls.map { it.toolKey })
            val prompt = provider.requests.single().systemPrompt
            assertTrue(prompt.contains("music.metadata"))
            assertTrue(prompt.contains("music.events"))
        }

    @Test
    fun `general ticketed sports planning selects sports events`() =
        runBlocking {
            val provider = ScriptedPlanningResearchProvider(
                researchPayload(
                    toolCall(
                        "sports.events",
                        buildJsonObject {
                            put("sport", "basketball")
                            put("city", "London")
                        },
                    ),
                ),
            )

            val result = StructuredPlanningResearch(provider).research(
                request(
                    goal = "Find a ticketed basketball game for this weekend",
                    requirements = listOf(
                        PlanningRequirement("requirement-1", "ActivityDomain", "sports", PlanningRequirementStrength.Must),
                    ),
                    availableTools = sportsTools(),
                ),
            )

            assertEquals(listOf("sports.events"), result.toolCalls.map { it.toolKey })
            val prompt = provider.requests.single().systemPrompt
            assertTrue(prompt.contains("ticketed or general sports event planning"))
        }

    @Test
    fun `football fixture planning keeps fixtures separate from sports events`() =
        runBlocking {
            val provider = ScriptedPlanningResearchProvider(
                researchPayload(
                    toolCall("sports.fixtures", buildJsonObject { put("competition", "premier_league") }),
                ),
            )

            val result = StructuredPlanningResearch(provider).research(
                request(
                    goal = "Plan around upcoming Premier League fixtures",
                    requirements = listOf(
                        PlanningRequirement("requirement-1", "ActivityDomain", "football", PlanningRequirementStrength.Must),
                    ),
                    availableTools = sportsTools(),
                ),
            )

            assertEquals(listOf("sports.fixtures"), result.toolCalls.map { it.toolKey })
            val prompt = provider.requests.single().systemPrompt
            assertTrue(prompt.contains("sports.fixtures rather than sports.events"))
        }

    @Test
    fun `unknown tool is rejected and retried`() =
        runBlocking {
            val provider = ScriptedPlanningResearchProvider(
                researchPayload(toolCall("invented.tool")),
                researchPayload(toolCall("movie.discovery")),
            )

            val result = StructuredPlanningResearch(provider).research(
                request(goal = "Plan a movie night", availableTools = movieTools()),
            )

            assertEquals("movie.discovery", result.toolCalls.single().toolKey)
            assertEquals(2, provider.requests.size)
            assertTrue(provider.requests.last().systemPrompt.contains("Repair only"))
        }

    @Test
    fun `duplicate tool is rejected`() =
        runBlocking {
            val provider = ScriptedPlanningResearchProvider(
                researchPayload(toolCall("movie.discovery"), toolCall("movie.discovery")),
                researchPayload(toolCall("movie.discovery"), toolCall("movie.discovery")),
            )

            val error = assertFailsWith<InvalidCapabilityResultException> {
                StructuredPlanningResearch(provider).research(
                    request(goal = "Plan a movie night", availableTools = movieTools()),
                )
            }

            assertEquals("duplicate_tool_key", error.failureStage)
            assertEquals(2, provider.requests.size)
        }

    @Test
    fun `blank tool is rejected`() =
        runBlocking {
            val provider = ScriptedPlanningResearchProvider(
                researchPayload(toolCall("   ")),
                researchPayload(toolCall("   ")),
            )

            val error = assertFailsWith<InvalidCapabilityResultException> {
                StructuredPlanningResearch(provider).research(
                    request(goal = "Plan a movie night", availableTools = movieTools()),
                )
            }

            assertEquals("invalid_tool_call", error.failureStage)
        }

    @Test
    fun `too many tool calls are rejected even when provider output parses`() =
        runBlocking {
            val provider = ScriptedPlanningResearchProvider(
                researchPayload(
                    toolCall("movie.discovery"),
                    toolCall("movie.showtimes"),
                    toolCall("weather.forecast"),
                    toolCall("outdoor.trails"),
                    toolCall("route.estimate"),
                    toolCall("web.search"),
                ),
                researchPayload(
                    toolCall("movie.discovery"),
                    toolCall("movie.showtimes"),
                    toolCall("weather.forecast"),
                    toolCall("outdoor.trails"),
                    toolCall("route.estimate"),
                    toolCall("web.search"),
                ),
            )

            val error = assertFailsWith<InvalidCapabilityResultException> {
                StructuredPlanningResearch(provider).research(
                    request(goal = "Plan a broad activity", availableTools = movieTools() + hikingTools() + webSearchTool()),
                )
            }

            assertEquals("too_many_tool_calls", error.failureStage)
        }

    @Test
    fun `plan shaped output is rejected`() =
        runBlocking {
            val provider = ScriptedPlanningResearchProvider(
                """{"toolCalls":[],"drafts":[]}""",
                """{"toolCalls":[],"drafts":[]}""",
            )

            val error = assertFailsWith<InvalidCapabilityResultException> {
                StructuredPlanningResearch(provider).research(
                    request(goal = "Plan a movie night", availableTools = movieTools()),
                )
            }

            assertEquals("json_decode", error.failureStage)
        }

    @Test
    fun `empty tool proposals are allowed when no external facts are required`() =
        runBlocking {
            val provider = ScriptedPlanningResearchProvider(researchPayload())

            val result = StructuredPlanningResearch(provider).research(
                request(
                    goal = "Organize already supplied indoor tasks into a plan",
                    requirements = listOf(
                        PlanningRequirement("requirement-1", "Topic", "use the provided checklist", PlanningRequirementStrength.Must),
                    ),
                    availableTools = movieTools(),
                ),
            )

            assertEquals(emptyList(), result.toolCalls)
        }

    @Test
    fun `request payload omits credentials raw prompt and full task message history`() =
        runBlocking {
            val provider = ScriptedPlanningResearchProvider(researchPayload(toolCall("movie.discovery")))

            StructuredPlanningResearch(provider).research(
                request(
                    goal = "Plan a movie night",
                    requirements = listOf(
                        PlanningRequirement("requirement-1", "ActivityDomain", "movie", PlanningRequirementStrength.Must),
                    ),
                    optionalContext = listOf(
                        ModelContextBlockPayload(
                            key = "profile.preference.location",
                            trust = ModelContextTrustPayload.UserProfile,
                            content = buildJsonObject { put("city", "Shanghai") },
                        ),
                    ),
                    availableTools = movieTools(),
                ),
            )

            val modelRequest = provider.requests.single()
            val renderedPayload = modelRequest.userPayload.toString()
            assertFalse(renderedPayload.contains("planning-research-test"))
            assertFalse(renderedPayload.contains("task-1"))
            assertFalse(renderedPayload.contains("sk-test"))
            assertFalse(renderedPayload.contains("Prompt version"))
            assertFalse(renderedPayload.contains("No plan generation"))
            assertFalse(renderedPayload.contains("fullTaskMessageHistory"))
            val requestPayload = modelRequest.userPayload.getValue("request").jsonObject
            assertEquals(setOf("referenceTime", "timeZoneId", "taskRevision"), requestPayload.keys)
            val tools = modelRequest.userPayload.getValue("coreContext")
                .jsonObject
                .getValue("availableReadTools")
                .jsonArray
            assertEquals(2, tools.size)
            assertEquals("movie.discovery", tools.first().jsonObject.getValue("toolKey").jsonPrimitive.content)
            assertEquals(1, modelRequest.metadata.diagnostics.includedContextBlockCount)
            assertTrue(modelRequest.metadata.diagnostics.contextDefinitionsSerializedChars > 0)
            assertTrue(modelRequest.metadata.diagnostics.fullUserPayloadSerializedChars > 0)
        }

    @Test
    fun `tool arguments remain JsonObject proposal`() =
        runBlocking {
            val provider = ScriptedPlanningResearchProvider(
                researchPayload(
                    toolCall(
                        "route.estimate",
                        buildJsonObject {
                            put("origin", "Futian")
                            put("destination", "trailhead")
                        },
                    ),
                ),
            )

            val result = StructuredPlanningResearch(provider).research(
                request(goal = "Plan a weekend hike", availableTools = hikingTools()),
            )

            val arguments = result.toolCalls.single().arguments
            assertEquals(JsonPrimitive("Futian"), arguments.getValue("origin"))
        }

    private fun request(
        goal: String,
        requirements: List<PlanningRequirement> = emptyList(),
        optionalContext: List<ModelContextBlockPayload> = emptyList(),
        availableTools: List<ReadOnlyToolDefinitionPayload> = emptyList(),
    ): PlanningResearchRequest =
        PlanningResearchRequest(
            planningResearchRequestId = "planning-research-test",
            taskId = "task-1",
            taskRevision = 7,
            goal = goal,
            requirements = requirements,
            optionalContext = optionalContext,
            availableReadTools = availableTools,
            referenceTime = Instant.parse("2026-09-07T04:00:00Z"),
            timeZoneId = "Asia/Shanghai",
        )

    private fun movieTools(): List<ReadOnlyToolDefinitionPayload> =
        listOf(
            ReadOnlyToolDefinitionPayload(
                toolKey = "movie.discovery",
                description = "Discover current movie candidates for a bounded location and date window.",
                argumentHint = "location, dateWindow",
            ),
            ReadOnlyToolDefinitionPayload(
                toolKey = "movie.showtimes",
                description = "Read showtimes for bounded movie candidates.",
                argumentHint = "movieTitle, location, date",
            ),
        )

    private fun hikingTools(): List<ReadOnlyToolDefinitionPayload> =
        listOf(
            ReadOnlyToolDefinitionPayload(
                toolKey = "outdoor.trails",
                description = "Find bounded hiking trail candidates.",
                argumentHint = "location, difficulty, distanceLimit",
            ),
            ReadOnlyToolDefinitionPayload(
                toolKey = "weather.forecast",
                description = "Read a weather forecast for a bounded location and date window.",
                argumentHint = "location, dateFrom, dateTo",
            ),
            ReadOnlyToolDefinitionPayload(
                toolKey = "route.estimate",
                description = "Estimate travel time for a bounded origin and destination.",
                argumentHint = "origin, destination, mode",
            ),
        )

    private fun placeTools(): List<ReadOnlyToolDefinitionPayload> =
        listOf(
            ReadOnlyToolDefinitionPayload(
                toolKey = "places.search",
                description = "Read typed place lookup evidence for a bounded location query.",
                argumentHint = "text, near, maxResults",
            ),
        )

    private fun musicTools(): List<ReadOnlyToolDefinitionPayload> =
        listOf(
            ReadOnlyToolDefinitionPayload(
                toolKey = "music.metadata",
                description = "Read MusicBrainz artist, release, album, and recording metadata.",
                argumentHint = "query, type, maxResults",
            ),
            ReadOnlyToolDefinitionPayload(
                toolKey = "music.events",
                description = "Find current concert, festival, and live music event candidates.",
                argumentHint = "artist, keyword, city, countryCode, dateFrom, dateTo",
            ),
        )

    private fun sportsTools(): List<ReadOnlyToolDefinitionPayload> =
        listOf(
            ReadOnlyToolDefinitionPayload(
                toolKey = "sports.events",
                description = "Find current ticketed general sports event candidates.",
                argumentHint = "sport, keyword, city, countryCode, dateFrom, dateTo",
            ),
            ReadOnlyToolDefinitionPayload(
                toolKey = "sports.fixtures",
                description = "Read football fixture schedules for leagues and competitions.",
                argumentHint = "competition, team, dateFrom, dateTo",
            ),
        )

    private fun webSearchTool(): List<ReadOnlyToolDefinitionPayload> =
        listOf(
            ReadOnlyToolDefinitionPayload(
                toolKey = "web.search",
                description = "Search current public web information.",
                argumentHint = "query, locale, dateWindow",
            ),
        )
}

private fun researchPayload(vararg toolCalls: ReadOnlyToolCallProposal): String =
    PlanningResearchTestJson.encodeToString(PlanningResearchPayload(toolCalls.toList()))

private fun toolCall(
    toolKey: String,
    arguments: JsonObject = buildJsonObject {},
): ReadOnlyToolCallProposal =
    ReadOnlyToolCallProposal(
        toolKey = toolKey,
        arguments = arguments,
    )

private class ScriptedPlanningResearchProvider(
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
                usage = StructuredModelUsage(
                    inputTokens = 6,
                    outputTokens = 9,
                    totalTokens = 15,
                ),
                requestDiagnostics = request.metadata.diagnostics,
            ),
        )
    }
}

private val PlanningResearchTestJson = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
    encodeDefaults = true
}
