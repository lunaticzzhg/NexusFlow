package com.nexusflow.ai.understanding

import com.nexusflow.contracts.backendai.common.ModelContextBlockPayload
import com.nexusflow.contracts.backendai.common.SelectableContextDefinitionPayload
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class UnderstandingModelPayload(
    @SerialName("request")
    val request: UnderstandingModelRequest,
    @SerialName("activePlanning")
    val activePlanning: ActivePlanningPayload? = null,
    @SerialName("optionalContext")
    val optionalContext: List<ModelContextBlockPayload> = emptyList(),
    @SerialName("availableContextDefinitions")
    val availableContextDefinitions: List<SelectableContextDefinitionPayload> = emptyList(),
)

@Serializable
internal data class UnderstandingModelRequest(
    @SerialName("currentMessage")
    val currentMessage: String,
    @SerialName("referenceTime")
    val referenceTime: Instant,
    @SerialName("timeZoneId")
    val timeZoneId: String,
)

@Serializable
internal data class ActivePlanningPayload(
    @SerialName("goal")
    val goal: String,
    @SerialName("requirements")
    val requirements: List<RequirementPayload>,
)

@Serializable
internal data class RequirementPayload(
    @SerialName("kind")
    val kind: String,
    @SerialName("valueSummary")
    val valueSummary: String,
    @SerialName("strength")
    val strength: String,
)

@Serializable
internal data class StructuredUnderstandingPayload(
    @SerialName("turnIntent")
    val turnIntent: String,
    @SerialName("planningGoalPatch")
    val planningGoalPatch: String? = null,
    @SerialName("constraintDeltas")
    val constraintDeltas: List<StructuredConstraintDeltaPayload>,
    @SerialName("clarification")
    val clarification: StructuredClarificationPayload,
    @SerialName("contextSelection")
    val contextSelection: StructuredContextSelectionPayload,
)

@Serializable
internal data class StructuredContextSelectionPayload(
    @SerialName("selectedKeys")
    val selectedKeys: List<String>,
)

@Serializable
internal data class StructuredClarificationPayload(
    @SerialName("needed")
    val needed: Boolean,
    @SerialName("missingInformation")
    val missingInformation: List<String>,
    @SerialName("reasonCategory")
    val reasonCategory: String,
    @SerialName("questionDraft")
    val questionDraft: String? = null,
)

@Serializable
internal data class StructuredConstraintDeltaPayload(
    @SerialName("operation")
    val operation: String,
    @SerialName("kind")
    val kind: String,
    @SerialName("strength")
    val strength: String? = null,
    @SerialName("evidenceText")
    val evidenceText: String,
    @SerialName("value")
    val value: StructuredRequirementValuePayload? = null,
)

@Serializable
internal data class StructuredRequirementValuePayload(
    @SerialName("type")
    val type: String,
    @SerialName("textValue")
    val textValue: String? = null,
    @SerialName("amountWholeUnits")
    val amountWholeUnits: Long? = null,
    @SerialName("currencyCode")
    val currencyCode: String? = null,
    @SerialName("maxMinutes")
    val maxMinutes: Int? = null,
    @SerialName("commutePreference")
    val commutePreference: String? = null,
    @SerialName("activityMode")
    val activityMode: String? = null,
    @SerialName("startAt")
    val startAt: String? = null,
    @SerialName("endAt")
    val endAt: String? = null,
    @SerialName("timeZoneId")
    val timeZoneId: String? = null,
)
