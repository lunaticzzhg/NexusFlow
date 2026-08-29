package com.nexusflow.ai.understanding.openai

import com.nexusflow.ai.understanding.ConfirmedConstraint
import com.nexusflow.ai.understanding.ConstraintCandidate
import com.nexusflow.ai.understanding.ConstraintKind
import com.nexusflow.ai.understanding.ConstraintStrength
import com.nexusflow.ai.understanding.ConstraintValue
import com.nexusflow.ai.understanding.InvalidStructuredOutputException
import com.nexusflow.ai.understanding.ProviderRefusedException
import com.nexusflow.ai.understanding.ProviderTimeoutException
import com.nexusflow.ai.understanding.ProviderUnavailableException
import com.nexusflow.ai.understanding.UNDERSTAND_USER_MESSAGE_PROMPT_VERSION
import com.nexusflow.ai.understanding.UnderstandingContext
import com.nexusflow.ai.understanding.UnderstandingMetadata
import com.nexusflow.ai.understanding.UnderstandingOutcome
import com.nexusflow.ai.understanding.UserIntent
import com.nexusflow.ai.understanding.UserMessageUnderstanding
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlinx.datetime.Instant as ContractInstant

class OpenAiUserMessageUnderstanding(
    private val client: HttpClient,
    private val apiKey: String,
    private val model: String,
    baseUrl: String = "https://api.openai.com/v1",
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) : UserMessageUnderstanding {
    private val responsesUrl = "${baseUrl.trimEnd('/')}/responses"

    init {
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
        require(model.isNotBlank()) { "model must not be blank" }
    }

    override suspend fun understand(context: UnderstandingContext): UnderstandingOutcome {
        var attempt = 1
        while (true) {
            try {
                return requestOnce(context, attempt)
            } catch (error: InvalidStructuredOutputException) {
                if (attempt == MAX_ATTEMPTS) throw error
                attempt += 1
            }
        }
    }

    private suspend fun requestOnce(
        context: UnderstandingContext,
        attempt: Int,
    ): UnderstandingOutcome {
        val response = try {
            client.post(responsesUrl) {
                bearerAuth(apiKey)
                contentType(ContentType.Application.Json)
                setBody(
                    OpenAiResponsesRequest(
                        model = model,
                        input = context.toPrompt(),
                        text = OpenAiTextConfig(
                            format = OpenAiJsonSchemaFormat(
                                type = "json_schema",
                                name = OPEN_AI_UNDERSTANDING_SCHEMA_NAME,
                                schema = OpenAiUnderstandingSchema,
                                strict = true,
                            ),
                        ),
                    ),
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: HttpRequestTimeoutException) {
            throw ProviderTimeoutException(error)
        } catch (error: IOException) {
            throw ProviderUnavailableException(error)
        }

        if (response.status == HttpStatusCode.RequestTimeout) {
            throw ProviderTimeoutException()
        }
        if (response.status.value == 429 || response.status.value >= 500) {
            throw ProviderUnavailableException()
        }
        if (response.status.value !in 200..299) {
            throw ProviderUnavailableException()
        }

        val body = response.bodyAsText()
        val providerResponse = try {
            json.decodeFromString<OpenAiResponsesResponse>(body)
        } catch (error: SerializationException) {
            throw InvalidStructuredOutputException("OpenAI response envelope was not valid structured output", error)
        }
        val payloadText = providerResponse.structuredText()
        val payload = try {
            json.decodeFromString<OpenAiUnderstandingPayload>(payloadText)
        } catch (error: SerializationException) {
            throw InvalidStructuredOutputException("OpenAI response payload was not valid structured output", error)
        }

        return payload.toOutcome(
            context = context,
            metadata = UnderstandingMetadata(
                provider = "openai",
                model = model,
                promptVersion = UNDERSTAND_USER_MESSAGE_PROMPT_VERSION,
                providerRequestId = providerResponse.id,
                attemptCount = attempt,
            ),
        )
    }

    private fun OpenAiResponsesResponse.structuredText(): String {
        output
            .flatMap { it.content }
            .firstOrNull { !it.refusal.isNullOrBlank() }
            ?.let { throw ProviderRefusedException() }
        return outputText
            ?.takeIf(String::isNotBlank)
            ?: output
                .flatMap { it.content }
                .firstNotNullOfOrNull { content -> content.text?.takeIf(String::isNotBlank) }
            ?: throw InvalidStructuredOutputException("OpenAI response did not contain structured output text")
    }

    private fun OpenAiUnderstandingPayload.toOutcome(
        context: UnderstandingContext,
        metadata: UnderstandingMetadata,
    ): UnderstandingOutcome {
        val intent = userIntent.toUserIntent()
        val cleanMissingInformation = missingInformation.map { it.trim() }
        if (cleanMissingInformation.any(String::isBlank)) {
            throw InvalidStructuredOutputException("Missing information contained a blank value")
        }
        if (!clarificationNeeded && cleanMissingInformation.isNotEmpty()) {
            throw InvalidStructuredOutputException("Missing information requires clarificationNeeded=true")
        }
        if (clarificationNeeded && assistantMessageDraft.isNullOrBlank()) {
            throw InvalidStructuredOutputException("Clarification requires a nonblank assistant message")
        }
        return UnderstandingOutcome(
            userIntent = intent,
            extractedConstraints = extractedConstraints.map { it.toCandidate(context.currentMessage) },
            missingInformation = cleanMissingInformation,
            clarificationNeeded = clarificationNeeded,
            assistantMessageDraft = assistantMessageDraft?.trim()?.takeIf(String::isNotBlank),
            metadata = metadata,
        )
    }

    private fun OpenAiConstraintPayload.toCandidate(currentMessage: String): ConstraintCandidate {
        val cleanEvidence = evidenceText.trim()
        if (cleanEvidence.isBlank() || !currentMessage.contains(cleanEvidence)) {
            throw InvalidStructuredOutputException("Constraint evidence must be present in the current message")
        }
        val constraintKind = kind.toConstraintKind()
        return ConstraintCandidate(
            kind = constraintKind,
            value = toConstraintValue(constraintKind, cleanEvidence),
            strength = strength.toConstraintStrength(),
            evidenceText = cleanEvidence,
        )
    }

    private fun OpenAiConstraintPayload.toConstraintValue(
        constraintKind: ConstraintKind,
        evidenceText: String,
    ): ConstraintValue =
        when (constraintKind) {
            ConstraintKind.TimeWindow -> {
                rejectUnexpectedValueFields(
                    constraintKind = constraintKind,
                    allowedFields = arrayOf(
                        ProviderValueField.StartAt,
                        ProviderValueField.EndAt,
                        ProviderValueField.TimeZoneId,
                    ),
                )
                val start = startAt?.parseInstant("startAt")
                val end = endAt?.parseInstant("endAt")
                if (start != null && end != null && start >= end) {
                    throw InvalidStructuredOutputException("Time window startAt must be before endAt")
                }
                ConstraintValue.TimeWindow(
                    startAt = start,
                    endAt = end,
                    timeZoneId = timeZoneId.requireProviderText("timeZoneId"),
                    originalText = evidenceText,
                )
            }
            ConstraintKind.BudgetLimit -> {
                rejectUnexpectedValueFields(
                    constraintKind = constraintKind,
                    allowedFields = arrayOf(
                        ProviderValueField.AmountWholeUnits,
                        ProviderValueField.CurrencyCode,
                    ),
                )
                ConstraintValue.BudgetLimit(
                    wholeUnits = amountWholeUnits?.takeIf { it > 0 }
                        ?: throw InvalidStructuredOutputException("Budget amount must be positive"),
                    currencyCode = currencyCode?.trim()?.takeIf(String::isNotBlank),
                )
            }
            ConstraintKind.CommuteLimit -> {
                rejectUnexpectedValueFields(
                    constraintKind = constraintKind,
                    allowedFields = arrayOf(ProviderValueField.MaxMinutes),
                )
                ConstraintValue.CommuteLimit(
                    maxMinutes = maxMinutes?.takeIf { it > 0 }
                        ?: throw InvalidStructuredOutputException("Commute minutes must be positive"),
                )
            }
            ConstraintKind.Location -> {
                rejectUnexpectedValueFields(
                    constraintKind = constraintKind,
                    allowedFields = arrayOf(ProviderValueField.TextValue),
                )
                ConstraintValue.Location(textValue.requireProviderText("textValue"))
            }
            ConstraintKind.ActivityDomain -> {
                rejectUnexpectedValueFields(
                    constraintKind = constraintKind,
                    allowedFields = arrayOf(ProviderValueField.TextValue),
                )
                ConstraintValue.ActivityDomain(textValue.requireProviderText("textValue"))
            }
            ConstraintKind.Topic -> {
                rejectUnexpectedValueFields(
                    constraintKind = constraintKind,
                    allowedFields = arrayOf(ProviderValueField.TextValue),
                )
                ConstraintValue.Topic(textValue.requireProviderText("textValue"))
            }
            ConstraintKind.ExperiencePreference -> {
                rejectUnexpectedValueFields(
                    constraintKind = constraintKind,
                    allowedFields = arrayOf(ProviderValueField.TextValue),
                )
                ConstraintValue.ExperiencePreference(textValue.requireProviderText("textValue"))
            }
        }

    private fun OpenAiConstraintPayload.rejectUnexpectedValueFields(
        constraintKind: ConstraintKind,
        allowedFields: Array<ProviderValueField>,
    ) {
        presentValueFields()
            .firstOrNull { field -> field !in allowedFields }
            ?.let { field ->
                throw InvalidStructuredOutputException(
                    "${field.providerName} is not allowed for ${constraintKind.providerName}",
                )
            }
    }

    private fun OpenAiConstraintPayload.presentValueFields(): List<ProviderValueField> =
        listOfNotNull(
            ProviderValueField.TextValue.takeIf { textValue != null },
            ProviderValueField.AmountWholeUnits.takeIf { amountWholeUnits != null },
            ProviderValueField.CurrencyCode.takeIf { currencyCode != null },
            ProviderValueField.MaxMinutes.takeIf { maxMinutes != null },
            ProviderValueField.StartAt.takeIf { startAt != null },
            ProviderValueField.EndAt.takeIf { endAt != null },
            ProviderValueField.TimeZoneId.takeIf { timeZoneId != null },
        )

    private fun String.toUserIntent(): UserIntent =
        when (this) {
            "plan_request" -> UserIntent.PlanRequest
            "constraint_update" -> UserIntent.ConstraintUpdate
            "clarification_response" -> UserIntent.ClarificationResponse
            else -> throw InvalidStructuredOutputException("Unknown user intent")
        }

    private fun String.toConstraintKind(): ConstraintKind =
        when (this) {
            "time_window" -> ConstraintKind.TimeWindow
            "budget_limit" -> ConstraintKind.BudgetLimit
            "commute_limit" -> ConstraintKind.CommuteLimit
            "location" -> ConstraintKind.Location
            "activity_domain" -> ConstraintKind.ActivityDomain
            "topic" -> ConstraintKind.Topic
            "experience_preference" -> ConstraintKind.ExperiencePreference
            else -> throw InvalidStructuredOutputException("Unknown constraint kind")
        }

    private fun String.toConstraintStrength(): ConstraintStrength =
        when (this) {
            "hard" -> ConstraintStrength.Hard
            "soft" -> ConstraintStrength.Soft
            else -> throw InvalidStructuredOutputException("Unknown constraint strength")
        }

    private fun String?.requireProviderText(fieldName: String): String =
        this?.trim()?.takeIf(String::isNotBlank)
            ?: throw InvalidStructuredOutputException("$fieldName must be nonblank")

    private fun String.parseInstant(fieldName: String): ContractInstant =
        try {
            ContractInstant.parse(this)
        } catch (error: IllegalArgumentException) {
            throw InvalidStructuredOutputException("$fieldName must be an ISO instant", error)
        }

    private fun UnderstandingContext.toPrompt(): String =
        """
        Prompt version: $UNDERSTAND_USER_MESSAGE_PROMPT_VERSION

        Extract only facts supported by the current user message. evidenceText must be an exact substring of the current user message.
        Do not decide TaskState, permissions, prices, availability, external facts, or side effects.
        Use referenceTime and timeZoneId only to interpret temporal wording.
        Respond in the language of the current user message when drafting clarification.

        aiRequestId: $aiRequestId
        taskId: $taskId
        taskVersion: $taskVersion
        referenceTime: $referenceTime
        timeZoneId: $timeZoneId
        currentGoal: $currentGoal
        confirmedConstraints:
        ${confirmedConstraints.toPromptLines()}
        currentMessage:
        $currentMessage
        """.trimIndent()

    private fun List<ConfirmedConstraint>.toPromptLines(): String =
        if (isEmpty()) {
            "- none"
        } else {
            joinToString(separator = "\n") { constraint ->
                "- ${constraint.kind} ${constraint.strength}: ${constraint.value}"
            }
        }
}

private enum class ProviderValueField(val providerName: String) {
    TextValue("textValue"),
    AmountWholeUnits("amountWholeUnits"),
    CurrencyCode("currencyCode"),
    MaxMinutes("maxMinutes"),
    StartAt("startAt"),
    EndAt("endAt"),
    TimeZoneId("timeZoneId"),
}

private val ConstraintKind.providerName: String
    get() = when (this) {
        ConstraintKind.TimeWindow -> "time_window"
        ConstraintKind.BudgetLimit -> "budget_limit"
        ConstraintKind.CommuteLimit -> "commute_limit"
        ConstraintKind.Location -> "location"
        ConstraintKind.ActivityDomain -> "activity_domain"
        ConstraintKind.Topic -> "topic"
        ConstraintKind.ExperiencePreference -> "experience_preference"
    }

private const val MAX_ATTEMPTS = 2
