package com.nexusflow.ai.understanding.openai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class OpenAiResponsesRequest(
    @SerialName("model")
    val model: String,
    @SerialName("input")
    val input: String,
    @SerialName("text")
    val text: OpenAiTextConfig,
)

@Serializable
internal data class OpenAiTextConfig(
    @SerialName("format")
    val format: OpenAiJsonSchemaFormat,
)

@Serializable
internal data class OpenAiJsonSchemaFormat(
    @SerialName("type")
    val type: String,
    @SerialName("name")
    val name: String,
    @SerialName("schema")
    val schema: JsonObject,
    @SerialName("strict")
    val strict: Boolean,
)

@Serializable
internal data class OpenAiResponsesResponse(
    @SerialName("id")
    val id: String? = null,
    @SerialName("status")
    val status: String? = null,
    @SerialName("output_text")
    val outputText: String? = null,
    @SerialName("output")
    val output: List<OpenAiOutputItem> = emptyList(),
)

@Serializable
internal data class OpenAiOutputItem(
    @SerialName("type")
    val type: String? = null,
    @SerialName("content")
    val content: List<OpenAiOutputContent> = emptyList(),
)

@Serializable
internal data class OpenAiOutputContent(
    @SerialName("type")
    val type: String? = null,
    @SerialName("text")
    val text: String? = null,
    @SerialName("refusal")
    val refusal: String? = null,
)

@Serializable
internal data class OpenAiUnderstandingPayload(
    @SerialName("userIntent")
    val userIntent: String,
    @SerialName("extractedConstraints")
    val extractedConstraints: List<OpenAiConstraintPayload>,
    @SerialName("missingInformation")
    val missingInformation: List<String>,
    @SerialName("clarificationNeeded")
    val clarificationNeeded: Boolean,
    @SerialName("assistantMessageDraft")
    val assistantMessageDraft: String? = null,
)

@Serializable
internal data class OpenAiConstraintPayload(
    @SerialName("kind")
    val kind: String,
    @SerialName("strength")
    val strength: String,
    @SerialName("evidenceText")
    val evidenceText: String,
    @SerialName("textValue")
    val textValue: String? = null,
    @SerialName("amountWholeUnits")
    val amountWholeUnits: Long? = null,
    @SerialName("currencyCode")
    val currencyCode: String? = null,
    @SerialName("maxMinutes")
    val maxMinutes: Int? = null,
    @SerialName("startAt")
    val startAt: String? = null,
    @SerialName("endAt")
    val endAt: String? = null,
    @SerialName("timeZoneId")
    val timeZoneId: String? = null,
)
