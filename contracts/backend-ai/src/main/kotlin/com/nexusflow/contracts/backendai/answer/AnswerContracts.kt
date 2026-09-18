package com.nexusflow.contracts.backendai.answer

import com.nexusflow.contracts.backendai.common.ModelContextBlockPayload
import com.nexusflow.contracts.backendai.common.StructuredModelUsage
import com.nexusflow.contracts.backendai.conversation.ConversationMessagePayload
import com.nexusflow.contracts.backendai.conversation.InformationNeedMode
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

fun interface ConversationAnsweringCapability {
    suspend fun answer(request: ComposeConversationAnswerRequest): ComposeConversationAnswerResult
}

fun interface StreamingConversationAnsweringCapability : ConversationAnsweringCapability {
    suspend fun answer(
        request: ComposeConversationAnswerRequest,
        onDelta: suspend (String) -> Unit,
    ): ComposeConversationAnswerResult

    override suspend fun answer(request: ComposeConversationAnswerRequest): ComposeConversationAnswerResult =
        answer(request) { }
}

@Serializable
data class ComposeConversationAnswerRequest(
    @SerialName("aiRequestId")
    val aiRequestId: String,
    @SerialName("conversationId")
    val conversationId: String?,
    @SerialName("taskId")
    val taskId: String?,
    @SerialName("taskRevision")
    val taskRevision: Long?,
    @SerialName("question")
    val question: String,
    @SerialName("recentMessages")
    val recentMessages: List<ConversationMessagePayload> = emptyList(),
    @SerialName("referenceTime")
    val referenceTime: Instant,
    @SerialName("timeZoneId")
    val timeZoneId: String,
    @SerialName("informationNeeds")
    val informationNeeds: List<AnswerInformationNeedPayload>,
    @SerialName("evidence")
    val evidence: List<AnswerEvidencePayload> = emptyList(),
    @SerialName("optionalContext")
    val optionalContext: List<ModelContextBlockPayload> = emptyList(),
)

@Serializable
data class AnswerInformationNeedPayload(
    @SerialName("id")
    val id: String,
    @SerialName("question")
    val question: String,
    @SerialName("mode")
    val mode: InformationNeedMode,
    @SerialName("evidenceSourceIds")
    val evidenceSourceIds: List<String> = emptyList(),
    @SerialName("issues")
    val issues: List<ResearchIssuePayload> = emptyList(),
)

@Serializable
data class ResearchIssuePayload(
    @SerialName("type")
    val type: ResearchIssueType,
    @SerialName("missingInputs")
    val missingInputs: List<String> = emptyList(),
)

@Serializable
enum class ResearchIssueType {
    @SerialName("no_suitable_tool")
    NO_SUITABLE_TOOL,

    @SerialName("empty_result")
    EMPTY_RESULT,

    @SerialName("source_unavailable")
    SOURCE_UNAVAILABLE,

    @SerialName("missing_input")
    MISSING_INPUT,

    @SerialName("invalid_arguments")
    INVALID_ARGUMENTS,
}

@Serializable
data class ComposeConversationAnswerResult(
    @SerialName("answer")
    val answer: String,
    @SerialName("coverage")
    val coverage: List<AnswerNeedCoveragePayload>,
    @SerialName("metadata")
    val metadata: AnswerModelMetadata = AnswerModelMetadata(),
)

@Serializable
data class AnswerNeedCoveragePayload(
    @SerialName("needId")
    val needId: String,
    @SerialName("status")
    val status: AnswerNeedCoverageStatus,
    @SerialName("usedEvidenceSourceIds")
    val usedEvidenceSourceIds: List<String> = emptyList(),
)

@Serializable
enum class AnswerNeedCoverageStatus {
    @SerialName("answered")
    ANSWERED,

    @SerialName("unresolved")
    UNRESOLVED,
}

@Serializable
data class AnswerEvidencePayload(
    @SerialName("sourceId")
    val sourceId: String,
    @SerialName("sourceUrl")
    val sourceUrl: String? = null,
    @SerialName("sourceKey")
    val sourceKey: String,
    @SerialName("sourceUpdatedAt")
    val sourceUpdatedAt: Instant? = null,
    @SerialName("authority")
    val authority: AnswerEvidenceAuthorityPayload = AnswerEvidenceAuthorityPayload.STRUCTURED_PRIMARY,
    @SerialName("facts")
    val facts: List<AnswerEvidenceFactPayload>,
)

@Serializable
enum class AnswerEvidenceAuthorityPayload {
    @SerialName("structured_primary")
    STRUCTURED_PRIMARY,

    @SerialName("structured_secondary")
    STRUCTURED_SECONDARY,

    @SerialName("official_web")
    OFFICIAL_WEB,

    @SerialName("general_web")
    GENERAL_WEB,
}

@Serializable
data class AnswerEvidenceFactPayload(
    @SerialName("kind")
    val kind: AnswerEvidenceFactKindPayload,
    @SerialName("value")
    val value: AnswerEvidenceFactValuePayload,
)

@Serializable
enum class AnswerEvidenceFactKindPayload {
    @SerialName("title")
    TITLE,

    @SerialName("original_title")
    ORIGINAL_TITLE,

    @SerialName("summary")
    SUMMARY,

    @SerialName("type")
    TYPE,

    @SerialName("start_time")
    START_TIME,

    @SerialName("end_time")
    END_TIME,

    @SerialName("date")
    DATE,

    @SerialName("release_date")
    RELEASE_DATE,

    @SerialName("location_name")
    LOCATION_NAME,

    @SerialName("price")
    PRICE,

    @SerialName("availability")
    AVAILABILITY,

    @SerialName("activity_mode")
    ACTIVITY_MODE,

    @SerialName("temperature_celsius")
    TEMPERATURE_CELSIUS,

    @SerialName("precipitation_percent")
    PRECIPITATION_PERCENT,

    @SerialName("wind_speed_kph")
    WIND_SPEED_KPH,

    @SerialName("distance_meters")
    DISTANCE_METERS,

    @SerialName("duration_minutes")
    DURATION_MINUTES,

    @SerialName("commute_minutes")
    COMMUTE_MINUTES,

    @SerialName("elevation_gain_meters")
    ELEVATION_GAIN_METERS,

    @SerialName("home_team")
    HOME_TEAM,

    @SerialName("away_team")
    AWAY_TEAM,

    @SerialName("competition")
    COMPETITION,

    @SerialName("status")
    STATUS,

    @SerialName("runtime_minutes")
    RUNTIME_MINUTES,

    @SerialName("genres")
    GENRES,

    @SerialName("artists")
    ARTISTS,

    @SerialName("country_code")
    COUNTRY_CODE,

    @SerialName("popularity")
    POPULARITY,

    @SerialName("latitude")
    LATITUDE,

    @SerialName("longitude")
    LONGITUDE,

    @SerialName("profile")
    PROFILE,

    @SerialName("source_url")
    SOURCE_URL,
}

@Serializable
sealed interface AnswerEvidenceFactValuePayload {
    @Serializable
    @SerialName("text")
    data class Text(
        @SerialName("value")
        val value: String,
    ) : AnswerEvidenceFactValuePayload

    @Serializable
    @SerialName("integer")
    data class Integer(
        @SerialName("value")
        val value: Long,
    ) : AnswerEvidenceFactValuePayload

    @Serializable
    @SerialName("decimal")
    data class Decimal(
        @SerialName("value")
        val value: Double,
    ) : AnswerEvidenceFactValuePayload

    @Serializable
    @SerialName("timestamp")
    data class Timestamp(
        @SerialName("value")
        val value: Instant,
    ) : AnswerEvidenceFactValuePayload

    @Serializable
    @SerialName("money")
    data class Money(
        @SerialName("wholeUnits")
        val wholeUnits: Long,
        @SerialName("currencyCode")
        val currencyCode: String? = null,
    ) : AnswerEvidenceFactValuePayload

    @Serializable
    @SerialName("geo_point")
    data class GeoPoint(
        @SerialName("latitude")
        val latitude: Double,
        @SerialName("longitude")
        val longitude: Double,
    ) : AnswerEvidenceFactValuePayload
}

@Serializable
data class AnswerModelMetadata(
    @SerialName("provider")
    val provider: String? = null,
    @SerialName("model")
    val model: String? = null,
    @SerialName("promptVersion")
    val promptVersion: String? = null,
    @SerialName("providerRequestId")
    val providerRequestId: String? = null,
    @SerialName("attemptCount")
    val attemptCount: Int? = null,
    @SerialName("usage")
    val usage: StructuredModelUsage? = null,
)
