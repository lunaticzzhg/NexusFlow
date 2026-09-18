@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.nexusflow.app.feature.task.presentation

import com.nexusflow.app.core.error.AppException
import com.nexusflow.app.core.observability.AppTraceManager
import com.nexusflow.app.feature.task.data.TaskFixtures
import com.nexusflow.app.feature.task.domain.ConversationDetail
import com.nexusflow.app.feature.task.domain.ConversationId
import com.nexusflow.app.feature.task.domain.CreateConversationCommand
import com.nexusflow.app.feature.task.domain.MessageRole
import com.nexusflow.app.feature.task.domain.PlanningState
import com.nexusflow.app.feature.task.domain.RemoveRequirementCommand
import com.nexusflow.app.feature.task.domain.ResponseRun
import com.nexusflow.app.feature.task.domain.ResponseRunFailureCategory
import com.nexusflow.app.feature.task.domain.ResponseRunId
import com.nexusflow.app.feature.task.domain.ResponseRunSnapshot
import com.nexusflow.app.feature.task.domain.ResponseRunStage
import com.nexusflow.app.feature.task.domain.ResponseRunStatus
import com.nexusflow.app.feature.task.domain.SelectPlanCommand
import com.nexusflow.app.feature.task.domain.SendConversationMessageCommand
import com.nexusflow.app.feature.task.domain.TaskDetail
import com.nexusflow.app.feature.task.domain.TaskId
import com.nexusflow.app.feature.task.domain.TaskMessage
import com.nexusflow.app.feature.task.domain.TaskRepository
import com.nexusflow.app.feature.task.domain.TaskSummary
import com.nexusflow.app.feature.task.domain.UpdateRequirementCommand
import com.nexusflow.app.feature.task.presentation.create.TaskCreateAction
import com.nexusflow.app.feature.task.presentation.create.TaskCreateEffect
import com.nexusflow.app.feature.task.presentation.create.TaskCreateViewModel
import com.nexusflow.app.feature.task.presentation.detail.ActiveResponseStatus
import com.nexusflow.app.feature.task.presentation.detail.TaskDetailAction
import com.nexusflow.app.feature.task.presentation.detail.TaskDetailContent
import com.nexusflow.app.feature.task.presentation.detail.TaskDetailIdentity
import com.nexusflow.app.feature.task.presentation.detail.TaskDetailOperation
import com.nexusflow.app.feature.task.presentation.detail.TaskDetailViewModel
import com.nexusflow.app.feature.task.presentation.home.TaskHomeAction
import com.nexusflow.app.feature.task.presentation.home.TaskHomeContent
import com.nexusflow.app.feature.task.presentation.home.TaskHomeEffect
import com.nexusflow.app.feature.task.presentation.home.TaskHomeViewModel
import com.nexusflow.observability.TraceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TaskViewModelTest {
    @Test
    fun `home loads things and opens linked conversation when supplied`() =
        viewModelTest {
            val repository = RecordingTaskRepository(loadResults = listOf(Result.success(TaskFixtures.success)))
            val viewModel = TaskHomeViewModel(repository)
            val effects = mutableListOf<TaskHomeEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.toList(effects) }
            runCurrent()

            viewModel.onAction(TaskHomeAction.Load)
            advanceUntilIdle()
            viewModel.onAction(TaskHomeAction.OpenSummary(TaskFixtures.success.single()))
            advanceUntilIdle()

            val content = assertIs<TaskHomeContent.Success>(viewModel.state.value.content)
            assertEquals("Create a calendar event and a pre-match reminder", content.summaries.single().intent)
            assertEquals(
                listOf(TaskFixtures.linkedConversationId),
                effects.map { (it as TaskHomeEffect.OpenConversation).conversationId },
            )
        }

    @Test
    fun `create submits one message based conversation request`() =
        viewModelTest {
            val repository = RecordingTaskRepository(createResults = listOf(Result.success(TaskFixtures.conversation)))
            val viewModel =
                TaskCreateViewModel(
                    repository = repository,
                    clientIdFactory = { "create-1" },
                    timeZoneIdProvider = { "Asia/Shanghai" },
                )
            val effects = mutableListOf<TaskCreateEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.toList(effects) }
            runCurrent()

            viewModel.onAction(TaskCreateAction.RequestChanged("Plan Saturday"))
            viewModel.onAction(TaskCreateAction.Submit)
            advanceUntilIdle()

            assertEquals(
                listOf(CreateConversationCommand("create-1", "Plan Saturday", "Asia/Shanghai")),
                repository.createConversationCommands,
            )
            assertEquals(listOf(TaskFixtures.conversation.id), effects.map { (it as TaskCreateEffect.OpenConversation).conversationId })
        }

    @Test
    fun `create retry reuses existing creation request id after unconfirmed failure`() =
        viewModelTest {
            val repository =
                RecordingTaskRepository(
                    createResults =
                        listOf(
                            Result.failure(AppException.Unavailable()),
                            Result.success(TaskFixtures.conversation),
                        ),
                )
            var nextId = 1
            val viewModel =
                TaskCreateViewModel(
                    repository = repository,
                    clientIdFactory = { "create-${nextId++}" },
                    timeZoneIdProvider = { "Asia/Shanghai" },
                )
            val effects = mutableListOf<TaskCreateEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.toList(effects) }
            runCurrent()

            viewModel.onAction(TaskCreateAction.RequestChanged("Plan Saturday"))
            viewModel.onAction(TaskCreateAction.Submit)
            advanceUntilIdle()
            viewModel.onAction(TaskCreateAction.RetrySubmit)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    CreateConversationCommand("create-1", "Plan Saturday", "Asia/Shanghai"),
                    CreateConversationCommand("create-1", "Plan Saturday", "Asia/Shanghai"),
                ),
                repository.createConversationCommands,
            )
            assertEquals(listOf(TaskFixtures.conversation.id), effects.map { (it as TaskCreateEffect.OpenConversation).conversationId })
        }

    @Test
    fun `conversation detail sends messages and selects plans with current task id`() =
        viewModelTest {
            val selected = TaskFixtures.detail.copy(selectedPlanId = TaskFixtures.currentPlans.single().id)
            val repository =
                RecordingTaskRepository(
                    conversationResults = listOf(Result.success(TaskFixtures.conversation)),
                    sendConversationResults =
                        listOf(
                            Result.success(TaskFixtures.conversation.copy(currentTask = TaskFixtures.detail.copy(revision = 2))),
                        ),
                    selectResults = listOf(Result.success(selected)),
                )
            val viewModel =
                TaskDetailViewModel(
                    detailIdentity = TaskDetailIdentity.Conversation(TaskFixtures.conversation.id),
                    repository = repository,
                    clientMessageIdFactory = { "message-1" },
                    timeZoneIdProvider = { "Asia/Shanghai" },
                )

            viewModel.onAction(TaskDetailAction.Load)
            advanceUntilIdle()
            viewModel.onAction(TaskDetailAction.DraftChanged("Keep it nearby"))
            viewModel.onAction(TaskDetailAction.SendMessage)
            advanceUntilIdle()
            viewModel.onAction(TaskDetailAction.SelectPlan(TaskFixtures.currentPlans.single().id))
            advanceUntilIdle()

            assertEquals(
                listOf(SendConversationMessageCommand(TaskFixtures.conversation.id, "message-1", "Keep it nearby", "Asia/Shanghai")),
                repository.sendConversationCommands,
            )
            assertEquals(
                listOf(SelectPlanCommand(TaskFixtures.detail.id, TaskFixtures.currentPlans.single().id)),
                repository.selectCommands,
            )
            val content = assertIs<TaskDetailContent.Success>(viewModel.state.value.content)
            assertEquals(TaskDetailOperation.Idle, content.operation)
            assertEquals(TaskFixtures.currentPlans.single().id, content.detail.currentTask?.selectedPlanId)
        }

    @Test
    fun `pure conversation detail renders without current task`() =
        viewModelTest {
            val pureConversation = TaskFixtures.conversation.copy(currentTask = null)
            val repository =
                RecordingTaskRepository(
                    conversationResults = listOf(Result.success(pureConversation)),
                )
            val viewModel =
                TaskDetailViewModel(
                    detailIdentity = TaskDetailIdentity.Conversation(TaskFixtures.conversation.id),
                    repository = repository,
                )

            viewModel.onAction(TaskDetailAction.Load)
            advanceUntilIdle()
            viewModel.onAction(TaskDetailAction.SelectPlan(TaskFixtures.currentPlans.single().id))
            advanceUntilIdle()

            val content = assertIs<TaskDetailContent.Success>(viewModel.state.value.content)
            assertEquals(null, content.detail.currentTask)
            assertEquals(emptyList(), repository.selectCommands)
            assertEquals(TaskDetailOperation.Idle, content.operation)
        }

    @Test
    fun `detail removes a requirement`() =
        viewModelTest {
            val requirementId = TaskFixtures.detail.requirements.single().id
            val repository =
                RecordingTaskRepository(
                    conversationResults = listOf(Result.success(TaskFixtures.conversation)),
                    removeResults = listOf(Result.success(TaskFixtures.detail.copy(requirements = emptyList()))),
                )
            val viewModel =
                TaskDetailViewModel(
                    detailIdentity = TaskDetailIdentity.Conversation(TaskFixtures.conversation.id),
                    repository = repository,
                )

            viewModel.onAction(TaskDetailAction.Load)
            advanceUntilIdle()
            viewModel.onAction(TaskDetailAction.RemoveRequirement(requirementId))
            advanceUntilIdle()

            assertEquals(listOf(RemoveRequirementCommand(TaskFixtures.detail.id, requirementId)), repository.removeCommands)
            val content = assertIs<TaskDetailContent.Success>(viewModel.state.value.content)
            assertEquals(emptyList(), content.detail.currentTask?.requirements)
        }

    @Test
    fun `detail send retry starts a new operation while reusing client message id`() =
        viewModelTest {
            val repository =
                RecordingTaskRepository(
                    conversationResults = listOf(Result.success(TaskFixtures.conversation)),
                    sendConversationResults =
                        listOf(
                            Result.failure(AppException.Unavailable()),
                            Result.success(TaskFixtures.conversation.copy(currentTask = TaskFixtures.detail.copy(revision = 2))),
                        ),
                )
            val traceManager = RecordingTraceManager()
            val viewModel =
                TaskDetailViewModel(
                    detailIdentity = TaskDetailIdentity.Conversation(TaskFixtures.conversation.id),
                    repository = repository,
                    traceManager = traceManager,
                    clientMessageIdFactory = { "message-1" },
                    timeZoneIdProvider = { "Asia/Shanghai" },
                )

            viewModel.onAction(TaskDetailAction.Load)
            advanceUntilIdle()
            viewModel.onAction(TaskDetailAction.DraftChanged("Keep it nearby"))
            viewModel.onAction(TaskDetailAction.SendMessage)
            advanceUntilIdle()
            viewModel.onAction(TaskDetailAction.RetryMessage)
            advanceUntilIdle()

            assertEquals(
                listOf(
                    SendConversationMessageCommand(TaskFixtures.conversation.id, "message-1", "Keep it nearby", "Asia/Shanghai"),
                    SendConversationMessageCommand(TaskFixtures.conversation.id, "message-1", "Keep it nearby", "Asia/Shanghai"),
                ),
                repository.sendConversationCommands,
            )
            assertEquals(
                listOf(
                    TraceOperation("task_message_send", "user"),
                    TraceOperation("task_message_send", "retry"),
                ),
                traceManager.operations,
            )
        }

    @Test
    fun `detail accepts no candidates planning state without marking message failed`() =
        viewModelTest {
            val noCandidates = TaskFixtures.detail.copy(plans = emptyList(), planningState = PlanningState.NoCandidates)
            val repository =
                RecordingTaskRepository(
                    conversationResults = listOf(Result.success(TaskFixtures.conversation)),
                    sendConversationResults = listOf(Result.success(TaskFixtures.conversation.copy(currentTask = noCandidates))),
                )
            val viewModel =
                TaskDetailViewModel(
                    detailIdentity = TaskDetailIdentity.Conversation(TaskFixtures.conversation.id),
                    repository = repository,
                    clientMessageIdFactory = { "message-1" },
                    timeZoneIdProvider = { "Asia/Shanghai" },
                )

            viewModel.onAction(TaskDetailAction.Load)
            advanceUntilIdle()
            viewModel.onAction(TaskDetailAction.DraftChanged("Keep it nearby"))
            viewModel.onAction(TaskDetailAction.SendMessage)
            advanceUntilIdle()

            val content = assertIs<TaskDetailContent.Success>(viewModel.state.value.content)
            assertEquals(PlanningState.NoCandidates, content.detail.currentTask?.planningState)
            assertNull(content.failedMessage)
            assertNull(content.operationFailure)
            assertEquals(TaskDetailOperation.Idle, content.operation)
        }

    @Test
    fun `detail send accepted with response run renders processing response`() =
        viewModelTest {
            val userMessage =
                TaskMessage(
                    id = "server-message-1",
                    role = MessageRole.User,
                    content = "Keep it nearby",
                    clientMessageId = "message-1",
                    turnIndex = 2,
                    understoodAt = null,
                )
            val run = responseRun(userMessageId = userMessage.id, turnIndex = 2, status = ResponseRunStatus.Processing)
            val acceptedDetail =
                TaskFixtures.conversation.copy(
                    messages = TaskFixtures.conversation.messages + userMessage,
                    responseRuns = listOf(run),
                )
            val repository =
                RecordingTaskRepository(
                    conversationResults = listOf(Result.success(TaskFixtures.conversation)),
                    sendConversationResults = listOf(Result.success(acceptedDetail)),
                    snapshotResults = listOf(Result.success(responseRunSnapshot(run, acceptedDetail))),
                )
            val viewModel =
                TaskDetailViewModel(
                    detailIdentity = TaskDetailIdentity.Conversation(TaskFixtures.conversation.id),
                    repository = repository,
                    clientMessageIdFactory = { "message-1" },
                    timeZoneIdProvider = { "Asia/Shanghai" },
                )

            viewModel.onAction(TaskDetailAction.Load)
            advanceUntilIdle()
            viewModel.onAction(TaskDetailAction.DraftChanged("Keep it nearby"))
            viewModel.onAction(TaskDetailAction.SendMessage)
            advanceUntilIdle()

            val content = assertIs<TaskDetailContent.Success>(viewModel.state.value.content)
            assertEquals(TaskDetailOperation.SendingMessage("message-1"), content.operation)
            assertEquals("message-1", repository.sendConversationCommands.single().clientMessageId)
            assertEquals(ActiveResponseStatus.Queued, content.activeResponse?.status)
            assertEquals(run.id, content.activeResponse?.runId)
            assertNull(content.failedMessage)
        }

    @Test
    fun `detail understoodAt null without response run no longer drives processing authority`() =
        viewModelTest {
            val pendingDetail =
                TaskFixtures.conversation.copy(
                    messages =
                        listOf(
                            TaskMessage(
                                id = "server-message-1",
                                role = MessageRole.User,
                                content = "Keep it nearby",
                                clientMessageId = "server-client-message-1",
                                understoodAt = null,
                            ),
                        ),
                    currentTask =
                        TaskFixtures.detail.copy(
                            messages = emptyList(),
                            plans = emptyList(),
                            planningState = PlanningState.Unavailable,
                        ),
                )
            val repository =
                RecordingTaskRepository(
                    conversationResults = listOf(Result.success(pendingDetail)),
                )
            val viewModel =
                TaskDetailViewModel(
                    detailIdentity = TaskDetailIdentity.Conversation(TaskFixtures.conversation.id),
                    repository = repository,
                    timeZoneIdProvider = { "Asia/Shanghai" },
                )

            viewModel.onAction(TaskDetailAction.Load)
            advanceUntilIdle()

            assertEquals(emptyList(), repository.sendConversationCommands)
            assertEquals(emptyList(), repository.retryResponseRunCommands)
            val content = assertIs<TaskDetailContent.Success>(viewModel.state.value.content)
            assertNull(content.activeResponse)
            assertNull(content.pendingMessage)
            assertNull(content.failedMessage)
            assertNull(content.operationFailure)
            assertEquals(TaskDetailOperation.Idle, content.operation)
        }

    @Test
    fun `detail cancel and retry response run use run commands`() =
        viewModelTest {
            val userMessage =
                TaskMessage(
                    id = "server-message-1",
                    role = MessageRole.User,
                    content = "Keep it nearby",
                    clientMessageId = "server-client-message-1",
                    turnIndex = 2,
                    understoodAt = null,
                )
            val processingRun = responseRun(userMessageId = userMessage.id, turnIndex = 2, status = ResponseRunStatus.Processing)
            val cancelledRun = processingRun.copy(status = ResponseRunStatus.Cancelled, retryable = false)
            val retryRun = cancelledRun.copy(status = ResponseRunStatus.Queued, retryable = false, attempt = 1)
            val detail =
                TaskFixtures.conversation.copy(
                    messages = TaskFixtures.conversation.messages + userMessage,
                    responseRuns = listOf(processingRun),
                )
            val repository =
                RecordingTaskRepository(
                    conversationResults = listOf(Result.success(detail)),
                    snapshotResults =
                        listOf(
                            Result.success(responseRunSnapshot(processingRun, detail)),
                            Result.success(responseRunSnapshot(retryRun, detail.withRun(retryRun))),
                        ),
                    cancelResults = listOf(Result.success(cancelledRun)),
                    retryResults = listOf(Result.success(retryRun)),
                )
            val viewModel =
                TaskDetailViewModel(
                    detailIdentity = TaskDetailIdentity.Conversation(TaskFixtures.conversation.id),
                    repository = repository,
                )

            viewModel.onAction(TaskDetailAction.Load)
            advanceUntilIdle()
            viewModel.onAction(TaskDetailAction.CancelResponseRun(processingRun.id))
            advanceUntilIdle()
            viewModel.onAction(TaskDetailAction.RetryResponseRun(processingRun.id))
            advanceUntilIdle()

            assertEquals(listOf(processingRun.id), repository.cancelResponseRunCommands)
            assertEquals(listOf(processingRun.id), repository.retryResponseRunCommands)
            val content = assertIs<TaskDetailContent.Success>(viewModel.state.value.content)
            assertEquals(retryRun.id, content.activeResponse?.runId)
            assertEquals(ActiveResponseStatus.Queued, content.activeResponse?.status)
        }
}

private fun viewModelTest(block: suspend kotlinx.coroutines.test.TestScope.() -> Unit) =
    runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            block()
        } finally {
            Dispatchers.resetMain()
        }
    }

private fun responseRun(
    id: String = "run-1",
    userMessageId: String = "message-1",
    turnIndex: Long = 1,
    status: ResponseRunStatus = ResponseRunStatus.Queued,
    attempt: Int = 0,
): ResponseRun =
    ResponseRun(
        id = ResponseRunId(id),
        userMessageId = userMessageId,
        turnIndex = turnIndex,
        status = status,
        stage = ResponseRunStage.Turn,
        attempt = attempt,
        retryable =
            status == ResponseRunStatus.FailedRetryable ||
                status == ResponseRunStatus.Failed ||
                status == ResponseRunStatus.TimedOut,
        assistantMessageId = null,
        failureCategory =
            if (status == ResponseRunStatus.FailedRetryable || status == ResponseRunStatus.Failed) {
                ResponseRunFailureCategory.ProviderTemporary
            } else {
                null
            },
        createdAt = Instant.parse("2026-08-28T10:17:00Z"),
        updatedAt = Instant.parse("2026-08-28T10:17:01Z"),
        completedAt = null,
    )

private fun responseRunSnapshot(
    run: ResponseRun,
    conversation: ConversationDetail,
    partialText: String = "",
    lastSeq: Long = 0,
): ResponseRunSnapshot =
    ResponseRunSnapshot(
        run = run,
        conversation = conversation.withRun(run),
        streamAttempt = run.attempt,
        lastSeq = lastSeq,
        partialText = partialText,
        activities = emptyList(),
        realtimeSnapshotAvailable = true,
    )

private fun ConversationDetail.withRun(run: ResponseRun): ConversationDetail =
    copy(
        responseRuns =
            if (responseRuns.any { it.id == run.id }) {
                responseRuns.map { existing -> if (existing.id == run.id) run else existing }
            } else {
                responseRuns + run
            },
    )

private class RecordingTaskRepository(
    private val loadResults: List<Result<List<TaskSummary>>> = emptyList(),
    private val createResults: List<Result<ConversationDetail>> = emptyList(),
    private val conversationResults: List<Result<ConversationDetail>> = emptyList(),
    private val detailResults: List<Result<TaskDetail>> = emptyList(),
    private val sendConversationResults: List<Result<ConversationDetail>> = emptyList(),
    private val snapshotResults: List<Result<ResponseRunSnapshot>> = emptyList(),
    private val cancelResults: List<Result<ResponseRun>> = emptyList(),
    private val retryResults: List<Result<ResponseRun>> = emptyList(),
    private val updateResults: List<Result<TaskDetail>> = emptyList(),
    private val removeResults: List<Result<TaskDetail>> = emptyList(),
    private val selectResults: List<Result<TaskDetail>> = emptyList(),
) : TaskRepository {
    val createConversationCommands = mutableListOf<CreateConversationCommand>()
    val sendConversationCommands = mutableListOf<SendConversationMessageCommand>()
    val snapshotCommands = mutableListOf<ResponseRunId>()
    val cancelResponseRunCommands = mutableListOf<ResponseRunId>()
    val retryResponseRunCommands = mutableListOf<ResponseRunId>()
    val updateCommands = mutableListOf<UpdateRequirementCommand>()
    val removeCommands = mutableListOf<RemoveRequirementCommand>()
    val selectCommands = mutableListOf<SelectPlanCommand>()
    private val loadQueue = ArrayDeque(loadResults)
    private val createQueue = ArrayDeque(createResults)
    private val conversationQueue = ArrayDeque(conversationResults)
    private val detailQueue = ArrayDeque(detailResults)
    private val sendConversationQueue = ArrayDeque(sendConversationResults)
    private val snapshotQueue = ArrayDeque(snapshotResults)
    private val cancelQueue = ArrayDeque(cancelResults)
    private val retryQueue = ArrayDeque(retryResults)
    private val updateQueue = ArrayDeque(updateResults)
    private val removeQueue = ArrayDeque(removeResults)
    private val selectQueue = ArrayDeque(selectResults)

    override suspend fun loadTaskSummaries(): Result<List<TaskSummary>> = loadQueue.removeFirst()

    override suspend fun createConversation(command: CreateConversationCommand): Result<ConversationDetail> {
        createConversationCommands += command
        return createQueue.removeFirst()
    }

    override suspend fun loadConversationDetail(conversationId: ConversationId): Result<ConversationDetail> =
        conversationQueue.removeFirst()

    override suspend fun loadTaskDetail(taskId: TaskId): Result<TaskDetail> = detailQueue.removeFirst()

    override suspend fun sendConversationMessage(command: SendConversationMessageCommand): Result<ConversationDetail> {
        sendConversationCommands += command
        return sendConversationQueue.removeFirst()
    }

    override suspend fun loadResponseRunSnapshot(
        conversationId: ConversationId,
        responseRunId: ResponseRunId,
    ): Result<ResponseRunSnapshot> {
        snapshotCommands += responseRunId
        return snapshotQueue.removeFirst()
    }

    override suspend fun cancelResponseRun(
        conversationId: ConversationId,
        responseRunId: ResponseRunId,
    ): Result<ResponseRun> {
        cancelResponseRunCommands += responseRunId
        return cancelQueue.removeFirst()
    }

    override suspend fun retryResponseRun(
        conversationId: ConversationId,
        responseRunId: ResponseRunId,
    ): Result<ResponseRun> {
        retryResponseRunCommands += responseRunId
        return retryQueue.removeFirst()
    }

    override suspend fun updateRequirement(command: UpdateRequirementCommand): Result<TaskDetail> {
        updateCommands += command
        return updateQueue.removeFirst()
    }

    override suspend fun removeRequirement(command: RemoveRequirementCommand): Result<TaskDetail> {
        removeCommands += command
        return removeQueue.removeFirst()
    }

    override suspend fun selectPlan(command: SelectPlanCommand): Result<TaskDetail> {
        selectCommands += command
        return selectQueue.removeFirst()
    }
}

private class RecordingTraceManager : AppTraceManager {
    val operations = mutableListOf<TraceOperation>()

    override fun currentTraceId(): TraceId? = null

    override suspend fun <T> withNewTrace(
        operation: String,
        trigger: String?,
        block: suspend () -> T,
    ): T {
        operations += TraceOperation(operation, trigger)
        return block()
    }

    override suspend fun <T> withTrace(
        traceId: TraceId,
        operation: String?,
        block: suspend () -> T,
    ): T = block()

    override suspend fun <T> withNewResultTrace(
        operation: String,
        trigger: String?,
        block: suspend () -> Result<T>,
    ): Result<T> {
        operations += TraceOperation(operation, trigger)
        return block()
    }
}

private data class TraceOperation(
    val operation: String,
    val trigger: String?,
)
