package com.nexusflow.ai.understanding.openai

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val OPEN_AI_UNDERSTANDING_SCHEMA_NAME = "orbit_m0_user_message_understanding"

internal val OpenAiUnderstandingSchema: JsonObject =
    buildJsonObject {
        put("type", "object")
        put(
            "required",
            jsonArray(
                "userIntent",
                "extractedConstraints",
                "missingInformation",
                "clarificationNeeded",
                "assistantMessageDraft",
            ),
        )
        put("additionalProperties", false)
        put(
            "properties",
            buildJsonObject {
                put("userIntent", enumString("plan_request", "constraint_update", "clarification_response"))
                put(
                    "extractedConstraints",
                    buildJsonObject {
                        put("type", "array")
                        put(
                            "items",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "required",
                                    jsonArray(
                                        "kind",
                                        "strength",
                                        "evidenceText",
                                        "textValue",
                                        "amountWholeUnits",
                                        "currencyCode",
                                        "maxMinutes",
                                        "startAt",
                                        "endAt",
                                        "timeZoneId",
                                    ),
                                )
                                put("additionalProperties", false)
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put(
                                            "kind",
                                            enumString(
                                                "time_window",
                                                "budget_limit",
                                                "commute_limit",
                                                "location",
                                                "activity_domain",
                                                "topic",
                                                "experience_preference",
                                            ),
                                        )
                                        put("strength", enumString("hard", "soft"))
                                        put("evidenceText", stringSchema())
                                        put("textValue", nullableStringSchema())
                                        put("amountWholeUnits", nullableNumberSchema("integer"))
                                        put("currencyCode", nullableStringSchema())
                                        put("maxMinutes", nullableNumberSchema("integer"))
                                        put("startAt", nullableStringSchema())
                                        put("endAt", nullableStringSchema())
                                        put("timeZoneId", nullableStringSchema())
                                    },
                                )
                            },
                        )
                    },
                )
                put(
                    "missingInformation",
                    buildJsonObject {
                        put("type", "array")
                        put("items", stringSchema())
                    },
                )
                put("clarificationNeeded", buildJsonObject { put("type", "boolean") })
                put("assistantMessageDraft", nullableStringSchema())
            },
        )
    }

private fun enumString(vararg values: String): JsonObject =
    buildJsonObject {
        put("type", "string")
        put("enum", JsonArray(values.map(::JsonPrimitive)))
    }

private fun stringSchema(): JsonObject =
    buildJsonObject { put("type", "string") }

private fun nullableStringSchema(): JsonObject =
    buildJsonObject { put("type", jsonArray("string", "null")) }

private fun nullableNumberSchema(type: String): JsonObject =
    buildJsonObject { put("type", jsonArray(type, "null")) }

private fun jsonArray(vararg values: String): JsonArray =
    buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }
