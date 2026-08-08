@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.nexusflow.app.feature.task.presentation

import com.nexusflow.app.feature.task.data.TaskFixtures
import com.nexusflow.app.feature.task.domain.CreateTaskCommand
import com.nexusflow.app.feature.task.domain.TaskId
import com.nexusflow.app.feature.task.domain.TaskReference
import com.nexusflow.app.feature.task.domain.TaskRepository
import com.nexusflow.app.feature.task.domain.TaskSummary
import com.nexusflow.app.feature.task.presentation.create.TaskCreateEffect
import com.nexusflow.app.feature.task.presentation.create.TaskCreateIntent
import com.nexusflow.app.feature.task.presentation.create.TaskCreateViewModel
import com.nexusflow.app.feature.task.presentation.create.TaskSubmission
import com.nexusflow.app.feature.task.presentation.home.TaskHomeContent
import com.nexusflow.app.feature.task.presentation.home.TaskHomeIntent
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
    fun homeLoadsContentOnceAndDoesNotReloadAfterReentry() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository = SequencedTaskRepository(listOf(Result.success(TaskFixtures.success)))
                val viewModel = TaskHomeViewModel(repository)

                viewModel.dispatch(TaskHomeIntent.Load)
                advanceUntilIdle()
                assertIs<TaskHomeContent.Success>(viewModel.state.value.content)

                viewModel.dispatch(TaskHomeIntent.Load)
                advanceUntilIdle()
                assertEquals(1, repository.loadCalls)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun homeLoadsThenRetriesAfterFailure() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository = SequencedTaskRepository(listOf(Result.failure(IllegalStateException()), Result.success(emptyList())))
                val viewModel = TaskHomeViewModel(repository)

                viewModel.dispatch(TaskHomeIntent.Load)
                advanceUntilIdle()
                assertIs<TaskHomeContent.Failure>(viewModel.state.value.content)

                viewModel.dispatch(TaskHomeIntent.Retry)
                advanceUntilIdle()
                assertIs<TaskHomeContent.Empty>(viewModel.state.value.content)
                assertEquals(2, repository.loadCalls)
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun createRejectsBlankInputAndEmitsOneSuccessEffectWhileSubmitting() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository = SequencedTaskRepository(createResult = Result.success(TaskReference(TaskId("new-task"), "Weekend plan")))
                val viewModel = TaskCreateViewModel(repository)
                val effects = mutableListOf<TaskCreateEffect>()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.effects.toList(effects) }
                runCurrent()

                viewModel.dispatch(TaskCreateIntent.Submit)
                assertEquals(0, repository.createCalls)

                viewModel.dispatch(TaskCreateIntent.RequestChanged("Weekend plan"))
                viewModel.dispatch(TaskCreateIntent.Submit)
                viewModel.dispatch(TaskCreateIntent.Submit)
                assertIs<TaskSubmission.Submitting>(viewModel.state.value.submission)
                advanceUntilIdle()

                assertEquals(1, repository.createCalls)
                assertEquals(
                    listOf<TaskCreateEffect>(TaskCreateEffect.OpenTask(TaskId("new-task"), "Weekend plan")),
                    effects,
                )
            } finally {
                Dispatchers.resetMain()
            }
        }

    @Test
    fun createFailureCanBeRetried() =
        runTest {
            val dispatcher = StandardTestDispatcher(testScheduler)
            Dispatchers.setMain(dispatcher)
            try {
                val repository =
                    SequencedTaskRepository(
                        createResults =
                            listOf(
                                Result.failure(IllegalStateException()),
                                Result.success(TaskReference(TaskId("new-task"), "Weekend plan")),
                            ),
                    )
                val viewModel = TaskCreateViewModel(repository)
                viewModel.dispatch(TaskCreateIntent.RequestChanged("Weekend plan"))
                viewModel.dispatch(TaskCreateIntent.Submit)
                advanceUntilIdle()
                assertIs<TaskSubmission.Failed>(viewModel.state.value.submission)

                viewModel.dispatch(TaskCreateIntent.RetrySubmit)
                advanceUntilIdle()
                assertEquals(2, repository.createCalls)
            } finally {
                Dispatchers.resetMain()
            }
        }
}

private class SequencedTaskRepository(
    private val loadResults: List<Result<List<TaskSummary>>> = emptyList(),
    private val createResult: Result<TaskReference>? = null,
    private val createResults: List<Result<TaskReference>> = emptyList(),
) : TaskRepository {
    var loadCalls = 0
    var createCalls = 0

    override suspend fun loadTaskSummaries(): Result<List<TaskSummary>> = loadResults[loadCalls++]

    override suspend fun createTask(command: CreateTaskCommand): Result<TaskReference> =
        (createResult ?: createResults[createCalls]).also { createCalls += 1 }
}
