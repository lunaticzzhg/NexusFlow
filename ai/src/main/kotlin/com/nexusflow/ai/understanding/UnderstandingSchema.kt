package com.nexusflow.ai.understanding

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val UNDERSTANDING_SCHEMA_NAME = "orbit_r1_user_message_understanding"

internal val UnderstandingSchema: JsonObject =
    buildJsonObject {
        put("type", "object")
        put(
            "required",
            jsonArray("turnIntent", "planningGoalPatch", "constraintDeltas", "clarification", "contextSelection"),
        )
        put("additionalProperties", false)
        put(
            "properties",
            buildJsonObject {
                put(
                    "turnIntent",
                    enumString("conversation", "planning"),
                )
                put("planningGoalPatch", nullableStringSchema())
                put(
                    "constraintDeltas",
                    buildJsonObject {
                        put("type", "array")
                        put(
                            "items",
                            buildJsonObject {
                                put(
                                    "required",
                                    jsonArray(
                                        "operation",
                                        "kind",
                                        "value",
                                        "strength",
                                        "evidenceText",
                                    ),
                                )
                                put("type", "object")
                                put("additionalProperties", false)
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put("operation", enumString("upsert", "remove"))
                                        put(
                                            "kind",
                                            enumString(
                                                "time_window",
                                                "budget_limit",
                                                "commute_limit",
                                                "commute_preference",
                                                "location",
                                                "activity_domain",
                                                "activity_mode",
                                                "topic",
                                                "experience_preference",
                                            ),
                                        )
                                        put("value", requirementValueSchema())
                                        put("strength", nullableEnumString("must", "prefer"))
                                        put("evidenceText", stringSchema())
                                    },
                                )
                            },
                        )
                    },
                )
                put(
                    "clarification",
                    buildJsonObject {
                        put("type", "object")
                        put("required", jsonArray("needed", "missingInformation", "reasonCategory", "questionDraft"))
                        put("additionalProperties", false)
                        put(
                            "properties",
                            buildJsonObject {
                                put("needed", buildJsonObject { put("type", "boolean") })
                                put(
                                    "missingInformation",
                                    buildJsonObject {
                                        put("type", "array")
                                        put("items", stringSchema())
                                    },
                                )
                                put(
                                    "reasonCategory",
                                    enumString(
                                        "none",
                                        "missing_required_information",
                                        "ambiguous_requirement",
                                    ),
                                )
                                put("questionDraft", nullableStringSchema())
                            },
                        )
                    },
                )
                put(
                    "contextSelection",
                    buildJsonObject {
                        put("type", "object")
                        put("required", jsonArray("selectedKeys"))
                        put("additionalProperties", false)
                        put(
                            "properties",
                            buildJsonObject {
                                put(
                                    "selectedKeys",
                                    buildJsonObject {
                                        put("type", "array")
                                        put("items", stringSchema())
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }

private fun requirementValueSchema(): JsonObject =
    buildJsonObject {
        put("type", jsonArray("object", "null"))
        put(
            "required",
            jsonArray(
                "type",
                "textValue",
                "amountWholeUnits",
                "currencyCode",
                "maxMinutes",
                "commutePreference",
                "activityMode",
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
                    "type",
                    enumString(
                        "time_window",
                        "budget_limit",
                        "commute_limit",
                        "commute_preference",
                        "location",
                        "activity_domain",
                        "activity_mode",
                        "topic",
                        "experience_preference",
                    ),
                )
                put("textValue", nullableStringSchema())
                put("amountWholeUnits", nullableNumberSchema("integer"))
                put("currencyCode", nullableStringSchema())
                put("maxMinutes", nullableNumberSchema("integer"))
                put("commutePreference", nullableEnumString("prefer_shorter"))
                put("activityMode", nullableEnumString("at_home", "out_of_home"))
                put("startAt", nullableStringSchema())
                put("endAt", nullableStringSchema())
                put("timeZoneId", nullableStringSchema())
            },
        )
    }

internal fun enumString(vararg values: String): JsonObject =
    buildJsonObject {
        put("type", "string")
        put("enum", JsonArray(values.map(::JsonPrimitive)))
    }

internal fun nullableEnumString(vararg values: String): JsonObject =
    buildJsonObject {
        put("type", jsonArray("string", "null"))
        put("enum", JsonArray(values.map(::JsonPrimitive) + JsonNull))
    }

internal fun stringSchema(): JsonObject =
    buildJsonObject { put("type", "string") }

internal fun nullableStringSchema(): JsonObject =
    buildJsonObject { put("type", jsonArray("string", "null")) }

internal fun nullableNumberSchema(type: String): JsonObject =
    buildJsonObject { put("type", jsonArray(type, "null")) }

internal fun jsonArray(vararg values: String): JsonArray =
    buildJsonArray {
        values.forEach { add(JsonPrimitive(it)) }
    }
