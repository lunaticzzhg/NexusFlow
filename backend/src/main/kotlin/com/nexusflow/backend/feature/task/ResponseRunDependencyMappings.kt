package com.nexusflow.backend.feature.task

import com.nexusflow.backend.core.config.ResponseRunRuntimeConfig
import com.nexusflow.backend.feature.conversation.application.ResponseRunWorkerConfig

internal fun ResponseRunRuntimeConfig.toWorkerConfig(): ResponseRunWorkerConfig =
    ResponseRunWorkerConfig(
        enabled = workerEnabled,
        pollInterval = pollInterval,
        leaseDuration = leaseDuration,
        heartbeatInterval = heartbeatInterval,
        retryBackoff = retryBackoff,
        maxAttempts = maxAttempts,
    )
