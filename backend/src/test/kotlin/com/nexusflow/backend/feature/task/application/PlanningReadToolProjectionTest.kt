package com.nexusflow.backend.feature.task.application

import com.nexusflow.backend.feature.research.application.ReadToolCatalog
import com.nexusflow.backend.feature.research.application.ReadToolEvidence
import com.nexusflow.backend.feature.research.application.ReadToolEvidencePayload
import com.nexusflow.backend.feature.research.application.ReadToolFact
import com.nexusflow.backend.feature.research.application.ReadToolFactKind
import com.nexusflow.backend.feature.research.application.ReadToolFactValue
import com.nexusflow.backend.feature.research.application.ReadToolOutcome
import com.nexusflow.backend.feature.task.ControlledPlanningReadTool
import com.nexusflow.backend.feature.task.ScriptedUnderstanding
import com.nexusflow.backend.feature.task.activityDomainChange
import com.nexusflow.backend.feature.task.cleanMigrateAndSeed
import com.nexusflow.backend.feature.task.createConversationServices
import com.nexusflow.backend.feature.task.drainResponseRuns
import com.nexusflow.backend.feature.task.domain.AvailabilityFact
import com.nexusflow.backend.feature.task.locationChange
import com.nexusflow.backend.feature.task.postgresDataSource
import com.nexusflow.backend.feature.task.taskActor
import com.nexusflow.backend.feature.task.understandingOutcome
import com.nexusflow.backend.feature.research.application.readtool.MovieShowtimesKey
import com.nexusflow.backend.feature.research.application.readtool.WeatherForecastKey
import com.nexusflow.backend.feature.research.application.readtool.WebSearchKey
import com.nexusflow.contracts.backendai.understanding.ConstraintDeltaOperation
import com.nexusflow.contracts.backendai.understanding.ConstraintDeltaProposal
import com.nexusflow.contracts.backendai.understanding.RequirementKind as AiRequirementKind
import com.nexusflow.contracts.backendai.understanding.RequirementStrength as AiRequirementStrength
import com.nexusflow.contracts.backendai.understanding.RequirementValue as AiRequirementValue
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant as KotlinInstant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanningReadToolProjectionTest {
    private lateinit var dataSource: HikariDataSource

    @BeforeTest
    fun setUp() {
        dataSource = postgresDataSource("Planning read tool projection")
        cleanMigrateAndSeed(dataSource)
    }

    @AfterTest
    fun tearDown() {
        if (::dataSource.isInitialized) {
            dataSource.close()
        }
    }

    @Test
    fun `requirement facts are not backfilled into read tool opportunities`() =
        runBlocking {
            val tool = ControlledPlanningReadTool(
                key = MovieShowtimesKey,
                outcomeFactory = { _, _ ->
                    ReadToolOutcome.Success(
                        ReadToolEvidencePayload(
                            listOf(
                                evidence(
                                    sourceId = "showtime-missing-facts",
                                    sourceKey = MovieShowtimesKey.value,
                                    facts = listOf(ReadToolFact(ReadToolFactKind.TITLE, ReadToolFactValue.Text("Late screening"))),
                                ),
                            ),
                        ),
                    )
                },
            )
            val services = createConversationServices(
                dataSource = dataSource,
                readToolCatalog = ReadToolCatalog(listOf(tool)),
                understanding = ScriptedUnderstanding({
                    understandingOutcome(
                        changes = listOf(
                            activityDomainChange("movie", "movie"),
                            locationChange("Futian", "Futian", AiRequirementStrength.Must),
                            timeWindowChange(),
                        ),
                    )
                }),
            )

            val created = services.conversationService.createConversation(
                taskActor(),
                "planning-missing-facts",
                "Find a movie near Futian tonight",
                "Asia/Shanghai",
            )
            services.drainResponseRuns()
            val result = services.conversationService.getConversation(
                taskActor(),
                created.detail.conversation.id.value.toString(),
            )

            assertEquals(emptyList(), result.currentTask!!.plans)
            val opportunity = services.planComposer.contexts.single().opportunities.single()
            assertNull(opportunity.startsAt)
            assertNull(opportunity.endsAt)
            assertNull(opportunity.location)
            assertNull(opportunity.availability)
            assertTrue(result.currentTask.plans.isEmpty())
        }

    @Test
    fun `weather and web evidence do not become selectable opportunities`() =
        runBlocking {
            listOf(WeatherForecastKey, WebSearchKey).forEachIndexed { index, key ->
                cleanMigrateAndSeed(dataSource)
                val tool = ControlledPlanningReadTool(
                    key = key,
                    outcomeFactory = { _, _ ->
                        ReadToolOutcome.Success(
                            ReadToolEvidencePayload(
                                listOf(
                                    evidence(
                                        sourceId = "non-candidate-$index",
                                        sourceKey = key.value,
                                        facts = listOf(
                                            ReadToolFact(ReadToolFactKind.TITLE, ReadToolFactValue.Text("External fact")),
                                            ReadToolFact(ReadToolFactKind.SUMMARY, ReadToolFactValue.Text("Useful reference, not a candidate.")),
                                        ),
                                    ),
                                ),
                            ),
                        )
                    },
                )
                val services = createConversationServices(
                    dataSource = dataSource,
                    readToolCatalog = ReadToolCatalog(listOf(tool)),
                    understanding = ScriptedUnderstanding({
                        understandingOutcome(changes = listOf(activityDomainChange("outdoor", "outdoor")))
                    }),
                )

                val created = services.conversationService.createConversation(
                    taskActor(),
                    "planning-non-candidate-$index",
                    "Find something to do",
                    "Asia/Shanghai",
                )
                services.drainResponseRuns()
                val result = services.conversationService.getConversation(
                    taskActor(),
                    created.detail.conversation.id.value.toString(),
                )

                assertEquals(emptyList(), result.currentTask!!.plans)
                assertEquals(emptyList(), services.planComposer.contexts)
                assertTrue(result.currentTask.plans.isEmpty())
            }
        }

    @Test
    fun `showtime source facts generate a selectable opportunity`() =
        runBlocking {
            val start = java.time.Instant.parse("2026-08-29T13:00:00Z")
            val end = java.time.Instant.parse("2026-08-29T15:00:00Z")
            val tool = ControlledPlanningReadTool(
                key = MovieShowtimesKey,
                outcomeFactory = { _, _ ->
                    ReadToolOutcome.Success(
                        ReadToolEvidencePayload(
                            listOf(
                                evidence(
                                    sourceId = "showtime-complete",
                                    sourceKey = MovieShowtimesKey.value,
                                    facts = listOf(
                                        ReadToolFact(ReadToolFactKind.TITLE, ReadToolFactValue.Text("Late screening")),
                                        ReadToolFact(ReadToolFactKind.START_TIME, ReadToolFactValue.Timestamp(start)),
                                        ReadToolFact(ReadToolFactKind.END_TIME, ReadToolFactValue.Timestamp(end)),
                                        ReadToolFact(ReadToolFactKind.LOCATION_NAME, ReadToolFactValue.Text("Futian Cinema")),
                                        ReadToolFact(ReadToolFactKind.AVAILABILITY, ReadToolFactValue.Text(AvailabilityFact.Available.name)),
                                    ),
                                ),
                            ),
                        ),
                    )
                },
            )
            val services = createConversationServices(
                dataSource = dataSource,
                readToolCatalog = ReadToolCatalog(listOf(tool)),
                understanding = ScriptedUnderstanding({
                    understandingOutcome(changes = listOf(activityDomainChange("movie", "movie")))
                }),
            )

            val created = services.conversationService.createConversation(
                taskActor(),
                "planning-showtime-facts",
                "Find a movie",
                "Asia/Shanghai",
            )
            services.drainResponseRuns()
            val result = services.conversationService.getConversation(
                taskActor(),
                created.detail.conversation.id.value.toString(),
            )

            assertTrue(result.currentTask!!.plans.isNotEmpty())
            val opportunity = services.planComposer.contexts.single().opportunities.single()
            assertEquals("Late screening", opportunity.title)
            assertEquals("Futian Cinema", opportunity.location)
            assertEquals(start.toString(), opportunity.startsAt.toString())
            assertEquals(end.toString(), opportunity.endsAt.toString())
            assertEquals(AvailabilityFact.Available.name, opportunity.availability)
            assertTrue(result.currentTask.plans.isNotEmpty())
        }

    private fun evidence(
        sourceId: String,
        sourceKey: String,
        facts: List<ReadToolFact>,
    ): ReadToolEvidence =
        ReadToolEvidence(
            sourceId = sourceId,
            sourceUrl = "controlled://$sourceId",
            sourceKey = sourceKey,
            facts = facts,
        )

    private fun timeWindowChange(): ConstraintDeltaProposal =
        ConstraintDeltaProposal(
            operation = ConstraintDeltaOperation.Upsert,
            kind = AiRequirementKind.TimeWindow,
            value = AiRequirementValue.TimeWindow(
                startAt = KotlinInstant.parse("2026-08-29T12:00:00Z"),
                endAt = KotlinInstant.parse("2026-08-29T16:00:00Z"),
                timeZoneId = "Asia/Shanghai",
                originalText = "tonight",
            ),
            strength = AiRequirementStrength.Must,
            evidenceText = "tonight",
        )
}
