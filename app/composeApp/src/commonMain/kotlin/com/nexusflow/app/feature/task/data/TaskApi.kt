package com.nexusflow.app.feature.task.data

import com.nexusflow.app.core.network.ApiCallExecutor
import com.nexusflow.contracts.api.CreateTaskRequest
import com.nexusflow.contracts.api.GeneratePlansRequest
import com.nexusflow.contracts.api.GeneratePlansResponse
import com.nexusflow.contracts.api.KResponse
import com.nexusflow.contracts.api.SelectPlanRequest
import com.nexusflow.contracts.api.SendTaskMessageRequest
import com.nexusflow.contracts.api.TaskDetailResponse
import com.nexusflow.contracts.api.TaskSummaryResponse
import de.jensklingenberg.ktorfit.http.Body
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.Headers
import de.jensklingenberg.ktorfit.http.POST
import de.jensklingenberg.ktorfit.http.PUT
import de.jensklingenberg.ktorfit.http.Path

internal object TaskEndpoints {
    const val TASKS = "v1/tasks"
    const val TASK_DETAIL = "v1/tasks/{taskId}"
    const val TASK_MESSAGES = "v1/tasks/{taskId}/messages"
    const val TASK_PLANNING_RUNS = "v1/tasks/{taskId}/planning-runs"
    const val TASK_SELECTED_PLAN = "v1/tasks/{taskId}/selected-plan"
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

    @POST(TaskEndpoints.TASK_PLANNING_RUNS)
    @Headers(JSON_CONTENT_TYPE_HEADER)
    suspend fun generatePlans(
        @Path("taskId") taskId: String,
        @Body request: GeneratePlansRequest,
    ): KResponse<GeneratePlansResponse>

    @PUT(TaskEndpoints.TASK_SELECTED_PLAN)
    @Headers(JSON_CONTENT_TYPE_HEADER)
    suspend fun selectPlan(
        @Path("taskId") taskId: String,
        @Body request: SelectPlanRequest,
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

    suspend fun generatePlans(
        taskId: String,
        request: GeneratePlansRequest,
    ): Result<GeneratePlansResponse> =
        apiCalls.execute(TaskEndpoints.TASK_PLANNING_RUNS) {
            api.generatePlans(taskId, request)
        }

    suspend fun selectPlan(
        taskId: String,
        request: SelectPlanRequest,
    ): Result<TaskDetailResponse> =
        apiCalls.execute(TaskEndpoints.TASK_SELECTED_PLAN) {
            api.selectPlan(taskId, request)
        }
}

private const val JSON_CONTENT_TYPE_HEADER = "Content-Type: application/json"
