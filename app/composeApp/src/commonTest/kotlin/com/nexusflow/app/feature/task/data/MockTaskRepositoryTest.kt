package com.nexusflow.app.feature.task.data

import com.nexusflow.app.feature.task.domain.CreateConversationCommand
import com.nexusflow.app.feature.task.domain.MessageRole
import com.nexusflow.app.feature.task.domain.SendConversationMessageCommand
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
    fun createConversationReturnsFinalConversationDetailForTheSubmittedRequest() =
        runTest {
            val repository = MockTaskRepository()
            val created =
                repository.createConversation(
                    CreateConversationCommand(
                        creationRequestId = "create-1",
                        requestText = "  Plan a quiet evening  ",
                        timeZoneId = "Asia/Shanghai",
                    ),
                ).getOrThrow()

            assertEquals("conversation-created-demo", created.id.value)
        }

    @Test
    fun createdConversationCanBeLoadedFromTheSameRepositoryInstance() =
        runTest {
            val repository = MockTaskRepository()
            val created =
                repository.createConversation(
                    CreateConversationCommand(
                        creationRequestId = "create-1",
                        requestText = "Plan a quiet evening",
                        timeZoneId = "Asia/Shanghai",
                    ),
                ).getOrThrow()

            val loaded = repository.loadConversationDetail(created.id).getOrThrow()

            assertEquals(created, loaded)
        }

    @Test
    fun sendConversationMessageAppendsUserMessageForKnownConversation() =
        runTest {
            val repository = MockTaskRepository()
            val before = repository.loadConversationDetail(TaskFixtures.conversation.id).getOrThrow()

            val updated =
                repository.sendConversationMessage(
                    SendConversationMessageCommand(
                        conversationId = TaskFixtures.conversation.id,
                        clientMessageId = "client-message-2",
                        text = "Budget 500",
                        timeZoneId = "Asia/Shanghai",
                    ),
                ).getOrThrow()

            val appended = updated.messages.last()
            assertEquals(before.messages.size + 1, updated.messages.size)
            assertEquals("message-2", appended.id)
            assertEquals(MessageRole.User, appended.role)
            assertEquals("Budget 500", appended.content)
            assertEquals("client-message-2", appended.clientMessageId)
            assertEquals(before.currentTask, updated.currentTask)
            assertEquals(updated, repository.loadConversationDetail(TaskFixtures.conversation.id).getOrThrow())
        }
}
