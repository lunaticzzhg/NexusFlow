package com.nexusflow.app.feature.task.data

import com.nexusflow.app.core.network.ApiCallExecutor
import com.nexusflow.contracts.appbackend.common.KResponse
import com.nexusflow.contracts.appbackend.conversation.ConversationDetailResponse
import com.nexusflow.contracts.appbackend.conversation.CreateConversationRequest
import com.nexusflow.contracts.appbackend.conversation.CreateConversationResponse
import com.nexusflow.contracts.appbackend.conversation.ResponseRunResponse
import com.nexusflow.contracts.appbackend.conversation.ResponseRunSnapshotResponse
import com.nexusflow.contracts.appbackend.conversation.SendConversationMessageRequest
import com.nexusflow.contracts.appbackend.conversation.SendConversationMessageResponse
import com.nexusflow.contracts.appbackend.task.TaskDetailResponse
import com.nexusflow.contracts.appbackend.task.TaskSummaryResponse
import com.nexusflow.contracts.appbackend.task.UpdateRequirementRequest
import de.jensklingenberg.ktorfit.http.Body
import de.jensklingenberg.ktorfit.http.DELETE
import de.jensklingenberg.ktorfit.http.GET
import de.jensklingenberg.ktorfit.http.Headers
import de.jensklingenberg.ktorfit.http.POST
import de.jensklingenberg.ktorfit.http.PUT
import de.jensklingenberg.ktorfit.http.Path

internal object TaskEndpoints {
    const val CONVERSATIONS = "v1/conversations"
    const val CONVERSATION_DETAIL = "v1/conversations/{conversationId}"
    const val CONVERSATION_MESSAGES = "v1/conversations/{conversationId}/messages"
    const val RESPONSE_RUN = "v1/conversations/{conversationId}/response-runs/{responseRunId}"
    const val RESPONSE_RUN_CANCEL = "v1/conversations/{conversationId}/response-runs/{responseRunId}/cancel"
    const val RESPONSE_RUN_RETRY = "v1/conversations/{conversationId}/response-runs/{responseRunId}/retry"
    const val TASKS = "v1/tasks"
    const val TASK_DETAIL = "v1/tasks/{taskId}"
    const val TASK_REQUIREMENT = "v1/tasks/{taskId}/requirements/{requirementId}"
    const val TASK_PLAN_SELECT = "v1/tasks/{taskId}/plans/{planId}/select"
}

internal interface TaskApi {
    @POST(TaskEndpoints.CONVERSATIONS)
    @Headers(JSON_CONTENT_TYPE_HEADER)
    suspend fun createConversation(
        @Body request: CreateConversationRequest,
    ): KResponse<CreateConversationResponse>

    @GET(TaskEndpoints.TASKS)
    suspend fun listTasks(): KResponse<List<TaskSummaryResponse>>

    @GET(TaskEndpoints.TASK_DETAIL)
    suspend fun getTask(
        @Path("taskId") taskId: String,
    ): KResponse<TaskDetailResponse>

    @GET(TaskEndpoints.CONVERSATION_DETAIL)
    suspend fun getConversation(
        @Path("conversationId") conversationId: String,
    ): KResponse<ConversationDetailResponse>

    @POST(TaskEndpoints.CONVERSATION_MESSAGES)
    @Headers(JSON_CONTENT_TYPE_HEADER)
    suspend fun sendConversationMessage(
        @Path("conversationId") conversationId: String,
        @Body request: SendConversationMessageRequest,
    ): KResponse<SendConversationMessageResponse>

    @GET(TaskEndpoints.RESPONSE_RUN)
    suspend fun getResponseRunSnapshot(
        @Path("conversationId") conversationId: String,
        @Path("responseRunId") responseRunId: String,
    ): KResponse<ResponseRunSnapshotResponse>

    @POST(TaskEndpoints.RESPONSE_RUN_CANCEL)
    suspend fun cancelResponseRun(
        @Path("conversationId") conversationId: String,
        @Path("responseRunId") responseRunId: String,
    ): KResponse<ResponseRunResponse>

    @POST(TaskEndpoints.RESPONSE_RUN_RETRY)
    suspend fun retryResponseRun(
        @Path("conversationId") conversationId: String,
        @Path("responseRunId") responseRunId: String,
    ): KResponse<ResponseRunResponse>

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
    suspend fun createConversation(request: CreateConversationRequest): Result<CreateConversationResponse> =
        apiCalls.execute(TaskEndpoints.CONVERSATIONS) {
            api.createConversation(request)
        }

    suspend fun listTasks(): Result<List<TaskSummaryResponse>> =
        apiCalls.execute(TaskEndpoints.TASKS) {
            api.listTasks()
        }

    suspend fun getTask(taskId: String): Result<TaskDetailResponse> =
        apiCalls.execute(TaskEndpoints.TASK_DETAIL) {
            api.getTask(taskId)
        }

    suspend fun getConversation(conversationId: String): Result<ConversationDetailResponse> =
        apiCalls.execute(TaskEndpoints.CONVERSATION_DETAIL) {
            api.getConversation(conversationId)
        }

    suspend fun sendConversationMessage(
        conversationId: String,
        request: SendConversationMessageRequest,
    ): Result<SendConversationMessageResponse> =
        apiCalls.execute(TaskEndpoints.CONVERSATION_MESSAGES) {
            api.sendConversationMessage(conversationId, request)
        }

    suspend fun getResponseRunSnapshot(
        conversationId: String,
        responseRunId: String,
    ): Result<ResponseRunSnapshotResponse> =
        apiCalls.execute(TaskEndpoints.RESPONSE_RUN) {
            api.getResponseRunSnapshot(conversationId, responseRunId)
        }

    suspend fun cancelResponseRun(
        conversationId: String,
        responseRunId: String,
    ): Result<ResponseRunResponse> =
        apiCalls.execute(TaskEndpoints.RESPONSE_RUN_CANCEL) {
            api.cancelResponseRun(conversationId, responseRunId)
        }

    suspend fun retryResponseRun(
        conversationId: String,
        responseRunId: String,
    ): Result<ResponseRunResponse> =
        apiCalls.execute(TaskEndpoints.RESPONSE_RUN_RETRY) {
            api.retryResponseRun(conversationId, responseRunId)
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
