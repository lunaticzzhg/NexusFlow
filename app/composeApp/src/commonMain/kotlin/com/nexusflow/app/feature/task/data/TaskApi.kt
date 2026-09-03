package com.nexusflow.app.feature.task.data

import com.nexusflow.app.core.network.ApiCallExecutor
import com.nexusflow.contracts.api.CreateTaskRequest
import com.nexusflow.contracts.api.KResponse
import com.nexusflow.contracts.api.SendTaskMessageRequest
import com.nexusflow.contracts.api.TaskDetailResponse
import com.nexusflow.contracts.api.TaskSummaryResponse
import com.nexusflow.contracts.api.UpdateRequirementRequest
import de.jensklingenberg.ktorfit.http.Body
import de.jensklingenberg.ktorfit.http.DELETE
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.Headers
import de.jensklingenberg.ktorfit.http.POST
import de.jensklingenberg.ktorfit.http.PUT
import de.jensklingenberg.ktorfit.http.Path

internal object TaskEndpoints {
    const val TASKS = "v1/tasks"
    const val TASK_DETAIL = "v1/tasks/{taskId}"
    const val TASK_MESSAGES = "v1/tasks/{taskId}/messages"
    const val TASK_REQUIREMENT = "v1/tasks/{taskId}/requirements/{requirementId}"
    const val TASK_PLAN_SELECT = "v1/tasks/{taskId}/plans/{planId}/select"
}

internal interface TaskApi {
    @POST(TaskEndpoints.TASKS)
    @Headers(JSON_CONTENT_TYPE_HEADER)
    suspend fun createTask(
        @Body request: CreateTaskRequest,
    ): KResponse<TaskDetailResponse>

    @GET(TaskEndpoints.TASKS)
    suspend fun listTasks(): KResponse<List<TaskSummaryResponse>>

    @GET(TaskEndpoints.TASK_DETAIL)
    suspend fun getTask(
        @Path("taskId") taskId: String,
    ): KResponse<TaskDetailResponse>

    @POST(TaskEndpoints.TASK_MESSAGES)
    @Headers(JSON_CONTENT_TYPE_HEADER)
    suspend fun sendMessage(
        @Path("taskId") taskId: String,
        @Body request: SendTaskMessageRequest,
    ): KResponse<TaskDetailResponse>

    @PUT(TaskEndpoints.TASK_REQUIREMENT)
    @Headers(JSON_CONTENT_TYPE_HEADER)
    suspend fun updateRequirement(
        @Path("taskId") taskId: String,
        @Path("requirementId") requirementId: String,
        @Body request: UpdateRequirementRequest,
    ): KResponse<TaskDetailResponse>

    @DELETE(TaskEndpoints.TASK_REQUIREMENT)
    suspend fun removeRequirement(
        @Path("taskId") taskId: String,
        @Path("requirementId") requirementId: String,
    ): KResponse<TaskDetailResponse>

    @POST(TaskEndpoints.TASK_PLAN_SELECT)
    suspend fun selectPlan(
        @Path("taskId") taskId: String,
        @Path("planId") planId: String,
    ): KResponse<TaskDetailResponse>
}

internal class TaskRemoteDataSource(
    private val api: TaskApi,
    private val apiCalls: ApiCallExecutor,
) {
    suspend fun createTask(request: CreateTaskRequest): Result<TaskDetailResponse> =
        apiCalls.execute(TaskEndpoints.TASKS) {
            api.createTask(request)
        }

    suspend fun listTasks(): Result<List<TaskSummaryResponse>> =
        apiCalls.execute(TaskEndpoints.TASKS) {
            api.listTasks()
        }

    suspend fun getTask(taskId: String): Result<TaskDetailResponse> =
        apiCalls.execute(TaskEndpoints.TASK_DETAIL) {
            api.getTask(taskId)
        }

    suspend fun sendMessage(
        taskId: String,
        request: SendTaskMessageRequest,
    ): Result<TaskDetailResponse> =
        apiCalls.execute(TaskEndpoints.TASK_MESSAGES) {
            api.sendMessage(taskId, request)
        }

    suspend fun updateRequirement(
        taskId: String,
        requirementId: String,
        request: UpdateRequirementRequest,
    ): Result<TaskDetailResponse> =
        apiCalls.execute(TaskEndpoints.TASK_REQUIREMENT) {
            api.updateRequirement(taskId, requirementId, request)
        }

    suspend fun removeRequirement(
        taskId: String,
        requirementId: String,
    ): Result<TaskDetailResponse> =
        apiCalls.execute(TaskEndpoints.TASK_REQUIREMENT) {
            api.removeRequirement(taskId, requirementId)
        }

    suspend fun selectPlan(
        taskId: String,
        planId: String,
    ): Result<TaskDetailResponse> =
        apiCalls.execute(TaskEndpoints.TASK_PLAN_SELECT) {
            api.selectPlan(taskId, planId)
        }
}

private const val JSON_CONTENT_TYPE_HEADER = "Content-Type: application/json"
