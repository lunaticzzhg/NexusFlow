package com.nexusflow.backend.feature.task.api

import com.nexusflow.backend.core.http.configureHttpPlatform
import com.nexusflow.backend.core.identity.ActorContext
import com.nexusflow.backend.core.identity.ActorResolver
import com.nexusflow.backend.core.identity.UnauthenticatedException
import com.nexusflow.backend.feature.task.ScriptedUnderstanding
import com.nexusflow.backend.feature.task.TaskFlowIds
import com.nexusflow.backend.feature.task.activityDomainChange
import com.nexusflow.backend.feature.task.cleanMigrateAndSeed
import com.nexusflow.backend.feature.task.createTaskServices
import com.nexusflow.backend.feature.task.locationChange
import com.nexusflow.backend.feature.task.postgresDataSource
import com.nexusflow.backend.feature.task.understandingOutcome
import com.nexusflow.contracts.api.CreateTaskRequest
import com.nexusflow.contracts.api.KResponse
import com.nexusflow.contracts.api.RequirementKind
import com.nexusflow.contracts.api.RequirementStrength
import com.nexusflow.contracts.api.RequirementValueResponse
import com.nexusflow.contracts.api.SendTaskMessageRequest
import com.nexusflow.contracts.api.TaskDetailResponse
import com.nexusflow.contracts.api.UpdateRequirementRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
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
    fun `task routes expose message requirement and plan selection flow without removed generation endpoints`() {
        val dataSource = postgresDataSource("Task routes")
        try {
            cleanMigrateAndSeed(dataSource)
            val services = createTaskServices(
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
            testApplication {
                application {
                    configureHttpPlatform()
                    routing { taskRoutes(services.taskService, services.planningService, HeaderActorResolver) }
                }

                val created = postJson<CreateTaskRequest, TaskDetailResponse>(
                    "/v1/tasks",
                    CreateTaskRequest("route-create", "Find a movie near Futian", "Asia/Shanghai"),
                )
                assertEquals(2, created.data.task.revision)
                assertEquals(2, created.data.requirements.size)
                assertEquals(1, created.data.plans.size)
                assertFalse(created.rawBody.contains("planningRun"))
                assertFalse(created.rawBody.contains("constraint"))

                val selected = postEmpty<TaskDetailResponse>("/v1/tasks/${created.data.task.id}/plans/${created.data.plans.single().id}/select")
                assertEquals(created.data.plans.single().id, selected.data.task.selectedPlanId)

                val afterMessage = postJson<SendTaskMessageRequest, TaskDetailResponse>(
                    "/v1/tasks/${created.data.task.id}/messages",
                    SendTaskMessageRequest("route-message", "Actually make it sports", "Asia/Shanghai"),
                )
                assertEquals(3, afterMessage.data.task.revision)
                assertEquals(null, afterMessage.data.task.selectedPlanId)
                assertEquals(1, afterMessage.data.plans.size)

                val locationId = afterMessage.data.requirements.single { it.kind == RequirementKind.Location }.id
                val afterPut = putJson<UpdateRequirementRequest>(
                    "/v1/tasks/${created.data.task.id}/requirements/$locationId",
                    UpdateRequirementRequest(
                        kind = RequirementKind.Location,
                        value = RequirementValueResponse.Location("Nanshan"),
                        strength = RequirementStrength.Prefer,
                    ),
                )
                assertEquals(4, afterPut.data.task.revision)
                assertEquals(1, afterPut.data.plans.size)

                val selectedAgain = postEmpty<TaskDetailResponse>(
                    "/v1/tasks/${created.data.task.id}/plans/${afterPut.data.plans.single().id}/select",
                )
                assertNotNull(selectedAgain.data.task.selectedPlanId)

                val afterDelete = deleteJson<TaskDetailResponse>("/v1/tasks/${created.data.task.id}/requirements/$locationId")
                assertEquals(5, afterDelete.data.task.revision)
                assertEquals(null, afterDelete.data.task.selectedPlanId)
                assertEquals(1, afterDelete.data.requirements.size)
                assertEquals(1, afterDelete.data.plans.size)

                val removedRouteResponse = client.post("/v1/tasks/${created.data.task.id}/planning-runs") {
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
