package com.nexusflow.ai.planner

import com.nexusflow.ai.understanding.enumString
import com.nexusflow.ai.understanding.jsonArray
import com.nexusflow.ai.understanding.stringSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val COMPOSE_PLANS_PROMPT_VERSION = "compose-plans-v1"
internal const val EXPLAIN_PLANS_PROMPT_VERSION = "explain-plans-v1"
internal const val PLANNING_RESEARCH_PROMPT_VERSION = "planning-research-v1"

internal const val COMPOSE_PLANS_SCHEMA_NAME = "orbit_m1_plan_composition"
internal const val EXPLAIN_PLANS_SCHEMA_NAME = "orbit_m1_plan_explanation"
internal const val PLANNING_RESEARCH_SCHEMA_NAME = "orbit_r2_planning_research"
internal const val PLANNING_RESEARCH_MAX_TOOL_CALLS = 5

internal val ComposePlansSchema: JsonObject =
    buildJsonObject {
        put("type", "object")
        put("required", jsonArray("drafts"))
        put("additionalProperties", false)
        put(
            "properties",
            buildJsonObject {
                put(
                    "drafts",
                    buildJsonObject {
                        put("type", "array")
                        put("minItems", 1)
                        put("maxItems", 3)
                        put(
                            "items",
                            buildJsonObject {
                                put("type", "object")
                                put("required", jsonArray("direction", "opportunityRefs"))
                                put("additionalProperties", false)
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put(
                                            "direction",
                                            enumString("best_match", "more_relaxed", "new_experience"),
                                        )
                                        put(
                                            "opportunityRefs",
                                            buildJsonObject {
                                                put("type", "array")
                                                put("minItems", 1)
                                                put("items", stringSchema())
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }

internal val ExplainPlansSchema: JsonObject =
    buildJsonObject {
        put("type", "object")
        put("required", jsonArray("narratives"))
        put("additionalProperties", false)
        put(
            "properties",
            buildJsonObject {
                put(
                    "narratives",
                    buildJsonObject {
                        put("type", "array")
                        put(
                            "items",
                            buildJsonObject {
                                put("type", "object")
                                put("required", jsonArray("planId", "title", "summary", "reasons", "tradeoffs"))
                                put("additionalProperties", false)
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put("planId", stringSchema())
                                        put("title", stringSchema())
                                        put("summary", stringSchema())
                                        put("reasons", narrativePointsSchema())
                                        put("tradeoffs", narrativePointsSchema())
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }

internal val PlanningResearchSchema: JsonObject =
    buildJsonObject {
        put("type", "object")
        put("required", jsonArray("toolCalls"))
        put("additionalProperties", false)
        put(
            "properties",
            buildJsonObject {
                put(
                    "toolCalls",
                    buildJsonObject {
                        put("type", "array")
                        put("maxItems", PLANNING_RESEARCH_MAX_TOOL_CALLS)
                        put(
                            "items",
                            buildJsonObject {
                                put("type", "object")
                                put("required", jsonArray("toolKey", "arguments"))
                                put("additionalProperties", false)
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put("toolKey", stringSchema())
                                        put(
                                            "arguments",
                                            buildJsonObject {
                                                put("type", "object")
                                                put("additionalProperties", true)
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }

private fun narrativePointsSchema(): JsonObject =
    buildJsonObject {
        put("type", "array")
        put(
            "items",
            buildJsonObject {
                put("type", "object")
                put("required", jsonArray("text", "factIds"))
                put("additionalProperties", false)
                put(
                    "properties",
                    buildJsonObject {
                        put("text", stringSchema())
                        put(
                            "factIds",
                            buildJsonObject {
                                put("type", "array")
                                put("items", stringSchema())
                            },
                        )
                    },
                )
            },
        )
    }

@Suppress("unused")
private fun jsonEnumArray(vararg values: String): JsonArray =
    JsonArray(values.map(::JsonPrimitive))
