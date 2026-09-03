package com.nexusflow.backend.feature.profile.application

import com.nexusflow.ai.provider.StructuredModelCapability
import com.nexusflow.backend.core.aicontext.ModelContextAllowance
import com.nexusflow.backend.core.aicontext.ModelContextKey
import com.nexusflow.backend.core.aicontext.ModelContextResolveRequest
import com.nexusflow.backend.core.aicontext.ModelContextTrust
import com.nexusflow.backend.core.identity.ActorContext
import com.nexusflow.backend.feature.profile.domain.ExplicitPreference
import com.nexusflow.backend.feature.profile.domain.ExplicitPreferenceRepository
import com.nexusflow.backend.feature.task.domain.ActivityModeValue
import com.nexusflow.backend.feature.task.domain.ProfilePreferenceId
import com.nexusflow.backend.feature.task.domain.RequirementKind
import com.nexusflow.backend.feature.task.domain.RequirementValue
import com.nexusflow.backend.feature.task.domain.TaskOwner
import com.nexusflow.backend.feature.task.domain.TenantId
import com.nexusflow.backend.feature.task.domain.UserId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExplicitPreferenceModelContextResolverTest {
    @Test
    fun `only selected preference keys resolve`() =
        runBlocking {
            val repository = RecordingExplicitPreferenceRepository(
                mutableListOf(
                    preference(PreferenceOne, OwnerA, RequirementKind.BudgetLimit, RequirementValue.BudgetLimit(300, "CNY")),
                    preference(PreferenceTwo, OwnerA, RequirementKind.ActivityMode, RequirementValue.ActivityMode(ActivityModeValue.OutOfHome)),
                ),
            )
            val resolver = ExplicitPreferenceModelContextResolver(repository)

            val blocks = resolver.resolve(resolveRequest(OwnerA), setOf(BudgetLimitKey))

            assertContentEquals(listOf(BudgetLimitKey), blocks.map { it.key })
            assertEquals("CNY", blocks.single().content["currencyCode"]?.jsonPrimitive?.content)
            assertEquals(300, blocks.single().content["wholeUnits"]?.jsonPrimitive?.int)
            assertContentEquals(listOf(OwnerA), repository.listOwners)
        }

    @Test
    fun `user A cannot resolve user B preferences`() =
        runBlocking {
            val repository = RecordingExplicitPreferenceRepository(
                mutableListOf(
                    preference(PreferenceOne, OwnerB, RequirementKind.BudgetLimit, RequirementValue.BudgetLimit(900, "CNY")),
                ),
            )
            val resolver = ExplicitPreferenceModelContextResolver(repository)

            val blocks = resolver.resolve(resolveRequest(OwnerA), setOf(BudgetLimitKey))

            assertEquals(emptyList(), blocks)
            assertContentEquals(listOf(OwnerA), repository.listOwners)
        }

    @Test
    fun `confirmed same-semantic requirement shadows long-term preference`() =
        runBlocking {
            val repository = RecordingExplicitPreferenceRepository(
                mutableListOf(
                    preference(PreferenceOne, OwnerA, RequirementKind.CommuteLimit, RequirementValue.CommuteLimit(45)),
                ),
            )
            val resolver = ExplicitPreferenceModelContextResolver(repository)

            val blocks = resolver.resolve(
                resolveRequest(OwnerA, shadowedKeys = setOf(CommuteLimitKey)),
                setOf(CommuteLimitKey),
            )

            assertEquals(emptyList(), blocks)
            assertEquals(emptyList(), repository.listOwners)
        }

    @Test
    fun `output is deterministic compact and self describing`() =
        runBlocking {
            val repository = RecordingExplicitPreferenceRepository(
                mutableListOf(
                    preference(PreferenceTwo, OwnerA, RequirementKind.Topic, RequirementValue.Topic("football"), Later),
                    preference(PreferenceOne, OwnerA, RequirementKind.BudgetLimit, RequirementValue.BudgetLimit(300, null), Now),
                    preference(PreferenceThree, OwnerA, RequirementKind.BudgetLimit, RequirementValue.BudgetLimit(500, null), Later),
                ),
            )
            val resolver = ExplicitPreferenceModelContextResolver(repository)

            val first = resolver.resolve(resolveRequest(OwnerA), setOf(TopicKey, BudgetLimitKey))
            val second = resolver.resolve(resolveRequest(OwnerA), setOf(BudgetLimitKey, TopicKey))

            assertContentEquals(listOf(BudgetLimitKey, TopicKey), first.map { it.key })
            assertContentEquals(first.map { it.key }, second.map { it.key })
            assertEquals(500, first.first().content["wholeUnits"]?.jsonPrimitive?.int)
            assertCompact(first.first().content, allowedKeys = setOf("wholeUnits"))
            assertCompact(first.last().content, allowedKeys = setOf("text"))
            assertEquals(ModelContextTrust.UserProfile, first.first().trust)
            assertTrue(first.all { it.provenance?.source == "ExplicitPreferenceRepository" })
        }

    @Test
    fun `resolver uses no LLM provider dependency`() =
        runBlocking {
            val repository = RecordingExplicitPreferenceRepository(
                mutableListOf(
                    preference(PreferenceOne, OwnerA, RequirementKind.ActivityMode, RequirementValue.ActivityMode(ActivityModeValue.AtHome)),
                ),
            )
            val resolver = ExplicitPreferenceModelContextResolver(repository)

            val block = resolver.resolve(resolveRequest(OwnerA), setOf(ActivityModeKey)).single()

            assertEquals(JsonPrimitive("at_home"), block.content["mode"])
            assertEquals(1, repository.listOwners.size)
        }

    @Test
    fun `value remains owned by ExplicitPreferenceRepository and is re-resolved`() =
        runBlocking {
            val preferences = mutableListOf(
                preference(PreferenceOne, OwnerA, RequirementKind.BudgetLimit, RequirementValue.BudgetLimit(300, null), Now),
            )
            val repository = RecordingExplicitPreferenceRepository(preferences)
            val resolver = ExplicitPreferenceModelContextResolver(repository)

            val first = resolver.resolve(resolveRequest(OwnerA), setOf(BudgetLimitKey)).single()
            preferences[0] = preference(PreferenceOne, OwnerA, RequirementKind.BudgetLimit, RequirementValue.BudgetLimit(450, null), Later)
            val second = resolver.resolve(resolveRequest(OwnerA), setOf(BudgetLimitKey)).single()

            assertEquals(300, first.content["wholeUnits"]?.jsonPrimitive?.int)
            assertEquals(450, second.content["wholeUnits"]?.jsonPrimitive?.int)
            assertEquals(2, repository.listOwners.size)
        }

    @Test
    fun `selecting preference does not create task requirement`() =
        runBlocking {
            val repository = RecordingExplicitPreferenceRepository(
                mutableListOf(
                    preference(PreferenceOne, OwnerA, RequirementKind.BudgetLimit, RequirementValue.BudgetLimit(300, null)),
                ),
            )
            val resolver = ExplicitPreferenceModelContextResolver(repository)

            val blocks = resolver.resolve(resolveRequest(OwnerA), setOf(BudgetLimitKey))

            assertEquals(1, blocks.size)
            assertEquals(0, repository.taskRequirementMutationCount)
            assertEquals(listOf(RequirementKind.BudgetLimit), repository.preferences.map { it.kind })
        }

    private class RecordingExplicitPreferenceRepository(
        val preferences: MutableList<ExplicitPreference>,
    ) : ExplicitPreferenceRepository {
        val listOwners = mutableListOf<TaskOwner>()
        val taskRequirementMutationCount = 0

        override suspend fun listForOwner(owner: TaskOwner): List<ExplicitPreference> {
            listOwners += owner
            return preferences.filter { it.owner == owner }
        }

        override suspend fun findForOwner(
            owner: TaskOwner,
            preferenceId: ProfilePreferenceId,
        ): ExplicitPreference? =
            preferences.firstOrNull { it.owner == owner && it.id == preferenceId }
    }

    private fun resolveRequest(
        owner: TaskOwner,
        shadowedKeys: Set<ModelContextKey> = emptySet(),
    ): ModelContextResolveRequest =
        ModelContextResolveRequest(
            actor = ActorContext(
                tenantId = owner.tenantId.value.toString(),
                userId = owner.userId.value.toString(),
                scopes = setOf("orbit.tasks.write"),
            ),
            allowance = ModelContextAllowance(StructuredModelCapability.UserMessageUnderstanding),
            shadowedKeys = shadowedKeys,
        )

    private fun preference(
        id: ProfilePreferenceId,
        owner: TaskOwner,
        kind: RequirementKind,
        value: RequirementValue,
        updatedAt: Instant = Now,
    ): ExplicitPreference =
        ExplicitPreference(
            id = id,
            owner = owner,
            kind = kind,
            value = value,
            createdAt = Now,
            updatedAt = updatedAt,
        )

    private fun assertCompact(
        content: JsonObject,
        allowedKeys: Set<String>,
    ) {
        assertEquals(allowedKeys, content.keys)
        assertFalse("id" in content)
        assertFalse("kind" in content)
        assertFalse("createdAt" in content)
        assertFalse("updatedAt" in content)
    }

    private companion object {
        val BudgetLimitKey = ModelContextKey("profile.preference.budget_limit")
        val CommuteLimitKey = ModelContextKey("profile.preference.commute_limit")
        val ActivityModeKey = ModelContextKey("profile.preference.activity_mode")
        val TopicKey = ModelContextKey("profile.preference.topic")
        val TenantA = TenantId(UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001"))
        val UserA = UserId(UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001"))
        val UserB = UserId(UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002"))
        val OwnerA = TaskOwner(TenantA, UserA)
        val OwnerB = TaskOwner(TenantA, UserB)
        val PreferenceOne = ProfilePreferenceId(UUID.fromString("60000000-0000-0000-0000-000000000001"))
        val PreferenceTwo = ProfilePreferenceId(UUID.fromString("60000000-0000-0000-0000-000000000002"))
        val PreferenceThree = ProfilePreferenceId(UUID.fromString("60000000-0000-0000-0000-000000000003"))
        val Now: Instant = Instant.parse("2026-08-29T01:00:00Z")
        val Later: Instant = Instant.parse("2026-08-29T01:05:00Z")
    }
}
