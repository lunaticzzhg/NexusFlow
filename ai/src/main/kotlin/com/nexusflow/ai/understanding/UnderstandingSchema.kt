package com.nexusflow.ai.understanding

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val UNDERSTANDING_SCHEMA_NAME = "orbit_m1_user_message_understanding"

internal val UnderstandingSchema: JsonObject =
    buildJsonObject {
        put("type", "object")
        put("required", jsonArray("userIntent", "intentPatch", "requirementChanges", "clarification", "contextSelection"))
        put("additionalProperties", false)
        put(
            "properties",
            buildJsonObject {
                put("userIntent", enumString("plan_request", "requirement_update", "clarification_response"))
                put("intentPatch", nullableStringSchema())
                put(
                    "requirementChanges",
                    buildJsonObject {
                        put("type", "array")
                        put(
                            "items",
                            buildJsonObject {
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
                                        "commutePreference",
                                        "activityMode",
                                        "startAt",
                                        "endAt",
                                        "timeZoneId",
                                    ),
                                )
                                put("type", "object")
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
                                                "commute_preference",
                                                "location",
                                                "activity_domain",
                                                "activity_mode",
                                                "topic",
                                                "experience_preference",
                                            ),
                                        )
                                        put("strength", enumString("must", "prefer"))
                                        put("evidenceText", stringSchema())
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
                                        "unsupported_request",
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
