package com.nexusflow.backend.feature.research.application

import kotlinx.serialization.json.JsonObject
import java.time.Instant

@JvmInline
value class ReadToolKey(val value: String) {
    init {
        require(value.isNotBlank()) { "read tool key must not be blank" }
    }
}

data class ReadToolDefinition(
    val key: ReadToolKey,
    val description: String,
    val argumentHint: String,
    val activityKind: ReadToolActivityKind = ReadToolActivityKind.OtherResearch,
) {
    init {
        require(description.isNotBlank()) { "read tool description must not be blank" }
        require(argumentHint.isNotBlank()) { "read tool argument hint must not be blank" }
    }
}

enum class ReadToolActivityKind {
    Weather,
    PlaceSearch,
    Route,
    Movie,
    Sports,
    Music,
    Web,
    OtherResearch,
}

interface ReadToolExecutionObserver {
    suspend fun onStarted(
        call: ReadToolCall,
        definition: ReadToolDefinition,
    )

    suspend fun onFinished(
        call: ReadToolCall,
        definition: ReadToolDefinition,
        outcome: ReadToolOutcome,
    )
}

interface ReadTool {
    val definition: ReadToolDefinition

    suspend fun execute(
        proposedArguments: JsonObject,
        context: ReadToolExecutionContext,
    ): ReadToolOutcome
}

data class ReadToolExecutionContext(
    val referenceTime: Instant,
    val timeZoneId: String,
    val responseRunId: String? = null,
    val actorTenantId: String? = null,
    val actorUserId: String? = null,
    val conversationId: String? = null,
    val taskId: String? = null,
) {
    init {
        require(timeZoneId.isNotBlank()) { "timeZoneId must not be blank" }
    }
}

sealed interface ReadToolOutcome {
    data class Success(val payload: ReadToolPayload) : ReadToolOutcome
    data object Empty : ReadToolOutcome
    data class MissingInput(val fields: Set<String>) : ReadToolOutcome {
        init {
            require(fields.isNotEmpty()) { "missing input fields must not be empty" }
            require(fields.none(String::isBlank)) { "missing input fields must not be blank" }
        }
    }
    data class InvalidArguments(val reasonCode: String) : ReadToolOutcome {
        init {
            require(reasonCode.isNotBlank()) { "invalid argument reasonCode must not be blank" }
        }
    }
    data class Unavailable(val reason: String) : ReadToolOutcome
}

sealed interface ReadToolPayload {
    val evidence: List<ReadToolEvidence>
}

data class ReadToolEvidence(
    val sourceId: String,
    val sourceUrl: String?,
    val sourceKey: String,
    val sourceUpdatedAt: Instant? = null,
    val authority: ReadToolSourceAuthority = ReadToolSourceAuthority.StructuredPrimary,
    val facts: List<ReadToolFact>,
) {
    init {
        require(sourceId.isNotBlank()) { "sourceId must not be blank" }
        require(sourceKey.isNotBlank()) { "sourceKey must not be blank" }
        require(facts.isNotEmpty()) { "read tool evidence requires distilled facts" }
    }
}

enum class ReadToolSourceAuthority {
    StructuredPrimary,
    StructuredSecondary,
    OfficialWeb,
    GeneralWeb,
}

data class ReadToolFact(
    val kind: ReadToolFactKind,
    val value: ReadToolFactValue,
)

enum class ReadToolFactKind {
    TITLE,
    ORIGINAL_TITLE,
    SUMMARY,
    TYPE,
    START_TIME,
    END_TIME,
    DATE,
    RELEASE_DATE,
    LOCATION_NAME,
    PRICE,
    AVAILABILITY,
    ACTIVITY_MODE,
    TEMPERATURE_CELSIUS,
    PRECIPITATION_PERCENT,
    WIND_SPEED_KPH,
    DISTANCE_METERS,
    DURATION_MINUTES,
    COMMUTE_MINUTES,
    ELEVATION_GAIN_METERS,
    HOME_TEAM,
    AWAY_TEAM,
    COMPETITION,
    STATUS,
    RUNTIME_MINUTES,
    GENRES,
    ARTISTS,
    COUNTRY_CODE,
    POPULARITY,
    LATITUDE,
    LONGITUDE,
    PROFILE,
    SOURCE_URL,
}

sealed interface ReadToolFactValue {
    data class Text(val value: String) : ReadToolFactValue
    data class Integer(val value: Long) : ReadToolFactValue
    data class Decimal(val value: Double) : ReadToolFactValue
    data class Timestamp(val value: Instant) : ReadToolFactValue
    data class Money(val wholeUnits: Long, val currencyCode: String?) : ReadToolFactValue
    data class GeoPoint(val latitude: Double, val longitude: Double) : ReadToolFactValue
}

data class ReadToolEvidencePayload(
    override val evidence: List<ReadToolEvidence>,
) : ReadToolPayload {
    init {
        require(evidence.isNotEmpty()) { "successful read tool payload requires evidence" }
    }
}

data class ReadToolCall(
    val key: ReadToolKey,
    val arguments: JsonObject,
)

data class ReadToolExecution(
    val call: ReadToolCall,
    val outcome: ReadToolOutcome,
)
