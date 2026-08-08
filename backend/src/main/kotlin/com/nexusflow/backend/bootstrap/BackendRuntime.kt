package com.nexusflow.backend.bootstrap

import com.nexusflow.backend.core.health.ReadinessProbe
import com.nexusflow.backend.feature.auth.application.AuthService

internal data class BackendRuntime(
    val readinessProbe: ReadinessProbe,
    val authService: AuthService?,
)
