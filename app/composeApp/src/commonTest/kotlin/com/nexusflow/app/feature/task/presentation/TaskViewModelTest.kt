@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.nexusflow.app.feature.task.presentation

import com.nexusflow.app.feature.task.data.TaskFixtures
import com.nexusflow.app.feature.task.domain.CreateTaskCommand
import com.nexusflow.app.feature.task.domain.GeneratePlansCommand
import com.nexusflow.app.feature.task.domain.PlanId
import com.nexusflow.app.feature.task.domain.SelectPlanCommand
import com.nexusflow.app.feature.task.domain.SendTaskMessageCommand
import com.nexusflow.app.feature.task.domain.TaskDetail
import com.nexusflow.app.feature.task.domain.TaskId
import com.nexusflow.app.feature.task.domain.TaskPlan
import com.nexusflow.app.feature.task.domain.TaskRepository
import com.nexusflow.app.feature.task.domain.TaskState
import com.nexusflow.app.feature.task.domain.TaskSummary
import com.nexusflow.app.feature.task.presentation.create.TaskCreateAction
import com.nexusflow.app.feature.task.presentation.create.TaskCreateEffect
import com.nexusflow.app.feature.task.presentation.create.TaskCreateViewModel
import com.nexusflow.app.feature.task.presentation.create.TaskSubmission
import com.nexusflow.app.feature.task.presentation.detail.TaskDetailAction
import com.nexusflow.app.feature.task.presentation.detail.TaskDetailContent
import com.nexusflow.app.feature.task.presentation.detail.TaskDetailViewModel
import com.nexusflow.app.feature.task.presentation.home.TaskHomeAction
import com.nexusflow.app.feature.task.presentation.home.TaskHomeContent
import com.nexusflow.app.feature.task.presentation.home.TaskHomeEffect
import com.nexusflow.app.feature.task.presentation.home.TaskHomeViewModel
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TaskViewModelTest {
    @Test
    fun `home loads success empty failure retry and opens task by ID only`() =
        viewModelTest {
            val repository =
                RecordingTaskRepository(
                    loadResults =
                        listOf(
                            Result.success(TaskFixtures.success),
                            Result.success(emptyList()),
                            Result.failure(IllegalStateException()),
                            Result.success(TaskFixtures.success),
                        ),
                )
            val viewModel = TaskHomeViewModel(repository)
            val effects = mutableListOf<TaskHomeEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.toList(effects) }
            runCurrent()

            viewModel.onAction(TaskHomeAction.Load)
            advanceUntilIdle()
            assertIs<TaskHomeContent.Success>(viewModel.state.value.content)

            viewModel.onAction(TaskHomeAction.OpenTask(TaskId("task-1")))
            advanceUntilIdle()
            assertEquals(listOf("task-1"), effects.map { (it as TaskHomeEffect.OpenTask).taskId.value })

            val emptyViewModel = TaskHomeViewModel(repository)
            emptyViewModel.onAction(TaskHomeAction.Load)
            advanceUntilIdle()
            assertIs<TaskHomeContent.Empty>(emptyViewModel.state.value.content)

            val retryViewModel = TaskHomeViewModel(repository)
            retryViewModel.onAction(TaskHomeAction.Load)
            advanceUntilIdle()
            assertIs<TaskHomeContent.Failure>(retryViewModel.state.value.content)
            retryViewModel.onAction(TaskHomeAction.Retry)
            advanceUntilIdle()
            assertIs<TaskHomeContent.Success>(retryViewModel.state.value.content)
        }

    @Test
    fun `create rejects empty input succeeds with final task ID and retries using the same identity pair`() =
        viewModelTest {
            val finalDetail = TaskFixtures.detail.copy(id = TaskId("created-task"))
            val repository =
                RecordingTaskRepository(
                    createResults =
                        listOf(
                            Result.failure(IllegalStateException()),
                            Result.success(finalDetail),
                        ),
                )
            val ids = ArrayDeque(listOf("create-1", "message-1"))
            val viewModel =
                TaskCreateViewModel(
                    repository = repository,
                    clientIdFactory = { ids.removeFirst() },
                    timeZoneIdProvider = { "Asia/Shanghai" },
                )
            val effects = mutableListOf<TaskCreateEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.toList(effects) }
            runCurrent()

            viewModel.onAction(TaskCreateAction.Submit)
            assertEquals(emptyList<CreateTaskCommand>(), repository.createCommands)

            viewModel.onAction(TaskCreateAction.RequestChanged("Weekend plan"))
            viewModel.onAction(TaskCreateAction.Submit)
            assertIs<TaskSubmission.Submitting>(viewModel.state.value.submission)
            advanceUntilIdle()
            assertIs<TaskSubmission.Failed>(viewModel.state.value.submission)

            viewModel.onAction(TaskCreateAction.RetrySubmit)
            advanceUntilIdle()

            assertEquals(listOf("create-1", "create-1"), repository.createCommands.map { it.creationRequestId })
            assertEquals(listOf("message-1", "message-1"), repository.createCommands.map { it.initialMessageId })
            assertEquals(listOf("Asia/Shanghai", "Asia/Shanghai"), repository.createCommands.map { it.timeZoneId })
            assertTrue(repository.createCommands.all { it.creationRequestId != it.initialMessageId })
            assertEquals(listOf("created-task"), effects.map { (it as TaskCreateEffect.OpenTask).taskId.value })
        }

    @Test
    fun `create editing after failure starts a new operation identity pair`() =
        viewModelTest {
            val repository =
                RecordingTaskRepository(
                    createResults =
                        listOf(
                            Result.failure(IllegalStateException()),
                            Result.success(TaskFixtures.detail.copy(id = TaskId("created-task"))),
                        ),
                )
            val ids =
                ArrayDeque(
                    listOf(
                        "create-1",
                        "message-1",
                        "create-2",
                        "message-2",
                    ),
                )
            val viewModel =
                TaskCreateViewModel(
                    repository = repository,
                    clientIdFactory = { ids.removeFirst() },
                    timeZoneIdProvider = { "Asia/Shanghai" },
                )

            viewModel.onAction(TaskCreateAction.RequestChanged("Weekend plan"))
            viewModel.onAction(TaskCreateAction.Submit)
            advanceUntilIdle()
            viewModel.onAction(TaskCreateAction.RequestChanged("Updated weekend plan"))
            viewModel.onAction(TaskCreateAction.Submit)
            advanceUntilIdle()

            assertEquals(listOf("create-1", "create-2"), repository.createCommands.map { it.creationRequestId })
            assertEquals(listOf("message-1", "message-2"), repository.createCommands.map { it.initialMessageId })
        }

    @Test
    fun `detail loads retries generates fixture plans and selects a plan`() =
        viewModelTest {
            val plan =
                TaskPlan(
                    id = PlanId("plan-1"),
                    title = "Fixture",
                    summary = "Fixture summary",
                    timeline = emptyList(),
                    estimatedCost = null,
                    commuteMinutes = null,
                    tradeoffs = emptyList(),
                    reasons = emptyList(),
                )
            val detail = TaskFixtures.detail.copy(state = TaskState.Planning)
            val selected = detail.copy(plans = listOf(plan), selectedPlanId = plan.id, state = TaskState.WaitingForApproval)
            val repository =
                RecordingTaskRepository(
                    detailResults =
                        listOf(
                            Result.failure(IllegalStateException()),
                            Result.success(detail),
                            Result.success(selected),
                        ),
                    generateResults = listOf(Result.success(listOf(plan))),
                    selectResults = listOf(Result.success(selected)),
                )
            val viewModel =
                TaskDetailViewModel(
                    taskId = TaskId("task-liverpool-night"),
                    repository = repository,
                    clientRequestIdFactory = { "planning-1" },
                )

            viewModel.onAction(TaskDetailAction.Load)
            advanceUntilIdle()
            assertIs<TaskDetailContent.Failure>(viewModel.state.value.content)

            viewModel.onAction(TaskDetailAction.Retry)
            advanceUntilIdle()
            assertIs<TaskDetailContent.Success>(viewModel.state.value.content)

            viewModel.onAction(TaskDetailAction.GenerateFixturePlan)
            advanceUntilIdle()
            assertEquals(listOf(GeneratePlansCommand(TaskId("task-liverpool-night"), "planning-1")), repository.generateCommands)

            viewModel.onAction(TaskDetailAction.SelectPlan(plan.id))
            advanceUntilIdle()
            assertEquals(listOf(SelectPlanCommand(TaskId("task-liverpool-night"), plan.id)), repository.selectCommands)
            assertEquals(selected, (viewModel.state.value.content as TaskDetailContent.Success).detail)
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

private class RecordingTaskRepository(
    private val loadResults: List<Result<List<TaskSummary>>> = emptyList(),
    private val createResults: List<Result<TaskDetail>> = emptyList(),
    private val detailResults: List<Result<TaskDetail>> = emptyList(),
    private val generateResults: List<Result<List<TaskPlan>>> = emptyList(),
    private val selectResults: List<Result<TaskDetail>> = emptyList(),
) : TaskRepository {
    val createCommands = mutableListOf<CreateTaskCommand>()
    val generateCommands = mutableListOf<GeneratePlansCommand>()
    val selectCommands = mutableListOf<SelectPlanCommand>()
    private var loadCalls = 0
    private var createCalls = 0
    private var detailCalls = 0
    private var generateCalls = 0
    private var selectCalls = 0

    override suspend fun loadTaskSummaries(): Result<List<TaskSummary>> = loadResults[loadCalls++]

    override suspend fun createTask(command: CreateTaskCommand): Result<TaskDetail> {
        createCommands += command
        return createResults[createCalls++]
    }

    override suspend fun loadTaskDetail(taskId: TaskId): Result<TaskDetail> = detailResults[detailCalls++]

    override suspend fun sendMessage(command: SendTaskMessageCommand): Result<TaskDetail> =
        error("sendMessage is not used by these ViewModel tests")

    override suspend fun generatePlans(command: GeneratePlansCommand): Result<List<TaskPlan>> {
        generateCommands += command
        return generateResults[generateCalls++]
    }

    override suspend fun selectPlan(command: SelectPlanCommand): Result<TaskDetail> {
        selectCommands += command
        return selectResults[selectCalls++]
    }
}
