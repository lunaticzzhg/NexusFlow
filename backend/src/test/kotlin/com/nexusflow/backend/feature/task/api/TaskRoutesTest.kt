package com.nexusflow.backend.feature.task.api

import com.nexusflow.backend.core.http.configureHttpPlatform
import com.nexusflow.backend.core.identity.ActorContext
import com.nexusflow.backend.core.identity.ActorResolver
import com.nexusflow.backend.core.identity.UnauthenticatedException
import com.nexusflow.backend.core.readtool.ReadToolCatalog
import com.nexusflow.backend.core.readtool.ReadToolEvidencePayload
import com.nexusflow.backend.core.readtool.ReadToolOutcome
import com.nexusflow.backend.feature.conversation.api.conversationRoutes
import com.nexusflow.backend.feature.task.ControlledPlanningReadTool
import com.nexusflow.backend.feature.task.ScriptedUnderstanding
import com.nexusflow.backend.feature.task.TaskFlowIds
import com.nexusflow.backend.feature.task.activityDomainChange
import com.nexusflow.backend.feature.task.application.readtool.MovieShowtimesKey
import com.nexusflow.backend.feature.task.cleanMigrateAndSeed
import com.nexusflow.backend.feature.task.createConversationServices
import com.nexusflow.backend.feature.task.createTaskServices
import com.nexusflow.backend.feature.task.drainResponseRuns
import com.nexusflow.backend.feature.task.locationChange
import com.nexusflow.backend.feature.task.postgresDataSource
import com.nexusflow.backend.feature.task.readToolEvidence
import com.nexusflow.backend.feature.task.understandingOutcome
import com.nexusflow.contracts.appbackend.common.KResponse
import com.nexusflow.contracts.appbackend.conversation.CreateConversationRequest
import com.nexusflow.contracts.appbackend.conversation.CreateConversationResponse
import com.nexusflow.contracts.appbackend.conversation.ConversationDetailResponse
import com.nexusflow.contracts.appbackend.conversation.ResponseRunStatusResponse
import com.nexusflow.contracts.appbackend.conversation.SendConversationMessageRequest
import com.nexusflow.contracts.appbackend.conversation.SendConversationMessageResponse
import com.nexusflow.contracts.appbackend.task.PlanningStatus
import com.nexusflow.contracts.appbackend.task.RequirementKind
import com.nexusflow.contracts.appbackend.task.RequirementStrength
import com.nexusflow.contracts.appbackend.task.RequirementValueResponse
import com.nexusflow.contracts.appbackend.task.TaskDetailResponse
import com.nexusflow.contracts.appbackend.task.UpdateRequirementRequest
import com.nexusflow.contracts.backendai.common.InvalidCapabilityResultException
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class TaskRoutesTest {
    @Test
    fun `task routes mutate conversation linked planning task while task message route is removed`() {
        val dataSource = postgresDataSource("Task routes")
        try {
            cleanMigrateAndSeed(dataSource)
            val conversationServices = createConversationServices(
                dataSource = dataSource,
                understanding = ScriptedUnderstanding(
                    {
                        understandingOutcome(
                            changes = listOf(
                                activityDomainChange("movie", "movie"),
                                locationChange("Futian", "Futian"),
                            ),
                        )
                    },
                    {
                        understandingOutcome(changes = listOf(activityDomainChange("sports", "sports")))
                    },
                ),
            )
            val taskServices = createTaskServices(
                dataSource = dataSource,
                readToolCatalog = ReadToolCatalog(listOf(ControlledPlanningReadTool())),
                understanding = ScriptedUnderstanding({ understandingOutcome(changes = emptyList()) }),
            )
            testApplication {
                application {
                    configureHttpPlatform()
                    routing {
                        conversationRoutes(conversationServices.conversationService, HeaderActorResolver)
                        taskRoutes(taskServices.taskService, taskServices.planningService, HeaderActorResolver)
                    }
                }

                val created = postJson<CreateConversationRequest, CreateConversationResponse>(
                    "/v1/conversations",
                    CreateConversationRequest("route-create", "Find a movie near Futian", "Asia/Shanghai"),
                )
                conversationServices.drainResponseRuns()
                val createdDetail = getJson<ConversationDetailResponse>("/v1/conversations/${created.data.conversation.id}")
                val currentTask = createdDetail.data.currentTask!!
                assertEquals(2, currentTask.task.revision)
                assertEquals(2, currentTask.requirements.size)
                assertEquals(1, currentTask.plans.size)
                assertFalse(created.rawBody.contains("planningRun"))
                assertFalse(created.rawBody.contains("constraint"))

                val selected = postEmpty<TaskDetailResponse>("/v1/tasks/${currentTask.task.id}/plans/${currentTask.plans.single().id}/select")
                assertEquals(currentTask.plans.single().id, selected.data.task.selectedPlanId)

                val afterMessage = postJson<SendConversationMessageRequest, SendConversationMessageResponse>(
                    "/v1/conversations/${created.data.conversation.id}/messages",
                    SendConversationMessageRequest("route-message", "Actually make it sports", "Asia/Shanghai"),
                )
                conversationServices.drainResponseRuns()
                val afterMessageDetail = getJson<ConversationDetailResponse>("/v1/conversations/${afterMessage.data.conversation.id}")
                val changedTask = afterMessageDetail.data.currentTask!!
                assertEquals(3, changedTask.task.revision)
                assertEquals(null, changedTask.task.selectedPlanId)
                assertEquals(0, changedTask.plans.size)

                val locationId = changedTask.requirements.single { it.kind == RequirementKind.Location }.id
                val afterPut = putJson<UpdateRequirementRequest>(
                    "/v1/tasks/${currentTask.task.id}/requirements/$locationId",
                    UpdateRequirementRequest(
                        kind = RequirementKind.Location,
                        value = RequirementValueResponse.Location("Nanshan"),
                        strength = RequirementStrength.Prefer,
                    ),
                )
                assertEquals(4, afterPut.data.task.revision)
                assertEquals(null, afterPut.data.task.selectedPlanId)

                val afterDelete = deleteJson<TaskDetailResponse>("/v1/tasks/${currentTask.task.id}/requirements/$locationId")
                assertEquals(5, afterDelete.data.task.revision)
                assertEquals(null, afterDelete.data.task.selectedPlanId)
                assertEquals(1, afterDelete.data.requirements.size)

                val removedRouteResponse = client.post("/v1/tasks/${currentTask.task.id}/planning-runs") {
                    actor()
                    contentType(ContentType.Application.Json)
                    setBody("""{"clientRequestId":"removed"}""")
                }
                assertEquals(HttpStatusCode.NotFound, removedRouteResponse.status)
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `planning empty and post commit understanding unavailable are returned through conversation responses`() {
        val dataSource = postgresDataSource("Task routes outcomes")
        try {
            cleanMigrateAndSeed(dataSource)
            val emptyPlanningTool = ControlledPlanningReadTool(outcomeFactory = { _, _ -> ReadToolOutcome.Empty })
            val services = createConversationServices(
                dataSource = dataSource,
                readToolCatalog = ReadToolCatalog(listOf(emptyPlanningTool)),
                understanding = ScriptedUnderstanding(
                    {
                        understandingOutcome(changes = listOf(activityDomainChange("movie", "movie")))
                    },
                    {
                        throw InvalidCapabilityResultException("temporary bad output")
                    },
                ),
            )
            testApplication {
                application {
                    configureHttpPlatform()
                    routing { conversationRoutes(services.conversationService, HeaderActorResolver) }
                }

                val created = postJson<CreateConversationRequest, CreateConversationResponse>(
                    "/v1/conversations",
                    CreateConversationRequest("route-no-candidates", "Find a movie", "Asia/Shanghai"),
                )
                services.drainResponseRuns()
                val createdDetail = getJson<ConversationDetailResponse>("/v1/conversations/${created.data.conversation.id}")
                assertEquals(PlanningStatus.Idle, createdDetail.data.currentTask!!.planning.status)
                assertEquals(emptyList(), createdDetail.data.currentTask!!.plans)

                val sent = postJson<SendConversationMessageRequest, SendConversationMessageResponse>(
                    "/v1/conversations/${created.data.conversation.id}/messages",
                    SendConversationMessageRequest("route-pending", "Near Futian", "Asia/Shanghai"),
                )
                services.drainResponseRuns()
                val sentDetail = getJson<ConversationDetailResponse>("/v1/conversations/${sent.data.conversation.id}")
                assertEquals(ResponseRunStatusResponse.Completed, sentDetail.data.conversation.responseRuns.last().status)
                val pending = sent.data.conversation.messages.single { it.clientMessageId == "route-pending" }
                assertEquals(null, pending.understoodAt)
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `no feasible plan is returned as successful conversation response`() {
        val dataSource = postgresDataSource("Task routes no feasible")
        try {
            cleanMigrateAndSeed(dataSource)
            val movieTool = ControlledPlanningReadTool(
                outcomeFactory = { _, _ ->
                    ReadToolOutcome.Success(
                        ReadToolEvidencePayload(
                            listOf(
                                readToolEvidence(
                                    "movie-1",
                                    "controlled://movie",
                                    MovieShowtimesKey.value,
                                    "Late movie screening",
                                    startAt = TaskFlowIds.Now.plusSeconds(3_600),
                                    endAt = TaskFlowIds.Now.plusSeconds(7_200),
                                    location = "Nanshan",
                                    availability = "Available",
                                ),
                            ),
                        ),
                    )
                },
            )
            val services = createConversationServices(
                dataSource = dataSource,
                readToolCatalog = ReadToolCatalog(listOf(movieTool)),
                understanding = ScriptedUnderstanding({
                    understandingOutcome(changes = listOf(activityDomainChange("sports", "sports")))
                }),
            )
            testApplication {
                application {
                    configureHttpPlatform()
                    routing { conversationRoutes(services.conversationService, HeaderActorResolver) }
                }

                val created = postJson<CreateConversationRequest, CreateConversationResponse>(
                    "/v1/conversations",
                    CreateConversationRequest("route-no-feasible", "Find sports", "Asia/Shanghai"),
                )
                services.drainResponseRuns()
                val createdDetail = getJson<ConversationDetailResponse>("/v1/conversations/${created.data.conversation.id}")

                assertEquals(PlanningStatus.Idle, createdDetail.data.currentTask!!.planning.status)
                assertEquals(emptyList(), createdDetail.data.currentTask!!.plans)
            }
        } finally {
            dataSource.close()
        }
    }

    private suspend inline fun <reified B, reified R> ApplicationTestBuilder.postJson(
        path: String,
        body: B,
    ): DecodedResponse<R> {
        val response = client.post(path) {
            actor()
            contentType(ContentType.Application.Json)
            setBody(JsonFormat.encodeToString(body))
        }
        return response.decode()
    }

    private suspend inline fun <reified T> ApplicationTestBuilder.postEmpty(path: String): DecodedResponse<T> {
        val response = client.post(path) {
            actor()
        }
        return response.decode()
    }

    private suspend inline fun <reified T> ApplicationTestBuilder.getJson(path: String): DecodedResponse<T> {
        val response = client.get(path) {
            actor()
        }
        return response.decode()
    }

    private suspend inline fun <reified T> ApplicationTestBuilder.putJson(
        path: String,
        body: T,
    ): DecodedResponse<TaskDetailResponse> {
        val response = client.put(path) {
            actor()
            contentType(ContentType.Application.Json)
            setBody(JsonFormat.encodeToString(body))
        }
        return response.decode()
    }

    private suspend inline fun <reified T> ApplicationTestBuilder.deleteJson(path: String): DecodedResponse<T> {
        val response = client.delete(path) {
            actor()
        }
        return response.decode()
    }

    private suspend inline fun <reified T> HttpResponse.decode(): DecodedResponse<T> {
        assertEquals(HttpStatusCode.OK, status, bodyAsText())
        val body = bodyAsText()
        return DecodedResponse(JsonFormat.decodeFromString<KResponse<T>>(body).data!!, body)
    }

    private fun HttpRequestBuilder.actor(
        scopes: String = "orbit.tasks.read orbit.tasks.write",
    ) {
        header("X-Orbit-Tenant", TaskFlowIds.TenantOne.toString())
        header("X-Orbit-User", TaskFlowIds.UserOne.toString())
        header("X-Orbit-Scopes", scopes)
    }

    private data class DecodedResponse<T>(
        val data: T,
        val rawBody: String,
    )

    private object HeaderActorResolver : ActorResolver {
        override fun resolve(call: ApplicationCall): ActorContext =
            ActorContext(
                tenantId = call.request.headers["X-Orbit-Tenant"] ?: throw UnauthenticatedException(),
                userId = call.request.headers["X-Orbit-User"] ?: throw UnauthenticatedException(),
                scopes = call.request.headers["X-Orbit-Scopes"]
                    ?.split(" ")
                    ?.filter(String::isNotBlank)
                    ?.toSet()
                    ?: emptySet(),
            )
    }

    private companion object {
        val JsonFormat = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = false
        }
    }
}
