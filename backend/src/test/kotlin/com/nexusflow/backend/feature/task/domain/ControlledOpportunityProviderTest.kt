package com.nexusflow.backend.feature.task.domain

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ControlledOpportunityProviderTest {
    private val referenceTime: Instant = Instant.parse("2026-08-29T10:00:00Z")
    private val task = task()

    @Test
    fun `controlled sports dataset returns viable opportunity snapshots`() {
        val opportunities = ControlledOpportunityProvider().discover(
            OpportunityRequest(
                task = task,
                requirements = listOf(
                    requirement(
                        RequirementKind.ActivityDomain,
                        RequirementValue.ActivityDomain("Sports"),
                        RequirementStrength.Must,
                    ),
                    requirement(RequirementKind.Topic, RequirementValue.Topic("Liverpool"), RequirementStrength.Must),
                ),
                referenceTime = referenceTime,
            ),
        )

        assertTrue(opportunities.size >= 3)
        assertEquals(opportunities.size, opportunities.map { it.id }.distinct().size)
        assertTrue(opportunities.all { it.kind == OpportunityKind.Sports })
        assertTrue(opportunities.all { it.validUntil?.isAfter(referenceTime) == true })
        assertTrue(opportunities.all { it.sources.isNotEmpty() })
        assertTrue(opportunities.all { it.facts.availability == AvailabilityFact.Available })
    }

    @Test
    fun `controlled movies dataset honors out of home requirement`() {
        val opportunities = ControlledOpportunityProvider().discover(
            OpportunityRequest(
                task = task,
                requirements = listOf(
                    requirement(
                        RequirementKind.ActivityDomain,
                        RequirementValue.ActivityDomain("movie"),
                        RequirementStrength.Must,
                    ),
                    requirement(
                        RequirementKind.ActivityMode,
                        RequirementValue.ActivityMode(ActivityModeValue.OutOfHome),
                        RequirementStrength.Must,
                    ),
                ),
                referenceTime = referenceTime,
            ),
        )

        assertTrue(opportunities.size >= 3)
        assertTrue(opportunities.all { it.kind == OpportunityKind.Movies })
        assertTrue(opportunities.all { it.facts.activityMode == ActivityModeValue.OutOfHome })
    }

    private fun requirement(
        kind: RequirementKind,
        value: RequirementValue,
        strength: RequirementStrength,
    ): Requirement =
        Requirement(
            id = RequirementId(UUID.randomUUID()),
            taskId = task.id,
            kind = kind,
            value = value,
            strength = strength,
            source = RequirementSource.UserExplicit,
            evidence = RequirementEvidence.UserMessage(MessageId(UUID.randomUUID())),
            createdAt = referenceTime,
            updatedAt = referenceTime,
        )

    private fun task(): Task =
        Task(
            id = TaskId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
            owner = TaskOwner(
                tenantId = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000002")),
                userId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000003")),
            ),
            creationRequestId = "create-1",
            intent = "Find weekend options",
            revision = 1,
            selectedPlanId = null,
            createdAt = referenceTime,
            updatedAt = referenceTime,
        )
}
