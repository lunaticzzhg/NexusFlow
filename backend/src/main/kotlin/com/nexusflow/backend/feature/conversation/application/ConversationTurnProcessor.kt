package com.nexusflow.backend.feature.conversation.application

import com.nexusflow.backend.feature.conversation.application.answer.ConversationAnswerService
import com.nexusflow.backend.feature.conversation.domain.ConversationRepository
import com.nexusflow.backend.feature.responserun.domain.ClaimedResponseRun
import com.nexusflow.backend.feature.responserun.domain.ResponseRunResultPayload
import com.nexusflow.backend.feature.task.application.PlanningService
import com.nexusflow.backend.feature.task.domain.TaskRepository
import com.nexusflow.contracts.backendai.conversation.ConversationTurnCapability
import com.nexusflow.observability.StructuredLogger
import java.time.Clock
import java.util.UUID

class ConversationTurnProcessor(
    private val conversationRepository: ConversationRepository,
    private val taskRepository: TaskRepository,
    private val conversationTurn: ConversationTurnCapability? = null,
    private val conversationAnswerService: ConversationAnswerService? = null,
    private val planningService: PlanningService? = null,
    private val realtimeHub: ResponseRunRealtimeHub? = null,
    private val logger: StructuredLogger? = null,
    private val clock: Clock = Clock.systemUTC(),
    private val uuidFactory: () -> UUID = UUID::randomUUID,
) {
    private val payloadMapper = ConversationTurnPayloadMapper(clock, uuidFactory)
    private val workflow = ConversationTurnWorkflow(
        contextLoader = ConversationTurnContextLoader(conversationRepository, taskRepository),
        payloadMapper = payloadMapper,
        conversationTurn = conversationTurn,
        conversationAnswerService = conversationAnswerService,
        planningService = planningService,
        realtimeHub = realtimeHub,
        logger = logger,
        clock = clock,
        uuidFactory = uuidFactory,
    )

    suspend fun process(claim: ClaimedResponseRun): ResponseRunResultPayload = workflow.execute(claim)
}
