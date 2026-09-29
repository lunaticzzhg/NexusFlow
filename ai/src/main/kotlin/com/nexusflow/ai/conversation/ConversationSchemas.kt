package com.nexusflow.ai.conversation

import com.nexusflow.ai.understanding.enumString
import com.nexusflow.ai.understanding.jsonArray
import com.nexusflow.ai.understanding.stringSchema
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val CONVERSATION_DECISION_PROMPT_VERSION = "conversation-decision-v2"
internal const val CONVERSATION_DECISION_SCHEMA_NAME = "orbit_r1_conversation_decision"

internal val ConversationDecisionSchema =
    buildJsonObject {
        put("type", "object")
        put("required", jsonArray("informationNeeds"))
        put("additionalProperties", false)
        put(
            "properties",
            buildJsonObject {
                put(
                    "informationNeeds",
                    buildJsonObject {
                        put("type", "array")
                        put("minItems", 1)
                        put("maxItems", 6)
                        put(
                            "items",
                            buildJsonObject {
                                put("type", "object")
                                put("required", jsonArray("question", "mode", "toolCalls"))
                                put("additionalProperties", false)
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put("question", stringSchema())
                                        put("mode", enumString("model_only", "tool_enhanced", "tool_required"))
                                        put(
                                            "toolCalls",
                                            buildJsonObject {
                                                put("type", "array")
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
                            },
                        )
                    },
                )
            },
        )
    }
