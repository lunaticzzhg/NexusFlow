package com.nexusflow.ai.answer

import com.nexusflow.ai.understanding.jsonArray
import com.nexusflow.ai.understanding.stringSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal const val CONVERSATION_ANSWER_PROMPT_VERSION = "conversation-answer-v1"
internal const val CONVERSATION_ANSWER_SCHEMA_NAME = "orbit_r1_conversation_answer"

internal val ConversationAnswerSchema: JsonObject =
    buildJsonObject {
        put("type", "object")
        put("required", jsonArray("answer", "coverage"))
        put("additionalProperties", false)
        put(
            "properties",
            buildJsonObject {
                put("answer", stringSchema())
                put(
                    "coverage",
                    buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("required", jsonArray("needId", "status", "usedEvidenceSourceIds"))
                            put("additionalProperties", false)
                            put(
                                "properties",
                                buildJsonObject {
                                    put("needId", stringSchema())
                                    put("status", com.nexusflow.ai.understanding.enumString("answered", "unresolved"))
                                    put(
                                        "usedEvidenceSourceIds",
                                        buildJsonObject {
                                            put("type", "array")
                                            put("items", stringSchema())
                                        },
                                    )
                                },
                            )
                        })
                    },
                )
            },
        )
    }
