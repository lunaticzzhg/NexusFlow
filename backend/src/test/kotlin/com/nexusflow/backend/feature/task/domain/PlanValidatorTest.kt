package com.nexusflow.backend.feature.task.domain

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlanValidatorTest {
    private val referenceTime: Instant = Instant.parse("2026-08-29T10:00:00Z")
    private val task = task(revision = 7)
    private val opportunity = opportunity()

    @Test
    fun `materialized plan only uses validated opportunity snapshot data`() {
        val result = PlanValidator().validate(
            PlanningContextSnapshot(
                task = task,
                requirements = listOf(
                    requirement(RequirementKind.Topic, RequirementValue.Topic("Liverpool"), RequirementStrength.Must),
                ),
                opportunities = listOf(opportunity),
                referenceTime = referenceTime,
            ),
            listOf(PlanDraft(planId("00000000-0000-0000-0000-000000000201"), PlanDirection.BestMatch, listOf(opportunity.id))),
        )

        val accepted = assertIs<PlanValidationResult.Accepted>(result)
        val plan = accepted.plans.single()
        assertEquals(task.id, plan.taskId)
        assertEquals(7, plan.revision)
        assertEquals(listOf(opportunity.id), plan.opportunityRefs)
        assertEquals(opportunity.title, plan.timeline.single().title)
        assertEquals(opportunity.validUntil, plan.validUntil)
        assertEquals(opportunity.sources.single().label, plan.sourceRefs.single().label)
    }

    @Test
    fun `unknown opportunity id is rejected`() {
        val result = PlanValidator().validate(
            PlanningContextSnapshot(
                task = task,
                requirements = emptyList(),
                opportunities = listOf(opportunity),
                referenceTime = referenceTime,
            ),
            listOf(
                PlanDraft(
                    planId("00000000-0000-0000-0000-000000000201"),
                    PlanDirection.BestMatch,
                    listOf(opportunityId("00000000-0000-0000-0000-000000009999")),
                ),
            ),
        )

        val rejected = assertIs<PlanValidationResult.Rejected>(result)
        assertTrue(rejected.failures.any { it.code == PlanValidationFailureCode.UnknownOpportunityRef })
    }

    @Test
    fun `must requirement rejects mismatched opportunity facts`() {
        val result = PlanValidator().validate(
            PlanningContextSnapshot(
                task = task,
                requirements = listOf(
                    requirement(RequirementKind.ActivityDomain, RequirementValue.ActivityDomain("movie"), RequirementStrength.Must),
                ),
                opportunities = listOf(opportunity),
                referenceTime = referenceTime,
            ),
            listOf(PlanDraft(planId("00000000-0000-0000-0000-000000000201"), PlanDirection.BestMatch, listOf(opportunity.id))),
        )

        val rejected = assertIs<PlanValidationResult.Rejected>(result)
        assertTrue(rejected.failures.any { it.code == PlanValidationFailureCode.MustActivityDomainRejected })
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

    private fun opportunity(): Opportunity =
        Opportunity(
            id = opportunityId("00000000-0000-0000-0000-000000000101"),
            provider = "Controlled Sports Feed",
            externalKey = "controlled://sports/liverpool/pub-screening",
            kind = OpportunityKind.Sports,
            title = "Liverpool supporters pub screening",
            facts = OpportunityFacts(
                summary = "Reserved table for a Liverpool match screening.",
                startTime = referenceTime.plusSeconds(3_600),
                endTime = referenceTime.plusSeconds(7_200),
                location = LocationFact("Futian Sports Bar", "futian sports bar"),
                activityMode = ActivityModeValue.OutOfHome,
                price = MoneyFact(180, "CNY"),
                commute = DurationFact(18),
                availability = AvailabilityFact.Available,
                attributes = mapOf("topics" to FactValue.Text("sports,football,liverpool")),
            ),
            sources = listOf(SourceRef("Controlled Sports Feed", "controlled://sports", referenceTime.minusSeconds(600))),
            observedAt = referenceTime,
            validUntil = referenceTime.plusSeconds(86_400),
        )

    private fun task(revision: Long): Task =
        Task(
            id = TaskId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
            owner = TaskOwner(
                tenantId = TenantId(UUID.fromString("00000000-0000-0000-0000-000000000002")),
                userId = UserId(UUID.fromString("00000000-0000-0000-0000-000000000003")),
            ),
            creationRequestId = "create-1",
            intent = "Find weekend options",
            revision = revision,
            selectedPlanId = null,
            createdAt = referenceTime,
            updatedAt = referenceTime,
        )

    private fun planId(value: String): PlanId = PlanId(UUID.fromString(value))

    private fun opportunityId(value: String): OpportunityId = OpportunityId(UUID.fromString(value))
}
