package com.nexusflow.backend.feature.task.application

import com.nexusflow.ai.understanding.ConstraintCandidate as AiConstraintCandidate
import com.nexusflow.ai.understanding.ConstraintKind as AiConstraintKind
import com.nexusflow.ai.understanding.ConstraintStrength as AiConstraintStrength
import com.nexusflow.ai.understanding.ConstraintValue as AiConstraintValue
import com.nexusflow.ai.understanding.InvalidStructuredOutputException
import com.nexusflow.ai.understanding.UnderstandingContext as AiUnderstandingContext
import com.nexusflow.ai.understanding.UnderstandingMetadata
import com.nexusflow.ai.understanding.UnderstandingOutcome
import com.nexusflow.ai.understanding.UserIntent
import com.nexusflow.ai.understanding.UserMessageUnderstanding
import com.nexusflow.backend.core.identity.ActorContext
import com.nexusflow.backend.feature.task.domain.AppendUserMessageCommand
import com.nexusflow.backend.feature.task.domain.AppendUserMessageResult
import com.nexusflow.backend.feature.task.domain.ApplyUnderstandingCommand
import com.nexusflow.backend.feature.task.domain.ApplyUnderstandingResult
import com.nexusflow.backend.feature.task.domain.AiUnderstandingAuditEventType
import com.nexusflow.backend.feature.task.domain.Conversation
import com.nexusflow.backend.feature.task.domain.ConversationId
import com.nexusflow.backend.feature.task.domain.ConversationMessage
import com.nexusflow.backend.feature.task.domain.CreateFixturePlanningRunCommand
import com.nexusflow.backend.feature.task.domain.CreateFixturePlanningRunResult
import com.nexusflow.backend.feature.task.domain.CreateTaskPersistenceCommand
import com.nexusflow.backend.feature.task.domain.CreateTaskPersistenceResult
import com.nexusflow.backend.feature.task.domain.MessageId
import com.nexusflow.backend.feature.task.domain.MessageRole
import com.nexusflow.backend.feature.task.domain.Plan
import com.nexusflow.backend.feature.task.domain.PlanId
import com.nexusflow.backend.feature.task.domain.PlanningRun
import com.nexusflow.backend.feature.task.domain.RecordAiUnderstandingAuditCommand
import com.nexusflow.backend.feature.task.domain.RecordAiUnderstandingAuditResult
import com.nexusflow.backend.feature.task.domain.SelectPlanCommand
import com.nexusflow.backend.feature.task.domain.SelectPlanResult
import com.nexusflow.backend.feature.task.domain.Task
import com.nexusflow.backend.feature.task.domain.TaskConstraint
import com.nexusflow.backend.feature.task.domain.TaskDetail
import com.nexusflow.backend.feature.task.domain.TaskId
import com.nexusflow.backend.feature.task.domain.TaskOwner
import com.nexusflow.backend.feature.task.domain.TaskRepository
import com.nexusflow.backend.feature.task.domain.TaskState
import com.nexusflow.backend.feature.task.domain.TenantId
import com.nexusflow.backend.feature.task.domain.UserId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class TaskServiceTest {
    @Test
    fun `scope checks happen at the application boundary and create succeeds`() =
        runBlocking {
            val service = taskService()

            assertFailsWith<MissingTaskScopeException> {
                service.listTasks(actor(scopes = setOf(WriteScope)))
            }
            assertFailsWith<MissingTaskScopeException> {
                service.createTask(actor(scopes = setOf(ReadScope)), "create-forbidden", "Plan Saturday")
            }

            val created = service.createTask(fullActor, "create-1", " Plan Saturday ")

            assertEquals("Plan Saturday", created.task.currentGoal)
            assertEquals(TaskState.Draft, created.task.state)
        }

    @Test
    fun `clarification required keeps task collecting constraints`() =
        runBlocking {
            val service = taskService(
                understanding = RecordingUnderstanding {
                    clarificationOutcome()
                },
            )
            val created = service.createTask(fullActor, "create-1", "Plan dinner")

            val detail = service.sendMessage(fullActor, created.task.id.value.toString(), "message-1", "Dinner", "Asia/Shanghai")

            assertEquals(TaskState.CollectingConstraints, detail.task.state)
            assertEquals("What time should I plan around?", detail.messages.last().content)
        }

    @Test
    fun `sufficient understanding records success audit and persists user explicit constraints`() =
        runBlocking {
            val repository = FakeTaskRepository()
            val understanding = RecordingUnderstanding { context ->
                repository.events += "ai"
                assertEquals("Asia/Shanghai", context.timeZoneId)
                sufficientOutcome()
            }
            val service = taskService(repository = repository, understanding = understanding)
            val created = service.createTask(fullActor, "create-1", "Watch Liverpool")

            val detail = service.sendMessage(
                actor = fullActor,
                taskId = created.task.id.value.toString(),
                clientMessageId = "message-1",
                text = "预算 300",
                timeZoneId = "Asia/Shanghai",
            )

            assertEquals(TaskState.Planning, detail.task.state)
            assertEquals(1, understanding.calls.size)
            assertEquals(
                listOf("append", "audit:Started", "ai", "audit:Succeeded", "apply"),
                repository.events,
            )
            assertEquals("UserExplicit", detail.constraints.single().source.name)
            val succeeded = repository.audits.single { it.eventType == AiUnderstandingAuditEventType.Succeeded }
            assertEquals("test-provider", succeeded.provider)
            assertEquals("test-model", succeeded.model)
            assertEquals("test-prompt", succeeded.promptVersion)
            assertEquals("test-request", succeeded.providerRequestId)
            assertEquals(1, succeeded.attemptCount)
            assertNotNull(succeeded.latencyMs)
            Unit
        }

    @Test
    fun `AI failure leaves message pending records failed audit and retry reuses message without duplication`() =
        runBlocking {
            val repository = FakeTaskRepository()
            val understanding = RecordingUnderstanding { throw InvalidStructuredOutputException("bad output") }
            val service = taskService(repository = repository, understanding = understanding)
            val created = service.createTask(fullActor, "create-1", "Plan dinner")
            val taskId = created.task.id.value.toString()

            assertFailsWith<TaskDependencyUnavailableException> {
                service.sendMessage(fullActor, taskId, "message-1", "Futian tonight", "Asia/Shanghai")
            }
            assertFailsWith<TaskDependencyUnavailableException> {
                service.sendMessage(fullActor, taskId, "message-1", "Futian tonight", "Asia/Shanghai")
            }

            val detail = repository.detail!!
            assertEquals(TaskState.Draft, detail.task.state)
            assertEquals(1, detail.messages.size)
            assertEquals(null, detail.messages.single().understoodAt)
            assertEquals(emptyList(), detail.constraints)
            assertEquals(2, understanding.calls.size)
            assertEquals(2, repository.audits.count { it.eventType == AiUnderstandingAuditEventType.Started })
            val failed = repository.audits.filter { it.eventType == AiUnderstandingAuditEventType.Failed }
            assertEquals(2, failed.size)
            assertEquals("InvalidStructuredOutputException", failed.last().failureCategory)
        }

    @Test
    fun `AI cancellation propagates without failed audit`() =
        runBlocking {
            val repository = FakeTaskRepository()
            val understanding = RecordingUnderstanding { throw CancellationException("cancelled") }
            val service = taskService(repository = repository, understanding = understanding)
            val created = service.createTask(fullActor, "create-1", "Plan dinner")

            assertFailsWith<CancellationException> {
                service.sendMessage(fullActor, created.task.id.value.toString(), "message-1", "Futian tonight", "Asia/Shanghai")
            }

            assertEquals(1, repository.audits.count { it.eventType == AiUnderstandingAuditEventType.Started })
            assertEquals(0, repository.audits.count { it.eventType == AiUnderstandingAuditEventType.Failed })
            assertEquals(1, understanding.calls.size)
        }

    @Test
    fun `already understood message retry returns existing detail without another AI attempt`() =
        runBlocking {
            val repository = FakeTaskRepository()
            val understanding = RecordingUnderstanding { sufficientOutcome() }
            val service = taskService(repository = repository, understanding = understanding)
            val created = service.createTask(fullActor, "create-1", "Watch Liverpool")
            val taskId = created.task.id.value.toString()

            val first = service.sendMessage(fullActor, taskId, "message-1", "预算 300", "Asia/Shanghai")
            val second = service.sendMessage(fullActor, taskId, "message-1", "预算 300", "Asia/Shanghai")

            assertEquals(first.task.version, second.task.version)
            assertEquals(1, understanding.calls.size)
            assertEquals(1, repository.audits.count { it.eventType == AiUnderstandingAuditEventType.Started })
        }

    @Test
    fun `AI intent cannot choose task state`() =
        runBlocking {
            val service = taskService(
                understanding = RecordingUnderstanding {
                    sufficientOutcome(userIntent = UserIntent.ClarificationResponse)
                },
            )
            val created = service.createTask(fullActor, "create-1", "Watch Liverpool")

            val detail = service.sendMessage(fullActor, created.task.id.value.toString(), "message-1", "预算 300", "Asia/Shanghai")

            assertEquals(TaskState.Planning, detail.task.state)
        }

    @Test
    fun `stale understanding result is rejected and not applied`() =
        runBlocking {
            val repository = FakeTaskRepository()
            repository.beforeApply = {
                repository.forceTaskVersion(99)
            }
            val service = taskService(repository = repository, understanding = RecordingUnderstanding { sufficientOutcome() })
            val created = service.createTask(fullActor, "create-1", "Watch Liverpool")

            assertFailsWith<TaskConflictException> {
                service.sendMessage(fullActor, created.task.id.value.toString(), "message-1", "预算 300", "Asia/Shanghai")
            }

            val detail = repository.detail!!
            assertEquals(TaskState.Draft, detail.task.state)
            assertEquals(emptyList(), detail.constraints)
            assertEquals(null, detail.messages.single().understoodAt)
        }

    @Test
    fun `time zone validation accepts real zones and rejects invalid zones before message append and AI`() =
        runBlocking {
            val repository = FakeTaskRepository()
            val understanding = RecordingUnderstanding { sufficientOutcome() }
            val service = taskService(repository = repository, understanding = understanding)
            val created = service.createTask(fullActor, "create-1", "Watch Liverpool")
            val taskId = created.task.id.value.toString()

            service.sendMessage(fullActor, taskId, "message-valid", "预算 300", "UTC")

            assertFailsWith<InvalidTaskRequestException> {
                service.sendMessage(fullActor, taskId, "message-invalid", "Budget 300", "Not/A-Zone")
            }

            assertEquals(1, understanding.calls.size)
            assertEquals(1, repository.appendCalls.count { it.clientMessageId == "message-valid" })
            assertEquals(0, repository.appendCalls.count { it.clientMessageId == "message-invalid" })
        }

    @Test
    fun `fixture planning only starts from Planning and selecting a plan keeps WaitingForApproval`() =
        runBlocking {
            val repository = FakeTaskRepository()
            val service = taskService(
                repository = repository,
                understanding = RecordingUnderstanding { sufficientOutcome() },
                fixturePlanningEnabled = true,
            )
            val created = service.createTask(fullActor, "create-1", "Watch Liverpool")
            val taskId = created.task.id.value.toString()

            assertFailsWith<InvalidTaskStateException> {
                service.generateFixturePlans(fullActor, taskId, "planning-too-early")
            }

            service.sendMessage(fullActor, taskId, "message-1", "预算 300", "Asia/Shanghai")
            val generated = service.generateFixturePlans(fullActor, taskId, "planning-1")
            val selected = service.selectPlan(fullActor, taskId, generated.plans.single().id.value.toString())

            assertEquals(TaskState.WaitingForApproval, selected.task.state)
            assertEquals(generated.plans.single().id, selected.task.selectedPlanId)
        }

    private fun taskService(
        repository: FakeTaskRepository = FakeTaskRepository(),
        understanding: UserMessageUnderstanding = RecordingUnderstanding { sufficientOutcome() },
        fixturePlanningEnabled: Boolean = false,
    ): TaskService =
        TaskService(
            repository = repository,
            understanding = understanding,
            fixturePlanningEnabled = fixturePlanningEnabled,
            clock = FixedClock,
            uuidFactory = UuidSequence()::next,
        )

    private class RecordingUnderstanding(
        private val answer: suspend (AiUnderstandingContext) -> UnderstandingOutcome,
    ) : UserMessageUnderstanding {
        val calls = mutableListOf<AiUnderstandingContext>()

        override suspend fun understand(context: AiUnderstandingContext): UnderstandingOutcome {
            calls += context
            return answer(context)
        }
    }

    private class FakeTaskRepository : TaskRepository {
        var detail: TaskDetail? = null
        var beforeApply: (() -> Unit)? = null
        val appendCalls = mutableListOf<AppendUserMessageCommand>()
        val audits = mutableListOf<RecordAiUnderstandingAuditCommand>()
        val events = mutableListOf<String>()

        override suspend fun createTaskWithConversation(command: CreateTaskPersistenceCommand): CreateTaskPersistenceResult {
            detail?.let { existing ->
                return if (existing.task.creationRequestId == command.creationRequestId && existing.task.initialGoal == command.goal.trim()) {
                    CreateTaskPersistenceResult.Existing(existing)
                } else {
                    CreateTaskPersistenceResult.ConflictingRequest
                }
            }
            val task = Task(
                id = command.taskId,
                owner = command.owner,
                creationRequestId = command.creationRequestId,
                initialGoal = command.goal.trim(),
                currentGoal = command.goal.trim(),
                title = command.goal.trim(),
                state = TaskState.Draft,
                version = 1,
                selectedPlanId = null,
                createdAt = command.now,
                updatedAt = command.now,
            )
            detail = TaskDetail(
                task = task,
                conversation = Conversation(command.conversationId, command.taskId, command.now),
                messages = emptyList(),
                constraints = emptyList(),
                planningRuns = emptyList(),
                plans = emptyList(),
            )
            return CreateTaskPersistenceResult.Created(detail!!)
        }

        override suspend fun listTasks(owner: TaskOwner): List<Task> =
            detail?.takeIf { it.task.owner == owner }?.let { listOf(it.task) } ?: emptyList()

        override suspend fun findTaskDetail(owner: TaskOwner, taskId: TaskId): TaskDetail? =
            detail?.takeIf { it.task.owner == owner && it.task.id == taskId }

        override suspend fun appendUserMessage(command: AppendUserMessageCommand): AppendUserMessageResult {
            appendCalls += command
            events += "append"
            val current = findTaskDetail(command.owner, command.taskId) ?: return AppendUserMessageResult.TaskNotFound
            current.messages.firstOrNull { it.clientMessageId == command.clientMessageId }?.let { existing ->
                return if (existing.content == command.text) {
                    AppendUserMessageResult.Existing(current, existing, current.task.version)
                } else {
                    AppendUserMessageResult.ConflictingMessage
                }
            }
            val message = ConversationMessage(
                id = command.messageId,
                conversationId = current.conversation.id,
                role = MessageRole.User,
                content = command.text,
                clientMessageId = command.clientMessageId,
                aiRequestId = command.aiRequestId,
                understoodAt = null,
                createdAt = command.now,
            )
            detail = current.copy(messages = current.messages + message)
            return AppendUserMessageResult.Appended(detail!!, message, current.task.version)
        }

        override suspend fun recordAiUnderstandingAudit(command: RecordAiUnderstandingAuditCommand): RecordAiUnderstandingAuditResult {
            findTaskDetail(command.owner, command.taskId) ?: return RecordAiUnderstandingAuditResult.TaskNotFound
            audits += command
            events += "audit:${command.eventType}"
            return RecordAiUnderstandingAuditResult.Recorded
        }

        override suspend fun applyUnderstanding(command: ApplyUnderstandingCommand): ApplyUnderstandingResult {
            beforeApply?.invoke()
            events += "apply"
            val current = findTaskDetail(command.owner, command.taskId) ?: return ApplyUnderstandingResult.TaskNotFound
            if (current.task.version != command.expectedTaskVersion) return ApplyUnderstandingResult.StaleTaskVersion
            val message = current.messages.firstOrNull { it.id == command.messageId }
                ?: return ApplyUnderstandingResult.MessageNotFound
            val understoodMessages = current.messages.map {
                if (it.id == message.id) {
                    it.copy(aiRequestId = command.aiRequestId, understoodAt = command.now)
                } else {
                    it
                }
            }
            val assistantMessages = command.assistantMessage?.let { assistant ->
                listOf(
                    ConversationMessage(
                        id = assistant.id,
                        conversationId = current.conversation.id,
                        role = MessageRole.Assistant,
                        content = assistant.text,
                        clientMessageId = null,
                        aiRequestId = command.aiRequestId,
                        understoodAt = command.now,
                        createdAt = command.now,
                    ),
                )
            } ?: emptyList()
            val constraints = command.constraints.map { write ->
                TaskConstraint(
                    id = write.id,
                    taskId = command.taskId,
                    kind = write.kind,
                    value = write.value,
                    strength = write.strength,
                    source = com.nexusflow.backend.feature.task.domain.ConstraintSource.UserExplicit,
                    evidenceMessageId = message.id,
                    confirmedAt = command.now,
                    createdAt = command.now,
                    updatedAt = command.now,
                )
            }
            detail = current.copy(
                task = current.task.copy(
                    state = command.targetState,
                    version = current.task.version + 1,
                    updatedAt = command.now,
                ),
                messages = understoodMessages + assistantMessages,
                constraints = constraints,
            )
            return ApplyUnderstandingResult.Applied(detail!!)
        }

        override suspend fun createFixturePlanningRun(command: CreateFixturePlanningRunCommand): CreateFixturePlanningRunResult {
            val current = findTaskDetail(command.owner, command.taskId) ?: return CreateFixturePlanningRunResult.TaskNotFound
            current.planningRuns.firstOrNull { it.clientRequestId == command.clientRequestId }?.let {
                return CreateFixturePlanningRunResult.Existing(current, it)
            }
            if (current.task.state != TaskState.Planning) return CreateFixturePlanningRunResult.InvalidState
            val planningRun = PlanningRun(command.planningRunId, command.taskId, command.clientRequestId, current.task.version, command.now)
            detail = current.copy(
                task = current.task.copy(
                    state = TaskState.WaitingForApproval,
                    version = current.task.version + 1,
                    updatedAt = command.now,
                ),
                planningRuns = current.planningRuns + planningRun,
                plans = current.plans + command.plans,
            )
            return CreateFixturePlanningRunResult.Created(detail!!, planningRun)
        }

        override suspend fun selectPlan(command: SelectPlanCommand): SelectPlanResult {
            val current = findTaskDetail(command.owner, command.taskId) ?: return SelectPlanResult.TaskNotFound
            if (current.task.state != TaskState.WaitingForApproval) return SelectPlanResult.InvalidState
            if (current.plans.none { it.id == command.planId }) return SelectPlanResult.PlanNotFound
            detail = current.copy(task = current.task.copy(selectedPlanId = command.planId, updatedAt = command.now))
            return SelectPlanResult.Selected(detail!!)
        }

        fun forceTaskVersion(version: Long) {
            val current = detail ?: return
            detail = current.copy(task = current.task.copy(version = version))
        }
    }

    private class UuidSequence {
        private var nextValue = 1

        fun next(): UUID =
            UUID.fromString("00000000-0000-0000-0000-${nextValue++.toString().padStart(12, '0')}")
    }

    private companion object {
        private const val ReadScope = "orbit.tasks.read"
        private const val WriteScope = "orbit.tasks.write"
        val Owner = TaskOwner(
            tenantId = TenantId(UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")),
            userId = UserId(UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001")),
        )
        val fullActor = actor(scopes = setOf(ReadScope, WriteScope))
        val FixedClock: Clock = Clock.fixed(Instant.parse("2026-08-28T10:35:00Z"), ZoneOffset.UTC)

        fun actor(scopes: Set<String>): ActorContext =
            ActorContext(
                tenantId = Owner.tenantId.value.toString(),
                userId = Owner.userId.value.toString(),
                scopes = scopes,
            )

        fun sufficientOutcome(userIntent: UserIntent = UserIntent.PlanRequest): UnderstandingOutcome =
            UnderstandingOutcome(
                userIntent = userIntent,
                extractedConstraints = listOf(
                    AiConstraintCandidate(
                        kind = AiConstraintKind.BudgetLimit,
                        value = AiConstraintValue.BudgetLimit(wholeUnits = 300, currencyCode = null),
                        strength = AiConstraintStrength.Hard,
                        evidenceText = "300",
                    ),
                ),
                missingInformation = emptyList(),
                clarificationNeeded = false,
                assistantMessageDraft = null,
                metadata = aiMetadata(),
            )

        fun clarificationOutcome(): UnderstandingOutcome =
            UnderstandingOutcome(
                userIntent = UserIntent.PlanRequest,
                extractedConstraints = emptyList(),
                missingInformation = listOf("time"),
                clarificationNeeded = true,
                assistantMessageDraft = "What time should I plan around?",
                metadata = aiMetadata(),
            )

        fun aiMetadata(): UnderstandingMetadata =
            UnderstandingMetadata(
                provider = "test-provider",
                model = "test-model",
                promptVersion = "test-prompt",
                providerRequestId = "test-request",
                attemptCount = 1,
            )
    }
}
