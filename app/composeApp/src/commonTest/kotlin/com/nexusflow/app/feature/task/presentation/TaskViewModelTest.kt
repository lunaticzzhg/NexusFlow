@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.nexusflow.app.feature.task.presentation

import com.nexusflow.app.feature.task.data.TaskFixtures
import com.nexusflow.app.feature.task.domain.CreateTaskCommand
import com.nexusflow.app.feature.task.domain.RemoveRequirementCommand
import com.nexusflow.app.feature.task.domain.SelectPlanCommand
import com.nexusflow.app.feature.task.domain.SendTaskMessageCommand
import com.nexusflow.app.feature.task.domain.TaskDetail
import com.nexusflow.app.feature.task.domain.TaskId
import com.nexusflow.app.feature.task.domain.TaskRepository
import com.nexusflow.app.feature.task.domain.TaskSummary
import com.nexusflow.app.feature.task.domain.UpdateRequirementCommand
import com.nexusflow.app.feature.task.presentation.create.TaskCreateAction
import com.nexusflow.app.feature.task.presentation.create.TaskCreateEffect
import com.nexusflow.app.feature.task.presentation.create.TaskCreateViewModel
import com.nexusflow.app.feature.task.presentation.detail.TaskDetailAction
import com.nexusflow.app.feature.task.presentation.detail.TaskDetailContent
import com.nexusflow.app.feature.task.presentation.detail.TaskDetailOperation
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

class TaskViewModelTest {
    @Test
    fun `home loads things and opens selected id`() =
        viewModelTest {
            val repository = RecordingTaskRepository(loadResults = listOf(Result.success(TaskFixtures.success)))
            val viewModel = TaskHomeViewModel(repository)
            val effects = mutableListOf<TaskHomeEffect>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.toList(effects) }
            runCurrent()

            viewModel.onAction(TaskHomeAction.Load)
            advanceUntilIdle()
            viewModel.onAction(TaskHomeAction.OpenTask(TaskFixtures.success.single().id))
            advanceUntilIdle()

            val content = assertIs<TaskHomeContent.Success>(viewModel.state.value.content)
            assertEquals("Create a calendar event and a pre-match reminder", content.summaries.single().intent)
            assertEquals(listOf(TaskFixtures.success.single().id), effects.map { (it as TaskHomeEffect.OpenTask).taskId })
        }

    @Test
    fun `create submits one message based task request`() =
        viewModelTest {
            val repository = RecordingTaskRepository(createResults = listOf(Result.success(TaskFixtures.detail)))
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
                listOf(CreateTaskCommand("create-1", "Plan Saturday", "Asia/Shanghai")),
                repository.createCommands,
            )
            assertEquals(listOf(TaskFixtures.detail.id), effects.map { (it as TaskCreateEffect.OpenTask).taskId })
        }

    @Test
    fun `detail sends messages and selects plans without client planning action`() =
        viewModelTest {
            val selected = TaskFixtures.detail.copy(selectedPlanId = TaskFixtures.currentPlans.single().id)
            val repository =
                RecordingTaskRepository(
                    detailResults = listOf(Result.success(TaskFixtures.detail)),
                    sendResults = listOf(Result.success(TaskFixtures.detail.copy(revision = 2))),
                    selectResults = listOf(Result.success(selected)),
                )
            val viewModel =
                TaskDetailViewModel(
                    taskId = TaskFixtures.detail.id,
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
                listOf(SendTaskMessageCommand(TaskFixtures.detail.id, "message-1", "Keep it nearby", "Asia/Shanghai")),
                repository.sendCommands,
            )
            assertEquals(
                listOf(SelectPlanCommand(TaskFixtures.detail.id, TaskFixtures.currentPlans.single().id)),
                repository.selectCommands,
            )
            val content = assertIs<TaskDetailContent.Success>(viewModel.state.value.content)
            assertEquals(TaskDetailOperation.Idle, content.operation)
            assertEquals(TaskFixtures.currentPlans.single().id, content.detail.selectedPlanId)
        }

    @Test
    fun `detail removes a requirement`() =
        viewModelTest {
            val requirementId = TaskFixtures.detail.requirements.single().id
            val repository =
                RecordingTaskRepository(
                    detailResults = listOf(Result.success(TaskFixtures.detail)),
                    removeResults = listOf(Result.success(TaskFixtures.detail.copy(requirements = emptyList()))),
                )
            val viewModel = TaskDetailViewModel(taskId = TaskFixtures.detail.id, repository = repository)

            viewModel.onAction(TaskDetailAction.Load)
            advanceUntilIdle()
            viewModel.onAction(TaskDetailAction.RemoveRequirement(requirementId))
            advanceUntilIdle()

            assertEquals(listOf(RemoveRequirementCommand(TaskFixtures.detail.id, requirementId)), repository.removeCommands)
            val content = assertIs<TaskDetailContent.Success>(viewModel.state.value.content)
            assertEquals(emptyList(), content.detail.requirements)
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
    private val sendResults: List<Result<TaskDetail>> = emptyList(),
    private val updateResults: List<Result<TaskDetail>> = emptyList(),
    private val removeResults: List<Result<TaskDetail>> = emptyList(),
    private val selectResults: List<Result<TaskDetail>> = emptyList(),
) : TaskRepository {
    val createCommands = mutableListOf<CreateTaskCommand>()
    val sendCommands = mutableListOf<SendTaskMessageCommand>()
    val updateCommands = mutableListOf<UpdateRequirementCommand>()
    val removeCommands = mutableListOf<RemoveRequirementCommand>()
    val selectCommands = mutableListOf<SelectPlanCommand>()
    private val loadQueue = ArrayDeque(loadResults)
    private val createQueue = ArrayDeque(createResults)
    private val detailQueue = ArrayDeque(detailResults)
    private val sendQueue = ArrayDeque(sendResults)
    private val updateQueue = ArrayDeque(updateResults)
    private val removeQueue = ArrayDeque(removeResults)
    private val selectQueue = ArrayDeque(selectResults)

    override suspend fun loadTaskSummaries(): Result<List<TaskSummary>> = loadQueue.removeFirst()

    override suspend fun createTask(command: CreateTaskCommand): Result<TaskDetail> {
        createCommands += command
        return createQueue.removeFirst()
    }

    override suspend fun loadTaskDetail(taskId: TaskId): Result<TaskDetail> = detailQueue.removeFirst()

    override suspend fun sendMessage(command: SendTaskMessageCommand): Result<TaskDetail> {
        sendCommands += command
        return sendQueue.removeFirst()
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
