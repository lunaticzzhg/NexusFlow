package com.nexusflow.app.feature.task.data

import com.nexusflow.app.feature.task.domain.CreateTaskCommand
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MockTaskRepositoryTest {
    @Test
    fun fixturesExposeSuccessEmptyAndFailureThroughTheRepositoryBoundary() =
        runTest {
            assertTrue(MockTaskRepository(TaskSummaryFixture.Success).loadTaskSummaries().getOrThrow().isNotEmpty())
            assertTrue(MockTaskRepository(TaskSummaryFixture.Empty).loadTaskSummaries().getOrThrow().isEmpty())
            assertTrue(MockTaskRepository(TaskSummaryFixture.Failure).loadTaskSummaries().isFailure)
        }

    @Test
    fun createReturnsAStableReferenceForTheSubmittedRequest() =
        runTest {
            val created =
                MockTaskRepository().createTask(CreateTaskCommand("  Plan a quiet evening  ")).getOrThrow()

            assertEquals("task-created-demo", created.id.value)
            assertEquals("Plan a quiet evening", created.title)
        }
}
