package com.nexusflow.backend.feature.task.domain

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
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

        val plan = result.plans.single()
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

        assertEquals(emptyList(), result.plans)
        assertTrue(result.failures.any { it.code == PlanValidationFailureCode.UnknownOpportunityRef })
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

        assertEquals(emptyList(), result.plans)
        assertTrue(result.failures.any { it.code == PlanValidationFailureCode.MustActivityDomainRejected })
    }

    @Test
    fun `partial validation keeps valid plans and reports rejected drafts`() {
        val secondOpportunity = opportunity(
            id = opportunityId("00000000-0000-0000-0000-000000000102"),
            title = "Second Liverpool screening",
        )
        val result = PlanValidator().validate(
            PlanningContextSnapshot(
                task = task,
                requirements = listOf(
                    requirement(RequirementKind.Topic, RequirementValue.Topic("Liverpool"), RequirementStrength.Must),
                ),
                opportunities = listOf(opportunity, secondOpportunity),
                referenceTime = referenceTime,
            ),
            listOf(
                PlanDraft(planId("00000000-0000-0000-0000-000000000201"), PlanDirection.BestMatch, listOf(opportunity.id)),
                PlanDraft(planId("00000000-0000-0000-0000-000000000202"), PlanDirection.MoreRelaxed, listOf(secondOpportunity.id)),
                PlanDraft(
                    planId("00000000-0000-0000-0000-000000000203"),
                    PlanDirection.NewExperience,
                    listOf(opportunityId("00000000-0000-0000-0000-000000009999")),
                ),
            ),
        )

        assertEquals(2, result.plans.size)
        assertTrue(result.failures.any { it.code == PlanValidationFailureCode.UnknownOpportunityRef })
    }

    @Test
    fun `all must rejections produce feasibility failures without plans`() {
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

        assertEquals(emptyList(), result.plans)
        assertEquals(listOf(PlanValidationFailureCode.MustActivityDomainRejected), result.failures.map { it.code }.distinct())
    }

    @Test
    fun `duplicate draft does not fail an existing valid plan`() {
        val result = PlanValidator().validate(
            PlanningContextSnapshot(
                task = task,
                requirements = emptyList(),
                opportunities = listOf(opportunity),
                referenceTime = referenceTime,
            ),
            listOf(
                PlanDraft(planId("00000000-0000-0000-0000-000000000201"), PlanDirection.BestMatch, listOf(opportunity.id)),
                PlanDraft(planId("00000000-0000-0000-0000-000000000202"), PlanDirection.MoreRelaxed, listOf(opportunity.id)),
            ),
        )

        assertEquals(1, result.plans.size)
        assertTrue(result.failures.any { it.code == PlanValidationFailureCode.DuplicatePlan })
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

    private fun opportunity(
        id: OpportunityId = opportunityId("00000000-0000-0000-0000-000000000101"),
        title: String = "Liverpool supporters pub screening",
    ): Opportunity =
        Opportunity(
            id = id,
            provider = "Controlled Sports Feed",
            externalKey = "controlled://sports/liverpool/pub-screening",
            kind = OpportunityKind.Sports,
            title = title,
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
