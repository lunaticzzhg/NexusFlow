package com.nexusflow.backend.feature.conversation.application.answer

import com.nexusflow.backend.feature.research.application.ReadToolFact
import com.nexusflow.backend.feature.research.application.ReadToolFactKind
import com.nexusflow.backend.feature.research.application.ReadToolFactValue
import com.nexusflow.backend.feature.research.application.ReadToolSourceAuthority
import com.nexusflow.contracts.backendai.answer.AnswerEvidenceAuthorityPayload
import com.nexusflow.contracts.backendai.answer.AnswerEvidenceFactKindPayload
import com.nexusflow.contracts.backendai.answer.AnswerEvidenceFactPayload
import com.nexusflow.contracts.backendai.answer.AnswerEvidenceFactValuePayload
import com.nexusflow.contracts.backendai.answer.AnswerEvidencePayload
import com.nexusflow.contracts.backendai.answer.AnswerInformationNeedPayload
import com.nexusflow.contracts.backendai.answer.ComposeConversationAnswerRequest
import com.nexusflow.contracts.backendai.answer.ComposeConversationAnswerResult
import com.nexusflow.contracts.backendai.answer.ConversationAnsweringCapability
import com.nexusflow.contracts.backendai.answer.StreamingConversationAnsweringCapability
import com.nexusflow.contracts.backendai.common.AiCapabilityException
import com.nexusflow.contracts.backendai.common.InvalidCapabilityResultException
import com.nexusflow.contracts.backendai.conversation.InformationNeedProposal
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant as ContractInstant
import java.time.Instant

internal class ConversationAnswerStep(
    private val conversationAnswering: ConversationAnsweringCapability?,
    private val streamingConversationAnswering: StreamingConversationAnsweringCapability?,
) {
    suspend fun generate(
        request: ConversationAnswerTurnRequest,
        needs: List<InformationNeedProposal>,
        research: List<NeedResearch>,
    ): ConversationAnswerStepResult {
        val answerRequest = request.toAnswerRequest(needs, research)
        val usesStreamingAnswerer = streamingConversationAnswering != null
        val answer = try {
            when (val streamingAnswerer = streamingConversationAnswering) {
                null -> {
                    val answerer = conversationAnswering ?: return ConversationAnswerStepResult.AiUnavailable
                    answerer.answer(answerRequest)
                }
                else -> streamingAnswerer.answer(answerRequest, request.onAnswerDelta)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: InvalidCapabilityResultException) {
            if (usesStreamingAnswerer) throw error
            return ConversationAnswerStepResult.InvalidAiResult
        } catch (error: AiCapabilityException) {
            if (usesStreamingAnswerer) throw error
            return ConversationAnswerStepResult.AiUnavailable
        }
        return ConversationAnswerStepResult.Success(answer)
    }

    private fun ConversationAnswerTurnRequest.toAnswerRequest(
        needs: List<InformationNeedProposal>,
        research: List<NeedResearch>,
    ): ComposeConversationAnswerRequest {
        val researchByNeed = research.associateBy { it.needId }
        val evidence = research.flatMap { it.evidence }.distinctBy { it.sourceId }
        return ComposeConversationAnswerRequest(
            aiRequestId = "answer-$aiRequestId",
            conversationId = conversationId,
            taskId = taskId,
            taskRevision = taskRevision,
            question = userMessage.content,
            recentMessages = recentMessages.map { message -> message.toPayload() },
            referenceTime = referenceTime.toContractInstant(),
            timeZoneId = timeZoneId,
            informationNeeds = needs.map { need ->
                val needResearch = researchByNeed.getValue(need.id)
                AnswerInformationNeedPayload(
                    id = need.id,
                    question = need.question,
                    mode = need.mode,
                    evidenceSourceIds = needResearch.evidence.map { it.sourceId },
                    issues = needResearch.issues,
                )
            },
            evidence = evidence.map { item ->
                AnswerEvidencePayload(
                    sourceId = item.sourceId,
                    sourceUrl = item.sourceUrl,
                    sourceKey = item.sourceKey,
                    sourceUpdatedAt = item.sourceUpdatedAt?.toContractInstant(),
                    authority = item.authority.toPayload(),
                    facts = item.facts.map { fact -> fact.toPayload() },
                )
            },
            optionalContext = optionalContext,
        )
    }
}

internal sealed interface ConversationAnswerStepResult {
    data class Success(val answer: ComposeConversationAnswerResult) : ConversationAnswerStepResult
    data object AiUnavailable : ConversationAnswerStepResult
    data object InvalidAiResult : ConversationAnswerStepResult
}

private fun ReadToolSourceAuthority.toPayload(): AnswerEvidenceAuthorityPayload =
    when (this) {
        ReadToolSourceAuthority.StructuredPrimary -> AnswerEvidenceAuthorityPayload.STRUCTURED_PRIMARY
        ReadToolSourceAuthority.StructuredSecondary -> AnswerEvidenceAuthorityPayload.STRUCTURED_SECONDARY
        ReadToolSourceAuthority.OfficialWeb -> AnswerEvidenceAuthorityPayload.OFFICIAL_WEB
        ReadToolSourceAuthority.GeneralWeb -> AnswerEvidenceAuthorityPayload.GENERAL_WEB
    }

private fun ReadToolFact.toPayload(): AnswerEvidenceFactPayload =
    AnswerEvidenceFactPayload(
        kind = kind.toPayload(),
        value = value.toPayload(),
    )

private fun ReadToolFactKind.toPayload(): AnswerEvidenceFactKindPayload =
    when (this) {
        ReadToolFactKind.TITLE -> AnswerEvidenceFactKindPayload.TITLE
        ReadToolFactKind.ORIGINAL_TITLE -> AnswerEvidenceFactKindPayload.ORIGINAL_TITLE
        ReadToolFactKind.SUMMARY -> AnswerEvidenceFactKindPayload.SUMMARY
        ReadToolFactKind.TYPE -> AnswerEvidenceFactKindPayload.TYPE
        ReadToolFactKind.START_TIME -> AnswerEvidenceFactKindPayload.START_TIME
        ReadToolFactKind.END_TIME -> AnswerEvidenceFactKindPayload.END_TIME
        ReadToolFactKind.DATE -> AnswerEvidenceFactKindPayload.DATE
        ReadToolFactKind.RELEASE_DATE -> AnswerEvidenceFactKindPayload.RELEASE_DATE
        ReadToolFactKind.LOCATION_NAME -> AnswerEvidenceFactKindPayload.LOCATION_NAME
        ReadToolFactKind.PRICE -> AnswerEvidenceFactKindPayload.PRICE
        ReadToolFactKind.AVAILABILITY -> AnswerEvidenceFactKindPayload.AVAILABILITY
        ReadToolFactKind.ACTIVITY_MODE -> AnswerEvidenceFactKindPayload.ACTIVITY_MODE
        ReadToolFactKind.TEMPERATURE_CELSIUS -> AnswerEvidenceFactKindPayload.TEMPERATURE_CELSIUS
        ReadToolFactKind.PRECIPITATION_PERCENT -> AnswerEvidenceFactKindPayload.PRECIPITATION_PERCENT
        ReadToolFactKind.WIND_SPEED_KPH -> AnswerEvidenceFactKindPayload.WIND_SPEED_KPH
        ReadToolFactKind.DISTANCE_METERS -> AnswerEvidenceFactKindPayload.DISTANCE_METERS
        ReadToolFactKind.DURATION_MINUTES -> AnswerEvidenceFactKindPayload.DURATION_MINUTES
        ReadToolFactKind.COMMUTE_MINUTES -> AnswerEvidenceFactKindPayload.COMMUTE_MINUTES
        ReadToolFactKind.ELEVATION_GAIN_METERS -> AnswerEvidenceFactKindPayload.ELEVATION_GAIN_METERS
        ReadToolFactKind.HOME_TEAM -> AnswerEvidenceFactKindPayload.HOME_TEAM
        ReadToolFactKind.AWAY_TEAM -> AnswerEvidenceFactKindPayload.AWAY_TEAM
        ReadToolFactKind.COMPETITION -> AnswerEvidenceFactKindPayload.COMPETITION
        ReadToolFactKind.STATUS -> AnswerEvidenceFactKindPayload.STATUS
        ReadToolFactKind.RUNTIME_MINUTES -> AnswerEvidenceFactKindPayload.RUNTIME_MINUTES
        ReadToolFactKind.GENRES -> AnswerEvidenceFactKindPayload.GENRES
        ReadToolFactKind.ARTISTS -> AnswerEvidenceFactKindPayload.ARTISTS
        ReadToolFactKind.COUNTRY_CODE -> AnswerEvidenceFactKindPayload.COUNTRY_CODE
        ReadToolFactKind.POPULARITY -> AnswerEvidenceFactKindPayload.POPULARITY
        ReadToolFactKind.LATITUDE -> AnswerEvidenceFactKindPayload.LATITUDE
        ReadToolFactKind.LONGITUDE -> AnswerEvidenceFactKindPayload.LONGITUDE
        ReadToolFactKind.PROFILE -> AnswerEvidenceFactKindPayload.PROFILE
        ReadToolFactKind.SOURCE_URL -> AnswerEvidenceFactKindPayload.SOURCE_URL
    }

private fun ReadToolFactValue.toPayload(): AnswerEvidenceFactValuePayload =
    when (this) {
        is ReadToolFactValue.Text -> AnswerEvidenceFactValuePayload.Text(value)
        is ReadToolFactValue.Integer -> AnswerEvidenceFactValuePayload.Integer(value)
        is ReadToolFactValue.Decimal -> AnswerEvidenceFactValuePayload.Decimal(value)
        is ReadToolFactValue.Timestamp -> AnswerEvidenceFactValuePayload.Timestamp(value.toAnswerContractInstant())
        is ReadToolFactValue.Money -> AnswerEvidenceFactValuePayload.Money(wholeUnits, currencyCode)
        is ReadToolFactValue.GeoPoint -> AnswerEvidenceFactValuePayload.GeoPoint(latitude, longitude)
    }

private fun Instant.toAnswerContractInstant(): ContractInstant =
    ContractInstant.fromEpochSeconds(epochSecond, nano.toLong())
