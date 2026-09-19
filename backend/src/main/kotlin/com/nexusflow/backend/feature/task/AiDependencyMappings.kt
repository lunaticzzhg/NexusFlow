package com.nexusflow.backend.feature.task

import com.nexusflow.ai.AiTaskCapabilityProvider
import com.nexusflow.backend.core.config.AiProvider

internal fun AiProvider.toAiCapabilityProvider(): AiTaskCapabilityProvider =
    when (this) {
        AiProvider.OpenAi -> AiTaskCapabilityProvider.OpenAi
        AiProvider.Qwen -> AiTaskCapabilityProvider.Qwen
        AiProvider.DeepSeek -> AiTaskCapabilityProvider.DeepSeek
    }
