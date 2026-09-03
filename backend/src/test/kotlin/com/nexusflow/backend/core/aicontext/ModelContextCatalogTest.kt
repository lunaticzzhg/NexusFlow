package com.nexusflow.backend.core.aicontext

import com.nexusflow.ai.provider.StructuredModelCapability
import com.nexusflow.backend.core.identity.ActorContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelContextCatalogTest {
    @Test
    fun `duplicate registration fails`() {
        assertFailsWith<IllegalArgumentException> {
            ModelContextCatalog(
                listOf(
                    FakeResolver(definition(ProfileTimeWindow)),
                    FakeResolver(definition(ProfileTimeWindow)),
                ),
            )
        }
    }

    @Test
    fun `unknown key cannot resolve`() =
        runBlocking {
            val resolver = FakeResolver(definition(ProfileTimeWindow))
            val catalog = ModelContextCatalog(listOf(resolver))

            assertFailsWith<IllegalArgumentException> {
                catalog.resolve(resolveRequest(), listOf(ModelContextKey("profile.preference.unknown")))
            }

            assertEquals(emptyList(), resolver.calls)
        }

    @Test
    fun `requested keys route only to owning resolver`() =
        runBlocking {
            val firstResolver = FakeResolver(
                definition(ProfileTimeWindow),
                blocks = mapOf(ProfileTimeWindow to resolvedBlock(ProfileTimeWindow, "evening")),
            )
            val secondResolver = FakeResolver(
                definition(ProfileBudget),
                blocks = mapOf(ProfileBudget to resolvedBlock(ProfileBudget, "low-cost")),
            )
            val catalog = ModelContextCatalog(listOf(firstResolver, secondResolver))

            val blocks = catalog.resolve(resolveRequest(), listOf(ProfileBudget))

            assertContentEquals(listOf(ProfileBudget), blocks.map { it.key })
            assertEquals(emptyList(), firstResolver.calls)
            assertEquals(listOf(setOf(ProfileBudget)), secondResolver.calls)
        }

    @Test
    fun `adding fake second resolver requires no assembler or provider changes`() =
        runBlocking {
            val catalog = ModelContextCatalog(
                listOf(
                    FakeResolver(
                        definition(ProfileTimeWindow),
                        blocks = mapOf(ProfileTimeWindow to resolvedBlock(ProfileTimeWindow, "evening")),
                    ),
                    FakeResolver(
                        definition(TaskPlanContext, trust = ModelContextTrust.TaskDerived),
                        blocks = mapOf(TaskPlanContext to resolvedBlock(TaskPlanContext, "existing plan")),
                    ),
                ),
            )
            val assembler = ModelContextAssembler(catalog)

            val assembled = assembler.assemble(resolveRequest(), listOf(ProfileTimeWindow, TaskPlanContext))

            assertContentEquals(
                listOf(ProfileTimeWindow.value, TaskPlanContext.value),
                assembled.optionalContext.map { it.key },
            )
        }

    @Test
    fun `only capability allowed definitions are offered`() {
        val catalog = ModelContextCatalog(
            listOf(
                FakeResolver(
                    definition(
                        ProfileTimeWindow,
                        allowedCapabilities = setOf(StructuredModelCapability.UserMessageUnderstanding),
                    ),
                ),
                FakeResolver(
                    definition(
                        PlanningOnly,
                        allowedCapabilities = setOf(StructuredModelCapability.PlanComposition),
                    ),
                ),
            ),
        )

        val offered = catalog.definitions(
            ModelContextAllowance(capability = StructuredModelCapability.UserMessageUnderstanding),
        )

        assertContentEquals(listOf(ProfileTimeWindow), offered.map { it.key })
    }

    @Test
    fun `key allowance narrows offered definitions and selected keys`() {
        runBlocking {
            val catalog = ModelContextCatalog(
                listOf(
                    FakeResolver(definition(ProfileTimeWindow)),
                    FakeResolver(definition(ProfileBudget)),
                ),
            )
            val timeWindowOnly = ModelContextAllowance(
                capability = StructuredModelCapability.UserMessageUnderstanding,
                allowedKeys = setOf(ProfileTimeWindow),
            )

            assertContentEquals(
                listOf(ProfileTimeWindow),
                catalog.definitions(timeWindowOnly).map { it.key },
            )
            assertFailsWith<IllegalArgumentException> {
                catalog.resolve(resolveRequest(timeWindowOnly), listOf(ProfileBudget))
            }
        }
    }

    @Test
    fun `lifecycle is respected for definitions and resolve`() {
        runBlocking {
            val catalog = ModelContextCatalog(
                listOf(
                    FakeResolver(definition(ProfileTimeWindow, lifecycle = ModelContextLifecycle.Task)),
                    FakeResolver(definition(RequestLocation, lifecycle = ModelContextLifecycle.Request)),
                ),
            )
            val taskOnly = ModelContextAllowance(
                capability = StructuredModelCapability.UserMessageUnderstanding,
                lifecycles = setOf(ModelContextLifecycle.Task),
            )

            assertContentEquals(
                listOf(ProfileTimeWindow),
                catalog.definitions(taskOnly).map { it.key },
            )
            assertFailsWith<IllegalArgumentException> {
                catalog.resolve(resolveRequest(taskOnly), listOf(RequestLocation))
            }
        }
    }

    @Test
    fun `assembler omits empty unavailable and duplicate returned blocks deterministically`() =
        runBlocking {
            val catalog = ModelContextCatalog(
                listOf(
                    FakeResolver(
                        definition(ProfileTimeWindow),
                        blocks = mapOf(
                            ProfileTimeWindow to listOf(
                                resolvedBlock(ProfileTimeWindow, "evening"),
                                resolvedBlock(ProfileTimeWindow, "duplicate"),
                            ),
                            ProfileBudget to emptyResolvedBlock(ProfileBudget),
                        ),
                        extraDefinitions = listOf(definition(ProfileBudget), definition(ProfileCommute)),
                    ),
                ),
            )
            val assembler = ModelContextAssembler(catalog)

            val assembled = assembler.assemble(resolveRequest(), listOf(ProfileTimeWindow, ProfileBudget, ProfileCommute))

            assertContentEquals(listOf(ProfileTimeWindow.value), assembled.optionalContext.map { it.key })
            assertEquals(2, assembled.omittedBlockCount)
            assertEquals("evening", assembled.optionalContext.single().content["value"].toString().trim('"'))
        }

    @Test
    fun `assembler budget keeps higher priority then earlier requested blocks and reports safe omissions`() =
        runBlocking {
            val catalog = ModelContextCatalog(
                listOf(
                    FakeResolver(
                        definition(ProfileTimeWindow, priority = ModelContextPriority.High),
                        blocks = mapOf(
                            ProfileTimeWindow to resolvedBlock(ProfileTimeWindow, "secret-high", ModelContextPriority.High),
                            ProfileBudget to resolvedBlock(ProfileBudget, "secret-first-low", ModelContextPriority.Low),
                            ProfileCommute to resolvedBlock(ProfileCommute, "secret-second-low", ModelContextPriority.Low),
                        ),
                        extraDefinitions = listOf(
                            definition(ProfileBudget, priority = ModelContextPriority.Low),
                            definition(ProfileCommute, priority = ModelContextPriority.Low),
                        ),
                    ),
                ),
            )
            val assembler = ModelContextAssembler(
                catalog = catalog,
                budgetPolicy = ModelContextBudgetPolicy(maxOptionalContextSerializedChars = 12_000, maxOptionalContextBlocks = 2),
            )

            val assembled = assembler.assemble(
                resolveRequest(),
                listOf(ProfileBudget, ProfileCommute, ProfileTimeWindow),
            )

            assertContentEquals(
                listOf(ProfileTimeWindow.value, ProfileBudget.value),
                assembled.optionalContext.map { it.key },
            )
            assertEquals(1, assembled.omittedBlockCount)
            assertContentEquals(listOf(ProfileCommute.value), assembled.omittedContextKeys)
            assertEquals(3, assembled.diagnostics.selectedContextKeyCount)
            assertEquals(3, assembled.diagnostics.resolvedContextBlockCount)
            assertEquals(2, assembled.diagnostics.includedContextBlockCount)
            assertEquals(1, assembled.diagnostics.omittedContextBlockCount)
            assertContentEquals(listOf(ProfileCommute.value), assembled.diagnostics.omittedContextKeys)
            assertTrue(assembled.diagnostics.optionalContextSerializedChars > 0)
            assertFalse(assembled.diagnostics.toString().contains("secret"))
        }

    @Test
    fun `assembler serialized char budget omits later blocks without exposing content`() =
        runBlocking {
            val catalog = ModelContextCatalog(
                listOf(
                    FakeResolver(
                        definition(ProfileTimeWindow, priority = ModelContextPriority.High),
                        blocks = mapOf(
                            ProfileTimeWindow to resolvedBlock(ProfileTimeWindow, "ok", ModelContextPriority.High),
                            ProfileTopic to resolvedBlock(ProfileTopic, "secret-value-that-must-not-appear".repeat(6)),
                        ),
                        extraDefinitions = listOf(definition(ProfileTopic)),
                    ),
                ),
            )
            val assembler = ModelContextAssembler(
                catalog = catalog,
                budgetPolicy = ModelContextBudgetPolicy(maxOptionalContextSerializedChars = 130, maxOptionalContextBlocks = 16),
            )

            val assembled = assembler.assemble(resolveRequest(), listOf(ProfileTimeWindow, ProfileTopic))

            assertContentEquals(listOf(ProfileTimeWindow.value), assembled.optionalContext.map { it.key })
            assertEquals(1, assembled.omittedBlockCount)
            assertContentEquals(listOf(ProfileTopic.value), assembled.omittedContextKeys)
            assertTrue(assembled.diagnostics.optionalContextSerializedChars <= 130)
            assertFalse(assembled.diagnostics.toString().contains("secret-value"))
        }

    private class FakeResolver(
        firstDefinition: ModelContextDefinition,
        private val blocks: Map<ModelContextKey, Any> = emptyMap(),
        extraDefinitions: List<ModelContextDefinition> = emptyList(),
    ) : ModelContextResolver {
        override val definitions: List<ModelContextDefinition> = listOf(firstDefinition) + extraDefinitions
        val calls = mutableListOf<Set<ModelContextKey>>()

        override suspend fun resolve(
            request: ModelContextResolveRequest,
            keys: Set<ModelContextKey>,
        ): List<ResolvedModelContextBlock> {
            calls += keys
            return keys.flatMap { key ->
                when (val block = blocks[key]) {
                    is ResolvedModelContextBlock -> listOf(block)
                    is List<*> -> block.filterIsInstance<ResolvedModelContextBlock>()
                    else -> emptyList()
                }
            }
        }
    }

    private fun definition(
        key: ModelContextKey,
        lifecycle: ModelContextLifecycle = ModelContextLifecycle.Task,
        trust: ModelContextTrust = ModelContextTrust.UserProfile,
        priority: ModelContextPriority = when (trust) {
            ModelContextTrust.BackendAuthoritative -> ModelContextPriority.High
            else -> ModelContextPriority.Normal
        },
        allowedCapabilities: Set<StructuredModelCapability> = StructuredModelCapability.entries.toSet(),
    ): ModelContextDefinition =
        ModelContextDefinition(
            key = key,
            description = "Description for ${key.value}",
            selectionHint = "Select ${key.value} when useful.",
            lifecycle = lifecycle,
            priority = priority,
            maxContentChars = 512,
            schemaVersion = 1,
            allowedCapabilities = allowedCapabilities,
        )

    private fun resolvedBlock(
        key: ModelContextKey,
        value: String,
        priority: ModelContextPriority = ModelContextPriority.Normal,
        trust: ModelContextTrust = ModelContextTrust.UserProfile,
    ): ResolvedModelContextBlock =
        ResolvedModelContextBlock(
            key = key,
            trust = trust,
            content = buildJsonObject { put("value", value) },
            provenance = ModelContextProvenance(source = "fake"),
            priority = priority,
        )

    private fun emptyResolvedBlock(key: ModelContextKey): List<ResolvedModelContextBlock> =
        listOf(
            ResolvedModelContextBlock(
                key = key,
                trust = ModelContextTrust.UserProfile,
                content = buildJsonObject {},
                provenance = ModelContextProvenance(source = "fake"),
                priority = ModelContextPriority.Normal,
            ),
        )

    private fun resolveRequest(
        allowance: ModelContextAllowance = ModelContextAllowance(
            capability = StructuredModelCapability.UserMessageUnderstanding,
        ),
    ): ModelContextResolveRequest =
        ModelContextResolveRequest(
            actor = ActorContext(
                tenantId = "tenant-1",
                userId = "user-1",
                scopes = setOf("tasks:write"),
            ),
            allowance = allowance,
            taskId = "task-1",
            taskVersion = 1,
        )

    private companion object {
        val ProfileTimeWindow = ModelContextKey("profile.preference.time_window")
        val ProfileBudget = ModelContextKey("profile.preference.budget_limit")
        val ProfileCommute = ModelContextKey("profile.preference.commute_limit")
        val ProfileTopic = ModelContextKey("profile.preference.topic")
        val TaskPlanContext = ModelContextKey("task.active_plan")
        val PlanningOnly = ModelContextKey("backend.planning_hint")
        val RequestLocation = ModelContextKey("request.current_location")
    }
}
